package zombie.core;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.BufferUtils;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.opengl.DepthUniformSnapshot;
import zombie.core.opengl.Shader;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.tileDepth.TileDepthShader;

/**
 * Batches the dominant StartShader + dual-texture WORLD draws by cloning PZ's own
 * tile-depth GLSL at runtime and converting the four per-draw uniforms to vertex data.
 * The clone is only used after a successful prewarm/link; unsupported shaders remain passthrough.
 */
final class DepthBatchRenderer extends TextureDraw.GenericDrawer {
    static final int MAX_PAIRS = 8;
    static final int MAX_DRAWS = 256;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_STREAM_DRAW = 0x88E0;
    private static final int GL_FLOAT = 0x1406;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_TRIANGLES = 0x0004;
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_SHADER_TYPE = 0x8B4F;
    private static final int GL_ATTACHED_SHADERS = 0x8B85;
    private static final int GL_ACTIVE_ATTRIBUTES = 0x8B89;
    private static final int GL_ACTIVE_ATTRIBUTE_MAX_LENGTH = 0x8B8A;
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int STRIDE = 56;
    private static final int MAX_VERTICES = MAX_DRAWS * 6;

    private static final Map<Integer, Backend> BACKENDS = new ConcurrentHashMap<>();
    private final DepthBatchPlanner.Group group;

    DepthBatchRenderer(DepthBatchPlanner.Group group) { this.group = group; }

    static boolean shaderAllowed(int shaderId) {
        Shader shader = Shader.ShaderMap.get(shaderId);
        return shader instanceof TileDepthShader;
    }

    static boolean isReady(int shaderId) {
        Backend b = BACKENDS.get(shaderId);
        return b != null && b.state == State.READY;
    }

    static boolean isFailed(int shaderId) {
        Backend b = BACKENDS.get(shaderId);
        return b != null && b.state == State.FAILED;
    }

    static boolean needsPrewarm(int shaderId) {
        Backend b = BACKENDS.get(shaderId);
        return b == null || b.state == State.COLD;
    }

    static int estimateGroupBatches(DepthBatchPlanner.Group group) {
        int n = group.draws().size();
        Texture[] colors = new Texture[n];
        Texture[] depths = new Texture[n];
        for (int i = 0; i < n; i++) {
            colors[i] = group.draws().get(i).draw().tex;
            depths[i] = group.draws().get(i).draw().tex1;
        }
        return estimateBatches(colors, depths, MAX_PAIRS, MAX_DRAWS);
    }

    static int maxBatchDraws(DepthBatchPlanner.Group group) {
        List<DepthBatchPlanner.DepthDraw> draws = group.draws();
        Object[] ck = new Object[MAX_PAIRS];
        Object[] dk = new Object[MAX_PAIRS];
        int slots = 0, count = 0, max = 0;
        for (DepthBatchPlanner.DepthDraw dd : draws) {
            TextureDraw d = dd.draw();
            Object c = textureKey(d.tex), dep = textureKey(d.tex1);
            int slot = pairSlot(ck, dk, slots, c, dep);
            if (count >= MAX_DRAWS || (slot < 0 && slots >= MAX_PAIRS)) {
                max = Math.max(max, count);
                slots = 0; count = 0; slot = -1;
            }
            if (slot < 0) { ck[slots] = c; dk[slots] = dep; slots++; }
            count++;
        }
        return Math.max(max, count);
    }

    static int estimateBatches(Texture[] colors, Texture[] depths, int maxPairs, int maxDraws) {
        if (colors == null || depths == null || colors.length != depths.length || colors.length == 0) return 0;
        Object[] ck = new Object[Math.max(1, maxPairs)];
        Object[] dk = new Object[Math.max(1, maxPairs)];
        int slots = 0, count = 0, batches = 0;
        for (int i = 0; i < colors.length; i++) {
            Object c = textureKey(colors[i]), d = textureKey(depths[i]);
            int slot = pairSlot(ck, dk, slots, c, d);
            if (count >= maxDraws || (slot < 0 && slots >= maxPairs)) {
                batches++; slots = 0; count = 0; slot = -1;
            }
            if (slot < 0) { ck[slots] = c; dk[slots] = d; slots++; }
            count++;
        }
        return batches + (count == 0 ? 0 : 1);
    }

    private static Object textureKey(Texture texture) {
        if (texture == null) return null;
        Object id = texture.getTextureId();
        return id == null ? texture : id;
    }

