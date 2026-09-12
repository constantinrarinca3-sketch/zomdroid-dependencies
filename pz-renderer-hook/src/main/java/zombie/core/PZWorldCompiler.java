package zombie.core;

import java.util.ArrayList;
import java.util.List;
import zombie.core.Styles.Style;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureDraw.Type;

/** Compiles only the normal cached-chunk interval in the PZ WORLD submission. */
public final class PZWorldCompiler {
    private static final boolean CENSUS = "1".equals(System.getenv("MOBILEGLUES_PZ_CENSUS"));
    private static final int REPORT_EVERY = 300;

    private static volatile boolean disabled;
    private static long frames;
    private static long normalIntervals;
    private static long compiledBlocks;
    private static long sourceDraws;
    private static long backendDraws;
    private static long fallbacks;
    private static long prewarms;
    private static long rejectNoDraw;
    private static long rejectCommand;
    private static long rejectDraw;
    private static long rejectStyle;
    private static int maxBatch;

    private PZWorldCompiler() {}

    public static boolean compileWorldState(SpriteRenderState state) {
        if (disabled || state == null || state.sprite == null || state.style == null || state.numSprites <= 0) {
            return false;
        }
        TextureDraw[] draws = state.sprite;
        Style[] styles = state.style;
        int count = state.numSprites;

        final List<Block> candidates;
        try {
            candidates = findBlocks(draws, styles, count);
        } catch (Throwable failure) {
            disabled = true;
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V2 disabled=1 stage=validate reason=" + failure);
            return false;
        }
        if (candidates.isEmpty()) {
            reportFrame();
            return false;
        }

        try {
            SpriteRenderer.ringBuffer.begin();
            if (!ChunkBatchRenderer.isReady()) {
                replayWithPrewarm(draws, styles, count, candidates.get(0).start);
                prewarms++;
            } else {
                replayCompiled(draws, styles, count, candidates);
            }
            SpriteRenderer.ringBuffer.render();
            reportFrame();
            return true;
        } catch (Throwable failure) {
            disabled = true;
            System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V2 disabled=1 stage=emit reason=" + failure);
            return true;
        }
    }

    private static List<Block> findBlocks(TextureDraw[] draws, Style[] styles, int count) {
        ArrayList<Block> result = new ArrayList<>(2);
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        for (int i = 0; i < limit; i++) {
            TextureDraw draw = draws[i];
            if (draw == null || draw.type != Type.glDoStartFrameNoZoom) continue;
            normalIntervals++;
            int end = i + 1;
            while (end < limit && (draws[end] == null || draws[end].type != Type.glDoEndFrame)) end++;
            if (end >= limit) {
                fallbacks++;
                rejectCommand++;
                break;
            }
            Validation validation = validateBlock(draws, styles, i, end, limit);
            if (validation.block != null) {
                result.add(validation.block);
            } else {
                fallbacks++;
                countReject(validation.reject);
            }
            i = end;
        }
        return result;
    }

    private static Validation validateBlock(TextureDraw[] draws, Style[] styles, int start, int end, int limit) {
        if (start <= 0 || end + 1 >= limit
                || draws[start - 1] == null || draws[start - 1].type != Type.glDoEndFrame
                || draws[end + 1] == null || draws[end + 1].type != Type.glDoStartFrame) {
            return Validation.reject(Reject.COMMAND);
        }
        if (!transparent(styles[start]) || !transparent(styles[end]) || !transparent(styles[end + 1])) {
            return Validation.reject(Reject.STYLE);
        }

        ArrayList<ChunkDraw> chunks = new ArrayList<>(128);
        TextureDraw activeShader = null;
        boolean sawDraw = false;
        int shaderId = -1;

        for (int i = start + 1; i < end; i++) {
            TextureDraw draw = draws[i];
            if (draw == null || draw.type == null) return Validation.reject(Reject.COMMAND);
            if (!transparent(styles[i])) return Validation.reject(Reject.STYLE);

            Type type = draw.type;
            if (type == Type.StartShader) {
                if (draw.a == 0) {
                    if (activeShader == null || !sawDraw) return Validation.reject(Reject.COMMAND);
                    activeShader = null;
                    sawDraw = false;
                } else {
                    if (activeShader != null) return Validation.reject(Reject.COMMAND);
                    if (draw.tex1 == null || !finite(draw.chunkDepth)) return Validation.reject(Reject.DRAW);
                    if (shaderId == -1) shaderId = draw.a;
                    else if (shaderId != draw.a) return Validation.reject(Reject.COMMAND);
                    activeShader = draw;
                    sawDraw = false;
                }
                continue;
            }

            if (type == Type.glDraw) {
                if (activeShader == null || sawDraw || !supportedChunkDraw(draw)) {
                    return Validation.reject(Reject.DRAW);
                }
                chunks.add(new ChunkDraw(draw, activeShader));
                sawDraw = true;
                continue;
            }

            if (type == Type.glBlendFuncSeparate) {
                if (activeShader == null || sawDraw
                        || draw.a != 1 || draw.b != 771 || draw.c != 773 || draw.d != 1) {
                    return Validation.reject(Reject.COMMAND);
                }
                continue;
            }
            if (type == Type.glDepthFunc) {
                if (activeShader == null || sawDraw || draw.a != 515) return Validation.reject(Reject.COMMAND);
                continue;
            }
            if (type == Type.glDepthMask) {
                if (activeShader == null || draw.a != 1) return Validation.reject(Reject.COMMAND);
                continue;
            }
            if (type == Type.glEnable) {
                if (activeShader == null || draw.a != 2929) return Validation.reject(Reject.COMMAND);
                continue;
            }
            return Validation.reject(Reject.COMMAND);
        }

        if (activeShader != null) return Validation.reject(Reject.COMMAND);
        if (chunks.size() < 2) return Validation.reject(Reject.NO_DRAW);
        return Validation.accept(new Block(start, end, styles[start], chunks));
    }

