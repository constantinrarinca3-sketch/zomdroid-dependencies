package zombie.core.opengl;

import zombie.core.textures.TextureDraw;

/** Immutable copy of the four per-sprite uniforms emitted by IsoSprite.startTileDepthShader. */
public final class DepthUniformSnapshot {
    private final int zDepthLocation;
    private final float zDepth;
    private final int drawPixelsLocation;
    private final int drawPixels;
    private final int zDepthBlendZLocation;
    private final float zDepthBlendZ;
    private final int zDepthBlendToZLocation;
    private final float zDepthBlendToZ;

    private DepthUniformSnapshot(int zDepthLocation, float zDepth,
                                 int drawPixelsLocation, int drawPixels,
                                 int zDepthBlendZLocation, float zDepthBlendZ,
                                 int zDepthBlendToZLocation, float zDepthBlendToZ) {
        this.zDepthLocation = zDepthLocation;
        this.zDepth = zDepth;
        this.drawPixelsLocation = drawPixelsLocation;
        this.drawPixels = drawPixels;
        this.zDepthBlendZLocation = zDepthBlendZLocation;
        this.zDepthBlendZ = zDepthBlendZ;
        this.zDepthBlendToZLocation = zDepthBlendToZLocation;
        this.zDepthBlendToZ = zDepthBlendToZ;
    }

    public static DepthUniformSnapshot capture(TextureDraw.GenericDrawer drawer) {
        if (!(drawer instanceof ShaderUniformSetter a)) return null;
        ShaderUniformSetter b = a.next;
        ShaderUniformSetter c = b == null ? null : b.next;
        ShaderUniformSetter d = c == null ? null : c.next;
        if (b == null || c == null || d == null || d.next != null) return null;
        if (a.type != ShaderUniformSetter.Type.Uniform1f
                || (b.type != ShaderUniformSetter.Type.Uniform1i
                    && b.type != ShaderUniformSetter.Type.NIL)
                || c.type != ShaderUniformSetter.Type.Uniform1f
                || d.type != ShaderUniformSetter.Type.Uniform1f) {
            return null;
        }
        if (!Float.isFinite(a.f1) || !Float.isFinite(c.f1) || !Float.isFinite(d.f1)) return null;
        // B42.20.3 declares drawPixels but no longer reads it (glColorMask is used
        // instead), so the driver legitimately optimizes that uniform out. PZ then
        // records a NIL setter in the otherwise exact four-node depth chain.
        int drawPixelsLocation = b.type == ShaderUniformSetter.Type.NIL ? -1 : b.location;
        int drawPixels = b.type == ShaderUniformSetter.Type.NIL ? 0 : b.i1;
        return new DepthUniformSnapshot(a.location, a.f1, drawPixelsLocation, drawPixels,
                c.location, c.f1, d.location, d.f1);
    }

    public int zDepthLocation() { return zDepthLocation; }
    public float zDepth() { return zDepth; }
    public int drawPixelsLocation() { return drawPixelsLocation; }
    public int drawPixels() { return drawPixels; }
    public int zDepthBlendZLocation() { return zDepthBlendZLocation; }
    public float zDepthBlendZ() { return zDepthBlendZ; }
    public int zDepthBlendToZLocation() { return zDepthBlendToZLocation; }
    public float zDepthBlendToZ() { return zDepthBlendToZ; }
}
