package zombie.core;

import zombie.core.Styles.Style;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureDraw.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles only PZ's semantic combined-chunk interval. It never scans UI or guesses a pass from
 * shader state: FBORenderChunkManager itself emits glBuffer(10) and glBuffer(11) around the block.
 */
public final class PZWorldCompiler {
    private static final ThreadLocal<Boolean> WORLD_PASS = ThreadLocal.withInitial(() -> false);
    private static final boolean CENSUS = "1".equals(System.getenv("MOBILEGLUES_PZ_CENSUS"));
    private static final int REPORT_EVERY = 300;

    private static volatile boolean disabled;
    private static long frames;
    private static long blocks;
    private static long sourceDraws;
    private static long backendDraws;
    private static long fallbacks;
    private static long prewarms;
    private static int maxBatch;

    private PZWorldCompiler() {
    }

    public static boolean enterWorldPass() {
        boolean previous = WORLD_PASS.get();
        WORLD_PASS.set(true);
        return previous;
    }

    public static void leaveWorldPass(boolean previous) {
        WORLD_PASS.set(previous);
    }

    /** Returns true only when this method has emitted the complete replacement command stream. */
    public static boolean compile(TextureDraw[] draws, Style[] styles, int count) {
        if (!WORLD_PASS.get() || disabled || draws == null || styles == null || count <= 0) {
            return false;
        }

        final List<Block> candidates;
        try {
            candidates = findBlocks(draws, styles, count);
        } catch (Throwable failure) {
            disabled = true;
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER disabled=1 stage=validate reason=" + failure);
            return false;
        }
        if (candidates.isEmpty()) {
            reportFrame();
            return false;
        }

        try {
            // Shader and GL objects must be created on the render thread. The first matching frame
            // therefore keeps every original command and inserts a harmless prewarm operation.
            if (!ChunkBatchRenderer.isReady()) {
                replayWithPrewarm(draws, styles, count, candidates.get(0).start);
                prewarms++;
                reportFrame();
                return true;
            }

            replayCompiled(draws, styles, count, candidates);
            reportFrame();
            return true;
        } catch (Throwable failure) {
            disabled = true;
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER disabled=1 stage=emit reason=" + failure);
            // Some commands may already be in RingBuffer. Skipping the original avoids duplicates;
            // the hook remains disabled on every later frame.
            return true;
        }
    }

    private static List<Block> findBlocks(TextureDraw[] draws, Style[] styles, int count) {
        ArrayList<Block> result = new ArrayList<>(2);
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        for (int i = 0; i < limit; i++) {
            if (!isMarker(draws[i], 10)) continue;
            int end = i + 1;
            while (end < limit && !isMarker(draws[end], 11)) end++;
            if (end >= limit) return List.of();

            Block block = validateBlock(draws, styles, i, end);
            if (block != null) {
                result.add(block);
            } else {
                fallbacks++;
            }
            i = end;
        }
        return result;
    }

    private static Block validateBlock(TextureDraw[] draws, Style[] styles, int start, int end) {
        ArrayList<ChunkDraw> chunks = new ArrayList<>(128);
        Style commandStyle = firstStyle(styles, start, end);
        if (commandStyle == null) return null;

        for (int i = start + 1; i < end; i++) {
            TextureDraw draw = draws[i];
            if (draw == null || draw.type != Type.glDraw) continue;

            Style style = styles[i];
            if (style == null || !"zombie.core.Styles.TransparentStyle".equals(style.getClass().getName())) {
                return null;
            }
            if (!supportedChunkDraw(draw)) return null;
            chunks.add(new ChunkDraw(draw));
        }

        return chunks.size() >= 2 ? new Block(start, end, commandStyle, chunks) : null;
    }

    private static boolean supportedChunkDraw(TextureDraw draw) {
        return draw.tex != null && draw.tex1 != null && draw.tex2 == null
                && draw.getColor(0) == -1 && draw.getColor(1) == -1
                && draw.getColor(2) == -1 && draw.getColor(3) == -1
                && finite(draw.x0) && finite(draw.y0) && finite(draw.x1) && finite(draw.y1)
                && finite(draw.x2) && finite(draw.y2) && finite(draw.x3) && finite(draw.y3)
                && finite(draw.u0) && finite(draw.v0) && finite(draw.u1) && finite(draw.v1)
                && finite(draw.u2) && finite(draw.v2) && finite(draw.u3) && finite(draw.v3)
                && finite(draw.chunkDepth);
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }

