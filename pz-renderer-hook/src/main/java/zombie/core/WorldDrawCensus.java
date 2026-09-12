package zombie.core;

import java.util.IdentityHashMap;
import zombie.core.Styles.Style;
import zombie.core.Styles.TransparentStyle;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureDraw.Type;

/** Read-only census of the PZ WORLD command stream before RingBuffer compilation. */
public final class WorldDrawCensus {
    private static final boolean CENSUS = "1".equals(System.getenv("MOBILEGLUES_PZ_CENSUS"));
    private static final int REPORT_EVERY = 300;
    private static final int PLAIN_TEXTURE_BUDGET = 16;
    private static final int DEPTH_TEXTURE_BUDGET = 8;
    private static final int MAX_INSTANCES = 256;

    private static long frames;
    private static long entries;
    private static long glDraws;
    private static long transparentDraws;
    private static long otherStyleDraws;
    private static long attribDraws;
    private static long noTextureDraws;
    private static long plainSource;
    private static long plainBatches;
    private static long plainRuns;
    private static long plainSameTextureAdjacent;
    private static long depthSource;
    private static long depthBatches;
    private static long depthRuns;
    private static long tex2Draws;
    private static int plainMaxRun;
    private static int depthMaxRun;

    private WorldDrawCensus() {}

    public static boolean enabled() { return CENSUS; }

    public static void observe(SpriteRenderState state) {
        if (!CENSUS || state == null || state.sprite == null || state.style == null || state.numSprites <= 0) {
            return;
        }
        Estimate e = estimate(state.sprite, state.style, state.numSprites,
                PLAIN_TEXTURE_BUDGET, DEPTH_TEXTURE_BUDGET, MAX_INSTANCES);
        frames++;
        entries += e.entries;
        glDraws += e.glDraws;
        transparentDraws += e.transparentDraws;
        otherStyleDraws += e.otherStyleDraws;
        attribDraws += e.attribDraws;
        noTextureDraws += e.noTextureDraws;
        plainSource += e.plainSource;
        plainBatches += e.plainBatches;
        plainRuns += e.plainRuns;
        plainSameTextureAdjacent += e.plainSameTextureAdjacent;
        depthSource += e.depthSource;
        depthBatches += e.depthBatches;
        depthRuns += e.depthRuns;
        tex2Draws += e.tex2Draws;
        plainMaxRun = Math.max(plainMaxRun, e.plainMaxRun);
        depthMaxRun = Math.max(depthMaxRun, e.depthMaxRun);

        if (frames % REPORT_EVERY == 0) {
            System.out.println("ZOMDROID_PZ_WORLD_CENSUS_V5 frames=" + frames
                    + " entries=" + entries
                    + " gl_draw=" + glDraws
                    + " transparent=" + transparentDraws
                    + " other_style=" + otherStyleDraws
                    + " attrib=" + attribDraws
                    + " no_tex=" + noTextureDraws
                    + " plain_src=" + plainSource
                    + " plain_batches16=" + plainBatches
                    + " plain_elim_est=" + (plainSource - plainBatches)
                    + " plain_runs=" + plainRuns
                    + " plain_max_run=" + plainMaxRun
                    + " plain_same_tex_adj=" + plainSameTextureAdjacent
                    + " depth_src=" + depthSource
                    + " depth_batches8=" + depthBatches
                    + " depth_elim_est=" + (depthSource - depthBatches)
                    + " depth_runs=" + depthRuns
                    + " depth_max_run=" + depthMaxRun
                    + " tex2=" + tex2Draws);
        }
    }