    private static int pairSlot(Object[] colors, Object[] depths, int slots, Object color, Object depth) {
        for (int i = 0; i < slots; i++) if (colors[i] == color && depths[i] == depth) return i;
        return -1;
    }

    @Override public void render() {
        Backend backend = BACKENDS.get(group.shaderId());
        if (backend == null || backend.state != State.READY) return;
        backend.render(group.draws());
    }

    static final class PrewarmDrawer extends TextureDraw.GenericDrawer {
        private final int shaderId;
        private final DepthUniformSnapshot sample;
        PrewarmDrawer(int shaderId, DepthUniformSnapshot sample) { this.shaderId = shaderId; this.sample = sample; }
        @Override public void render() {
            Backend backend = BACKENDS.computeIfAbsent(shaderId, Backend::new);
            backend.initialize(sample);
        }
    }

    private static final class Backend {
        final int sourceProgram;
        volatile State state = State.COLD;
        int program, vao, vbo, mvpLocation, activeSlots;
        final Matrix4f mvp = new Matrix4f();
        final float[] mvpData = new float[16];
        final int[] diffuseLoc = new int[MAX_PAIRS];
        final int[] depthLoc = new int[MAX_PAIRS];
        final ByteBuffer upload = BufferUtils.createByteBuffer(MAX_VERTICES * STRIDE);
        final Object[] colorKeys = new Object[MAX_PAIRS];
        final Object[] depthKeys = new Object[MAX_PAIRS];
        final Texture[] colorTextures = new Texture[MAX_PAIRS];
        final Texture[] depthTextures = new Texture[MAX_PAIRS];

        Backend(int sourceProgram) { this.sourceProgram = sourceProgram; }