    private static boolean supportedChunkDraw(TextureDraw draw) {
        return draw.tex != null && draw.tex1 == null && draw.tex2 == null
                && !draw.flipped && draw.useAttribArray == -1
                && draw.getColor(0) == -1 && draw.getColor(1) == -1
                && draw.getColor(2) == -1 && draw.getColor(3) == -1
                && finite(draw.x0) && finite(draw.y0) && finite(draw.x1) && finite(draw.y1)
                && finite(draw.x2) && finite(draw.y2) && finite(draw.x3) && finite(draw.y3)
                && finite(draw.u0) && finite(draw.v0) && finite(draw.u1) && finite(draw.v1)
                && finite(draw.u2) && finite(draw.v2) && finite(draw.u3) && finite(draw.v3);
    }

    private static boolean finite(float value) { return Float.isFinite(value); }

    private static boolean transparent(Style style) {
        return style != null && "zombie.core.Styles.TransparentStyle".equals(style.getClass().getName());
    }

    private static void countReject(Reject reject) {
        if (reject == Reject.NO_DRAW) rejectNoDraw++;
        else if (reject == Reject.DRAW) rejectDraw++;
        else if (reject == Reject.STYLE) rejectStyle++;
        else rejectCommand++;
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

                int emitted = (block.chunks.size() + ChunkBatchRenderer.batchSize() - 1) / ChunkBatchRenderer.batchSize();
                compiledBlocks++;
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
        System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V2 disabled=1 stage=renderer reason=" + failure);
    }

    private static void reportFrame() {
        frames++;
        if (!CENSUS || frames % REPORT_EVERY != 0) return;
        long eliminated = sourceDraws - backendDraws;
        System.out.println("ZOMDROID_PZ_WORLD_COMPILER_V2 frames=" + frames
                + " normal_intervals=" + normalIntervals
                + " compiled_blocks=" + compiledBlocks
                + " source_draws=" + sourceDraws
                + " backend_draws=" + backendDraws
                + " eliminated=" + eliminated
                + " fallback=" + fallbacks
                + " prewarm=" + prewarms
                + " max_batch=" + maxBatch
                + " ready=" + (ChunkBatchRenderer.isReady() ? 1 : 0)
                + " reject_no_draw=" + rejectNoDraw
                + " reject_command=" + rejectCommand
                + " reject_draw=" + rejectDraw
                + " reject_style=" + rejectStyle);
    }

    static final class ChunkDraw {
        final Texture color;
        final Texture depth;
        final float[] data = new float[18];

        ChunkDraw(TextureDraw source, TextureDraw shader) {
            color = source.tex;
            depth = shader.tex1;
            data[0] = source.x0; data[1] = source.y0;
            data[2] = source.x1; data[3] = source.y1;
            data[4] = source.x2; data[5] = source.y2;
            data[6] = source.x3; data[7] = source.y3;
            data[8] = source.flipped ? source.u1 : source.u0; data[9] = source.v0;
            data[10] = source.flipped ? source.u0 : source.u1; data[11] = source.v1;
            data[12] = source.flipped ? source.u3 : source.u2; data[13] = source.v2;
            data[14] = source.flipped ? source.u2 : source.u3; data[15] = source.v3;
            data[16] = shader.chunkDepth;
        }
    }

    private record Block(int start, int end, Style style, List<ChunkDraw> chunks) {}
    private record Validation(Block block, Reject reject) {
        static Validation accept(Block block) { return new Validation(block, null); }
        static Validation reject(Reject reject) { return new Validation(null, reject); }
    }
    private enum Reject { NO_DRAW, COMMAND, DRAW, STYLE }
}
