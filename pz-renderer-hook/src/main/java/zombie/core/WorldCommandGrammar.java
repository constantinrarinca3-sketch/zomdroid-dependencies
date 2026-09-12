package zombie.core;

import zombie.core.Styles.Style;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureDraw.Type;

/**
 * Passive grammar census for tex1 WORLD draws. It never changes the command stream.
 * Counts the exact immediate command before/after each eligible tex1 draw, split by
 * whether a non-zero StartShader is active at that draw.
 */
public final class WorldCommandGrammar {
    private static final boolean CENSUS = "1".equals(System.getenv("MOBILEGLUES_PZ_CENSUS"));
    private static final int REPORT_EVERY = 300;
    private static final int TOP_PAIRS = 10;
    private static final Snapshot TOTAL = new Snapshot();
    private static long frames;

    private WorldCommandGrammar() {}

    public static void observe(zombie.core.sprite.SpriteRenderState state) {
        if (!CENSUS || state == null || state.sprite == null || state.style == null || state.numSprites <= 0) {
            return;
        }
        scan(state.sprite, state.style, state.numSprites, TOTAL);
        frames++;
        if (frames % REPORT_EVERY == 0) {
            System.out.println("ZOMDROID_PZ_WORLD_GRAMMAR_V4 frames=" + frames
                    + " tex1_draws=" + TOTAL.tex1Draws
                    + " shader0=" + TOTAL.shaderZero
                    + " shaderN=" + TOTAL.shaderNonZero
                    + " top=" + TOTAL.topPairs(TOP_PAIRS));
        }
    }

    static Snapshot analyze(TextureDraw[] draws, Style[] styles, int count) {
        Snapshot out = new Snapshot();
        scan(draws, styles, count, out);
        return out;
    }

    private static void scan(TextureDraw[] draws, Style[] styles, int count, Snapshot out) {
        if (draws == null || styles == null || count <= 0) return;
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        int activeShader = 0;

        for (int i = 0; i < limit; i++) {
            TextureDraw draw = draws[i];
            if (draw == null || draw.type == null) continue;

            if (draw.type == Type.StartShader) {
                activeShader = draw.a;
                continue;
            }

            if (draw.type != Type.glDraw || !target(draw, styles[i])) continue;

            boolean shaderNonZero = activeShader != 0;
            Type prev = typeAt(draws, i - 1, limit);
            Type next = typeAt(draws, i + 1, limit);
            out.add(prev, next, shaderNonZero);
        }
    }

    private static boolean target(TextureDraw draw, Style style) {
        return transparent(style)
                && draw.tex != null
                && draw.tex1 != null
                && draw.tex2 == null
                && draw.useAttribArray == -1;
    }

    private static boolean transparent(Style style) {
        return style != null && "zombie.core.Styles.TransparentStyle".equals(style.getClass().getName());
    }

    private static Type typeAt(TextureDraw[] draws, int at, int limit) {
        if (at < 0 || at >= limit) return null;
        TextureDraw draw = draws[at];
        return draw == null ? null : draw.type;
    }

    static final class Snapshot {
        private static final Type[] TYPES = Type.values();
        private static final int NONE = TYPES.length;
        private static final int SIZE = TYPES.length + 1;

        long tex1Draws;
        long shaderZero;
        long shaderNonZero;
        final long[][][] pairs = new long[2][SIZE][SIZE];

        void add(Type prev, Type next, boolean shaderN) {
            tex1Draws++;
            if (shaderN) shaderNonZero++; else shaderZero++;
            pairs[shaderN ? 1 : 0][index(prev)][index(next)]++;
        }

        long pairCount(Type prev, Type next, boolean shaderN) {
            return pairs[shaderN ? 1 : 0][index(prev)][index(next)];
        }

        String topPairs(int max) {
            if (max <= 0) return "-";
            long[] counts = new long[max];
            int[] shaders = new int[max];
            int[] prevs = new int[max];
            int[] nexts = new int[max];

            for (int shader = 0; shader < 2; shader++) {
                for (int prev = 0; prev < SIZE; prev++) {
                    for (int next = 0; next < SIZE; next++) {
                        long count = pairs[shader][prev][next];
                        if (count == 0 || count <= counts[max - 1]) continue;
                        int pos = max - 1;
                        while (pos > 0 && count > counts[pos - 1]) {
                            counts[pos] = counts[pos - 1];
                            shaders[pos] = shaders[pos - 1];
                            prevs[pos] = prevs[pos - 1];
                            nexts[pos] = nexts[pos - 1];
                            pos--;
                        }
                        counts[pos] = count;
                        shaders[pos] = shader;
                        prevs[pos] = prev;
                        nexts[pos] = next;
                    }
                }
            }

            StringBuilder out = new StringBuilder(256);
            for (int i = 0; i < max && counts[i] != 0; i++) {
                if (i != 0) out.append(',');
                out.append(name(prevs[i])).append('>')
                        .append(name(nexts[i])).append('@')
                        .append(shaders[i] == 0 ? "S0" : "SN")
                        .append(':').append(counts[i]);
            }
            return out.length() == 0 ? "-" : out.toString();
        }

        private static int index(Type type) {
            return type == null ? NONE : type.ordinal();
        }

        private static String name(int index) {
            return index == NONE ? "NONE" : TYPES[index].name();
        }
    }
}