        synchronized void initialize(DepthUniformSnapshot sample) {
            if (state != State.COLD) return;
            state = State.INITIALIZING;
            try {
                if (!validateUniformLocations(sample)) throw new IllegalStateException("uniform layout mismatch");
                String[] source = captureMainSources(sourceProgram);
                TileDepthShaderTransformer.Sources transformed = TileDepthShaderTransformer.transform(source[0], source[1], MAX_PAIRS);
                if (transformed == null) throw new IllegalStateException("unsupported tile-depth GLSL shape");
                int vs = compile(GL_VERTEX_SHADER, transformed.vertex());
                int fs = compile(GL_FRAGMENT_SHADER, transformed.fragment());
                program = GL20.glCreateProgram();
                GL20.glAttachShader(program, vs);
                GL20.glAttachShader(program, fs);
                mirrorAttributeLocations(sourceProgram, program);
                GL20.glBindAttribLocation(program, 5, "zd_BatchSlot");
                GL20.glBindAttribLocation(program, 6, "zd_Params");
                GL20.glLinkProgram(program);
                if (GL20.glGetProgrami(program, GL_LINK_STATUS) == 0) throw new IllegalStateException("depth-link: " + GL20.glGetProgramInfoLog(program));
                GL20.glDeleteShader(vs); GL20.glDeleteShader(fs);
                GL20.glUseProgram(program);
                mvpLocation = GL20.glGetUniformLocation(program, "ModelViewProjection");
                if (mvpLocation < 0) throw new IllegalStateException("missing ModelViewProjection");
                for (int i = 0; i < MAX_PAIRS; i++) {
                    diffuseLoc[i] = GL20.glGetUniformLocation(program, "ZD_DIFFUSE" + i);
                    depthLoc[i] = GL20.glGetUniformLocation(program, "ZD_DEPTH" + i);
                    if (diffuseLoc[i] < 0 || depthLoc[i] < 0) throw new IllegalStateException("missing sampler bank " + i);
                    GL20.glUniform1i(diffuseLoc[i], i);
                    GL20.glUniform1i(depthLoc[i], MAX_PAIRS + i);
                }
                vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
                GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL_ARRAY_BUFFER, vbo);
                GL15.glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * STRIDE, GL_STREAM_DRAW);
                attrib(0, 2, GL_FLOAT, false, 0L); attrib(1, 2, GL_FLOAT, false, 8L);
                attrib(2, 4, GL_UNSIGNED_BYTE, true, 16L); attrib(3, 2, GL_FLOAT, false, 20L);
                attrib(4, 2, GL_FLOAT, false, 28L); attrib(5, 1, GL_FLOAT, false, 36L);
                attrib(6, 4, GL_FLOAT, false, 40L);
                state = State.READY;
                if (PZWorldCompiler.censusEnabled())
                    System.out.println("ZOMDROID_PZ_DEPTH_BATCH_V5 renderer=ready shader=" + sourceProgram + " pairs=" + MAX_PAIRS);
            } catch (Throwable failure) {
                state = State.FAILED;
                PZWorldCompiler.depthRendererFailed(sourceProgram, failure);
            } finally {
                try {
                    GL30.glBindVertexArray(0); GL15.glBindBuffer(GL_ARRAY_BUFFER, 0);
                    ShaderHelper.forgetCurrentlyBound(); ShaderHelper.glUseProgramObjectARB(0);
                    GL13.glActiveTexture(GL_TEXTURE0);
                } catch (Throwable ignored) { }
            }
        }

        private boolean validateUniformLocations(DepthUniformSnapshot p) {
            return p != null
                    && GL20.glGetUniformLocation(sourceProgram, "zDepth") == p.zDepthLocation()
                    && GL20.glGetUniformLocation(sourceProgram, "drawPixels") == p.drawPixelsLocation()
                    && GL20.glGetUniformLocation(sourceProgram, "zDepthBlendZ") == p.zDepthBlendZLocation()
                    && GL20.glGetUniformLocation(sourceProgram, "zDepthBlendToZ") == p.zDepthBlendToZLocation();
        }

        void render(List<DepthBatchPlanner.DepthDraw> draws) {
            try {
                GL20.glUseProgram(program);
                VertexBufferObject.getModelViewProjection(mvp); mvp.get(mvpData);
                GL20.glUniformMatrix4fv(mvpLocation, false, mvpData);
                GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL_ARRAY_BUFFER, vbo);
                int first = 0;
                while (first < draws.size()) {
                    int end = fillBatch(draws, first);
                    bindPairs(); upload.flip();
                    GL15.glBufferSubData(GL_ARRAY_BUFFER, 0L, upload);
                    GL11.glDrawArrays(GL_TRIANGLES, 0, (end - first) * 6);
                    first = end;
                }
            } catch (Throwable failure) {
                state = State.FAILED;
                PZWorldCompiler.depthRendererFailed(sourceProgram, failure);
            } finally {
                try {
                    GL30.glBindVertexArray(0); GL15.glBindBuffer(GL_ARRAY_BUFFER, 0);
                    ShaderHelper.forgetCurrentlyBound(); ShaderHelper.glUseProgramObjectARB(0);
                    if (!draws.isEmpty()) {
                        TextureDraw last = draws.get(draws.size() - 1).draw();
                        GL13.glActiveTexture(GL_TEXTURE0); GL11.glBindTexture(GL_TEXTURE_2D, last.tex.getID());
                        GL13.glActiveTexture(GL_TEXTURE0 + 1); GL11.glBindTexture(GL_TEXTURE_2D, last.tex1.getID());
                    }
                    GL13.glActiveTexture(GL_TEXTURE0); Texture.lastTextureID = 0;
                    SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                    SpriteRenderer.ringBuffer.restoreVbos = true;
                } catch (Throwable ignored) { state = State.FAILED; }
            }
        }

        private int fillBatch(List<DepthBatchPlanner.DepthDraw> draws, int first) {
            upload.clear(); int slots = 0; int end = first;
            while (end < draws.size() && end - first < MAX_DRAWS) {
                TextureDraw d = draws.get(end).draw();
                Object ck = textureKey(d.tex), dk = textureKey(d.tex1);
                int slot = pairSlot(colorKeys, depthKeys, slots, ck, dk);
                if (slot < 0) {
                    if (slots == MAX_PAIRS) break;
                    slot = slots++;
                    colorKeys[slot] = ck; depthKeys[slot] = dk;
                    colorTextures[slot] = d.tex; depthTextures[slot] = d.tex1;
                }
                putQuad(draws.get(end), slot); end++;
            }
            for (int i = slots; i < MAX_PAIRS; i++) {
                colorKeys[i] = depthKeys[i] = null;
                colorTextures[i] = depthTextures[i] = null;
            }
            activeSlots = slots; return end;
        }

        private void bindPairs() {
            for (int i = 0; i < activeSlots; i++) {
                GL13.glActiveTexture(GL_TEXTURE0 + i); GL11.glBindTexture(GL_TEXTURE_2D, colorTextures[i].getID());
                GL13.glActiveTexture(GL_TEXTURE0 + MAX_PAIRS + i); GL11.glBindTexture(GL_TEXTURE_2D, depthTextures[i].getID());
            }
        }

        private void putQuad(DepthBatchPlanner.DepthDraw dd, int slot) {
            TextureDraw d = dd.draw();
            int[] order = d.getColor(0) == d.getColor(2) ? ORDER_A : ORDER_B;
            for (int vertex : order) putVertex(d, dd.params(), vertex, slot);
        }

        private void putVertex(TextureDraw d, DepthUniformSnapshot p, int v, int slot) {
            float x, y, u, vv, u1, v1; int color;
            switch (v) {
                case 0 -> { x=d.x0; y=d.y0; u=d.flipped?d.u1:d.u0; vv=d.v0; u1=d.tex1U0; v1=d.tex1V0; color=d.getColor(0); }
                case 1 -> { x=d.x1; y=d.y1; u=d.flipped?d.u0:d.u1; vv=d.v1; u1=d.tex1U1; v1=d.tex1V1; color=d.getColor(1); }
                case 2 -> { x=d.x2; y=d.y2; u=d.flipped?d.u3:d.u2; vv=d.v2; u1=d.tex1U2; v1=d.tex1V2; color=d.getColor(2); }
                default -> { x=d.x3; y=d.y3; u=d.flipped?d.u2:d.u3; vv=d.v3; u1=d.tex1U3; v1=d.tex1V3; color=d.getColor(3); }
            }
            upload.putFloat(x).putFloat(y).putFloat(u).putFloat(vv).putInt(color)
                    .putFloat(u1).putFloat(v1).putFloat(0f).putFloat(0f).putFloat(slot)
                    .putFloat(p.zDepth()).putFloat(p.drawPixels()).putFloat(p.zDepthBlendZ()).putFloat(p.zDepthBlendToZ());
        }

        private static void attrib(int index, int size, int type, boolean normalized, long offset) {
            GL20.glEnableVertexAttribArray(index);
            GL20.glVertexAttribPointer(index, size, type, normalized, STRIDE, offset);
        }
    }

    private static final int[] ORDER_A = {0,1,2,0,2,3};
    private static final int[] ORDER_B = {1,2,3,1,3,0};

    private static String[] captureMainSources(int program) {
        int count = GL20.glGetProgrami(program, GL_ATTACHED_SHADERS);
        if (count <= 0) throw new IllegalStateException("no attached shaders");
        int[] shaders = new int[count]; int[] actual = new int[1];
        GL20.glGetAttachedShaders(program, actual, shaders);
        String vertex = null, fragment = null;
        for (int i = 0; i < actual[0]; i++) {
            int shader = shaders[i]; String source = GL20.glGetShaderSource(shader);
            if (source == null || !source.contains("void main")) continue;
            int type = GL20.glGetShaderi(shader, GL_SHADER_TYPE);
            if (type == GL_VERTEX_SHADER) {
                if (vertex != null) throw new IllegalStateException("multiple vertex main shaders");
                vertex = source;
            } else if (type == GL_FRAGMENT_SHADER) {
                if (fragment != null) throw new IllegalStateException("multiple fragment main shaders");
                fragment = source;
            }
        }
        if (vertex == null || fragment == null) throw new IllegalStateException("missing main shader source");
        return new String[]{vertex, fragment};
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, 0x8B81) == 0)
            throw new IllegalStateException((type == GL_VERTEX_SHADER ? "depth-vertex: " : "depth-fragment: ") + GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static void mirrorAttributeLocations(int sourceProgram, int targetProgram) {
        int count = GL20.glGetProgrami(sourceProgram, GL_ACTIVE_ATTRIBUTES);
        int maxLength = Math.max(1, GL20.glGetProgrami(sourceProgram, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH));
        IntBuffer size = BufferUtils.createIntBuffer(1); IntBuffer type = BufferUtils.createIntBuffer(1);
        for (int i = 0; i < count; i++) {
            size.clear(); type.clear();
            String name = GL20.glGetActiveAttrib(sourceProgram, i, maxLength, size, type);
            int location = GL20.glGetAttribLocation(sourceProgram, name);
            if (location >= 5) throw new IllegalStateException("original attrib conflicts with V5 slot: " + name + "@" + location);
            if (location >= 0) GL20.glBindAttribLocation(targetProgram, location, name);
        }
    }

    private enum State { COLD, INITIALIZING, READY, FAILED }
}