    static Estimate estimate(TextureDraw[] draws, Style[] styles, int count,
                             int plainTextureBudget, int depthTextureBudget, int maxInstances) {
        Estimate out = new Estimate();
        if (draws == null || styles == null || count <= 0) return out;
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        out.entries = limit;

        IdentityHashMap<Texture, Boolean> plainTextures = new IdentityHashMap<>();
        IdentityHashMap<Texture, Boolean> depthColors = new IdentityHashMap<>();
        IdentityHashMap<Texture, Boolean> depthTextures = new IdentityHashMap<>();
        int plainBatchInstances = 0;
        int depthBatchInstances = 0;
        int plainRun = 0;
        int depthRun = 0;
        Texture previousPlainTexture = null;

        for (int i = 0; i < limit; i++) {
            TextureDraw draw = draws[i];
            Style style = styles[i];
            boolean isDraw = draw != null && draw.type == Type.glDraw;
            if (!isDraw) {
                if (plainRun > 0) out.plainRuns++;
                if (depthRun > 0) out.depthRuns++;
                plainRun = 0;
                depthRun = 0;
                plainBatchInstances = 0;
                depthBatchInstances = 0;
                plainTextures.clear();
                depthColors.clear();
                depthTextures.clear();
                previousPlainTexture = null;
                continue;
            }

            out.glDraws++;
            boolean transparent = style == TransparentStyle.instance;
            if (transparent) out.transparentDraws++; else out.otherStyleDraws++;
            if (draw.useAttribArray != -1) out.attribDraws++;
            if (draw.tex == null) out.noTextureDraws++;
            if (draw.tex2 != null) out.tex2Draws++;

            boolean common = transparent && draw.useAttribArray == -1 && draw.tex != null;
            boolean plain = common && draw.tex1 == null && draw.tex2 == null;
            boolean depth = common && draw.tex1 != null && draw.tex2 == null;

            if (plain) {
                if (depthRun > 0) out.depthRuns++;
                depthRun = 0;
                depthBatchInstances = 0;
                depthColors.clear();
                depthTextures.clear();

                if (plainRun == 0) previousPlainTexture = null;
                plainRun++;
                out.plainSource++;
                out.plainMaxRun = Math.max(out.plainMaxRun, plainRun);
                if (previousPlainTexture == draw.tex) out.plainSameTextureAdjacent++;
                previousPlainTexture = draw.tex;

                boolean newTexture = !plainTextures.containsKey(draw.tex);
                if (plainBatchInstances == 0
                        || plainBatchInstances >= maxInstances
                        || (newTexture && plainTextures.size() >= plainTextureBudget)) {
                    out.plainBatches++;
                    plainTextures.clear();
                    plainBatchInstances = 0;
                }
                plainTextures.put(draw.tex, Boolean.TRUE);
                plainBatchInstances++;
                continue;
            }

            if (depth) {
                if (plainRun > 0) out.plainRuns++;
                plainRun = 0;
                plainBatchInstances = 0;
                plainTextures.clear();
                previousPlainTexture = null;

                depthRun++;
                out.depthSource++;
                out.depthMaxRun = Math.max(out.depthMaxRun, depthRun);
                boolean newColor = !depthColors.containsKey(draw.tex);
                boolean newDepth = !depthTextures.containsKey(draw.tex1);
                if (depthBatchInstances == 0
                        || depthBatchInstances >= maxInstances
                        || (newColor && depthColors.size() >= depthTextureBudget)
                        || (newDepth && depthTextures.size() >= depthTextureBudget)) {
                    out.depthBatches++;
                    depthColors.clear();
                    depthTextures.clear();
                    depthBatchInstances = 0;
                }
                depthColors.put(draw.tex, Boolean.TRUE);
                depthTextures.put(draw.tex1, Boolean.TRUE);
                depthBatchInstances++;
                continue;
            }

            if (plainRun > 0) out.plainRuns++;
            if (depthRun > 0) out.depthRuns++;
            plainRun = 0;
            depthRun = 0;
            plainBatchInstances = 0;
            depthBatchInstances = 0;
            plainTextures.clear();
            depthColors.clear();
            depthTextures.clear();
            previousPlainTexture = null;
        }

        if (plainRun > 0) out.plainRuns++;
        if (depthRun > 0) out.depthRuns++;
        return out;
    }

    static final class Estimate {
        long entries;
        long glDraws;
        long transparentDraws;
        long otherStyleDraws;
        long attribDraws;
        long noTextureDraws;
        long tex2Draws;
        long plainSource;
        long plainBatches;
        long plainRuns;
        long plainSameTextureAdjacent;
        int plainMaxRun;
        long depthSource;
        long depthBatches;
        long depthRuns;
        int depthMaxRun;

        long plainEliminated() { return plainSource - plainBatches; }
        long depthEliminated() { return depthSource - depthBatches; }
    }
}
