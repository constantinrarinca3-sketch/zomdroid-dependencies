package zombie.core;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

import java.util.List;

/** Executes one instanced backend draw for as many proven-safe chunk color/depth pairs as texture units allow. */
final class ChunkBatchRenderer extends TextureDraw.GenericDrawer {
    private static final int GL_CURRENT_PROGRAM = 0x8B8D;
    private static final int GL_VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int GL_ARRAY_BUFFER_BINDING = 0x8894;
    private static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_STREAM_DRAW = 0x88E0;
    private static final int GL_FLOAT = 0x1406;
    private static final int GL_TRIANGLES = 0x0004;
    private static final int FLOATS_PER_INSTANCE = 18;
    private static final int STRIDE = FLOATS_PER_INSTANCE * Float.BYTES;
    private static final int MAX_PAIRS = 8;

    private static volatile State state = State.COLD;
    private static int program;
    private static int vao;
    private static int vbo;
    private static int mvpLocation;
    private static int batchSize = MAX_PAIRS;
    private static int textureUnits;
    private static final int[] colorLocations = new int[MAX_PAIRS];
    private static final int[] depthLocations = new int[MAX_PAIRS];
    private static float[] upload = new float[MAX_PAIRS * FLOATS_PER_INSTANCE];
    private static final float[] matrix = new float[16];
    private static final Matrix4f mvp = new Matrix4f();

    private final List<PZWorldCompiler.ChunkDraw> chunks;

    ChunkBatchRenderer(List<PZWorldCompiler.ChunkDraw> chunks) {
        this.chunks = chunks;
    }

    static boolean isReady() {
        return state == State.READY;
    }

    static int batchSize() {
        return batchSize;
    }

    static int chooseBatchSize(int availableTextureUnits) {
        return Math.min(MAX_PAIRS, Math.max(0, availableTextureUnits / 2));
    }