    private static Style firstStyle(Style[] styles, int start, int end) {
        for (int i = start; i <= end; i++) {
            if (styles[i] != null) return styles[i];
        }
        return null;
    }

    private static boolean isMarker(TextureDraw draw, int marker) {
        return draw != null && draw.type == Type.glBuffer && draw.a == marker;
    }

    private static void replayWithPrewarm(TextureDraw[] draws, Style[] styles, int count, int before) {
        TextureDraw previous = null;
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        for (int i = 0; i < limit; i++) {
            if (i == before) {
                TextureDraw warmup = new TextureDraw();
                warmup.type = Type.DrawModel;
                warmup.drawer = new ChunkBatchRenderer.PrewarmDrawer();
                SpriteRenderer.ringBuffer.add(warmup, previous, nonNullStyle(styles, i, limit));
                previous = warmup;
            }
            SpriteRenderer.ringBuffer.add(draws[i], previous, styles[i]);
            previous = draws[i];
        }
    }

    private static void replayCompiled(TextureDraw[] draws, Style[] styles, int count, List<Block> candidates) {
        TextureDraw previous = null;
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        int blockIndex = 0;
        Block block = candidates.get(0);

        for (int i = 0; i < limit; i++) {
            if (block != null && i == block.start) {
                TextureDraw begin = draws[i];
                SpriteRenderer.ringBuffer.add(begin, previous, styles[i]);
                previous = begin;

                TextureDraw compiled = new TextureDraw();
                compiled.type = Type.DrawModel;
                compiled.drawer = new ChunkBatchRenderer(block.chunks);
                SpriteRenderer.ringBuffer.add(compiled, previous, block.style);
                previous = compiled;

                TextureDraw finish = draws[block.end];
                SpriteRenderer.ringBuffer.add(finish, previous, styles[block.end]);
                previous = finish;

                int emitted = (block.chunks.size() + ChunkBatchRenderer.batchSize() - 1)
                        / ChunkBatchRenderer.batchSize();
                blocks++;
                sourceDraws += block.chunks.size();
                backendDraws += emitted;
                maxBatch = Math.max(maxBatch, Math.min(block.chunks.size(), ChunkBatchRenderer.batchSize()));
                i = block.end;
                blockIndex++;
                block = blockIndex < candidates.size() ? candidates.get(blockIndex) : null;
                continue;
            }
            SpriteRenderer.ringBuffer.add(draws[i], previous, styles[i]);
            previous = draws[i];
        }
    }

    private static Style nonNullStyle(Style[] styles, int at, int limit) {
        if (styles[at] != null) return styles[at];
        for (int i = at + 1; i < limit; i++) if (styles[i] != null) return styles[i];
        for (int i = at - 1; i >= 0; i--) if (styles[i] != null) return styles[i];
        return null;
    }

    static void rendererFailed(Throwable failure) {
        disabled = true;
        System.out.println("ZOMDROID_PZ_WORLD_COMPILER disabled=1 stage=renderer reason=" + failure);
    }

    private static void reportFrame() {
        frames++;
        if (!CENSUS || frames % REPORT_EVERY != 0) return;
        long eliminated = sourceDraws - backendDraws;
        System.out.println("ZOMDROID_PZ_WORLD_COMPILER frames=" + frames
                + " blocks=" + blocks
                + " source_draws=" + sourceDraws
                + " backend_draws=" + backendDraws
                + " eliminated=" + eliminated
                + " fallback=" + fallbacks
                + " prewarm=" + prewarms
                + " max_batch=" + maxBatch
                + " ready=" + (ChunkBatchRenderer.isReady() ? 1 : 0));
    }

    static final class ChunkDraw {
        final zombie.core.textures.Texture color;
        final zombie.core.textures.Texture depth;
        final float[] data = new float[18];

        ChunkDraw(TextureDraw source) {
            color = source.tex;
            depth = source.tex1;
            data[0] = source.x0;
            data[1] = source.y0;
            data[2] = source.x1;
            data[3] = source.y1;
            data[4] = source.x2;
            data[5] = source.y2;
            data[6] = source.x3;
            data[7] = source.y3;
            data[8] = source.flipped ? source.u1 : source.u0;
            data[9] = source.v0;
            data[10] = source.flipped ? source.u0 : source.u1;
            data[11] = source.v1;
            data[12] = source.flipped ? source.u3 : source.u2;
            data[13] = source.v2;
            data[14] = source.flipped ? source.u2 : source.u3;
            data[15] = source.v3;
            data[16] = source.chunkDepth;
        }
    }

    private record Block(int start, int end, Style style, List<ChunkDraw> chunks) {
    }
}