    @Override
    public void render() {
        if (state != State.READY) return;
        try {
            GL20.glUseProgram(program);
            GL14.glBlendFuncSeparate(1, 771, 773, 1);
            GL11.glDepthFunc(515);
            GL11.glDepthMask(true);
            GL11.glEnable(2929);
            VertexBufferObject.getModelViewProjection(mvp);
            mvp.get(matrix);
            GL20.glUniformMatrix4fv(mvpLocation, false, matrix);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL_ARRAY_BUFFER, vbo);

            for (int first = 0; first < chunks.size(); first += batchSize) {
                int count = Math.min(batchSize, chunks.size() - first);
                uploadBatch(first, count);
                GL15.glBufferSubData(GL_ARRAY_BUFFER, 0L, upload);

                for (int slot = 0; slot < count; slot++) {
                    PZWorldCompiler.ChunkDraw chunk = chunks.get(first + slot);
                    GL13.glActiveTexture(GL_TEXTURE0 + slot);
                    chunk.color.bind();
                    GL13.glActiveTexture(GL_TEXTURE0 + batchSize + slot);
                    GL11.glBindTexture(GL_TEXTURE_2D, chunk.depth.getID());
                }
                GL31.glDrawArraysInstanced(GL_TRIANGLES, 0, 6, count);
            }
        } catch (Throwable failure) {
            state = State.FAILED;
            PZWorldCompiler.rendererFailed(failure);
        } finally {
            try {
                GL30.glBindVertexArray(0);
                GL15.glBindBuffer(GL_ARRAY_BUFFER, 0);
                ShaderHelper.forgetCurrentlyBound();
                ShaderHelper.glUseProgramObjectARB(0);
                GL13.glActiveTexture(GL_TEXTURE0);
                Texture.lastTextureID = 0;
                SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                SpriteRenderer.ringBuffer.restoreVbos = true;
            } catch (Throwable ignored) {
                state = State.FAILED;
            }
        }
    }

    private void uploadBatch(int first, int count) {
        for (int slot = 0; slot < count; slot++) {
            PZWorldCompiler.ChunkDraw chunk = chunks.get(first + slot);
            int offset = slot * FLOATS_PER_INSTANCE;
            System.arraycopy(chunk.data, 0, upload, offset, FLOATS_PER_INSTANCE);
            upload[offset + 17] = slot;
        }
    }

    private static void initialize() {
        if (state != State.COLD) return;
        state = State.INITIALIZING;
        int previousProgram = GL11.glGetInteger(GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            textureUnits = GL11.glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
            batchSize = chooseBatchSize(textureUnits);
            if (batchSize < 2) {
                throw new IllegalStateException("need at least 4 fragment texture units, got " + textureUnits);
            }
            upload = new float[batchSize * FLOATS_PER_INSTANCE];

            int vertex = compileShader(0x8B31, vertexShader());
            int fragment = compileShader(0x8B30, fragmentShader(batchSize));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, 0x8B82) == 0) {
                throw new IllegalStateException("link: " + GL20.glGetProgramInfoLog(program));
            }
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);

            GL20.glUseProgram(program);
            mvpLocation = GL20.glGetUniformLocation(program, "ModelViewProjection");
            if (mvpLocation < 0) throw new IllegalStateException("missing ModelViewProjection");
            for (int i = 0; i < batchSize; i++) {
                colorLocations[i] = GL20.glGetUniformLocation(program, "uColor" + i);
                depthLocations[i] = GL20.glGetUniformLocation(program, "uDepth" + i);
                GL20.glUniform1i(colorLocations[i], i);
                GL20.glUniform1i(depthLocations[i], batchSize + i);
            }

            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL_ARRAY_BUFFER, (long) batchSize * STRIDE, GL_STREAM_DRAW);
            attribute(0, 4, 0L);
            attribute(1, 4, 16L);
            attribute(2, 4, 32L);
            attribute(3, 4, 48L);
            attribute(4, 2, 64L);
            state = State.READY;
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V5 renderer=ready batch=" + batchSize
                    + " tex_units=" + textureUnits);
        } catch (Throwable failure) {
            state = State.FAILED;
            PZWorldCompiler.rendererFailed(failure);
        } finally {
            GL30.glBindVertexArray(previousVao);
            GL15.glBindBuffer(GL_ARRAY_BUFFER, previousArrayBuffer);
            GL20.glUseProgram(previousProgram);
            ShaderHelper.forgetCurrentlyBound();
        }
    }

    private static void attribute(int index, int components, long offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, components, GL_FLOAT, false, STRIDE, offset);
        GL33.glVertexAttribDivisor(index, 1);
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, 0x8B81) == 0) {
            throw new IllegalStateException("shader: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static String vertexShader() {
        return "#version 330 core\n"
                + "layout(location=0) in vec4 iPos01;\n"
                + "layout(location=1) in vec4 iPos23;\n"
                + "layout(location=2) in vec4 iUv01;\n"
                + "layout(location=3) in vec4 iUv23;\n"
                + "layout(location=4) in vec2 iDepthSlot;\n"
                + "uniform mat4 ModelViewProjection;\n"
                + "out vec2 vUv; flat out int vSlot; flat out float vDepth;\n"
                + "void main(){\n"
                + " int q=(gl_VertexID==0||gl_VertexID==3)?0:((gl_VertexID==1)?1:((gl_VertexID==2||gl_VertexID==4)?2:3));\n"
                + " vec2 p=q==0?iPos01.xy:(q==1?iPos01.zw:(q==2?iPos23.xy:iPos23.zw));\n"
                + " vUv=q==0?iUv01.xy:(q==1?iUv01.zw:(q==2?iUv23.xy:iUv23.zw));\n"
                + " vSlot=int(iDepthSlot.y+0.5); vDepth=iDepthSlot.x;\n"
                + " gl_Position=ModelViewProjection*vec4(p,0.0,1.0);\n"
                + "}\n";
    }

    private static String fragmentShader(int pairs) {
        StringBuilder shader = new StringBuilder("#version 330 core\n");
        for (int i = 0; i < pairs; i++) shader.append("uniform sampler2D uColor").append(i).append(";\n");
        for (int i = 0; i < pairs; i++) shader.append("uniform sampler2D uDepth").append(i).append(";\n");
        shader.append("in vec2 vUv; flat in int vSlot; flat in float vDepth; out vec4 fragColor;\n")
                .append("vec4 colorAt(int s){\n");
        for (int i = 0; i < pairs - 1; i++) {
            shader.append(" if(s==").append(i).append(") return texture(uColor").append(i).append(",vUv);\n");
        }
        shader.append(" return texture(uColor").append(pairs - 1).append(",vUv); }\nfloat depthAt(int s){\n");
        for (int i = 0; i < pairs - 1; i++) {
            shader.append(" if(s==").append(i).append(") return texture(uDepth").append(i).append(",vUv).r;\n");
        }
        shader.append(" return texture(uDepth").append(pairs - 1).append(",vUv).r; }\n")
                .append("void main(){ fragColor=colorAt(vSlot); gl_FragDepth=vDepth+depthAt(vSlot); }\n");
        return shader.toString();
    }

    static final class PrewarmDrawer extends TextureDraw.GenericDrawer {
        @Override
        public void render() {
            initialize();
        }
    }

    private enum State { COLD, INITIALIZING, READY, FAILED }
}
