package zombie.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;
import zombie.core.Styles.Style;
import zombie.core.opengl.DepthUniformSnapshot;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureDraw.Type;

/**
 * Finds conservative WORLD runs that can be replaced by one tile-depth backend draw.
 * Only StartShader+glDraw packets are considered.  Intervening glDepthMask commands
 * may be absorbed only when they write the already-known current value.
 */
public final class DepthBatchPlanner {
    private static final int MAX_DRAWS = 256;

    private DepthBatchPlanner() {}

    public static Plan plan(TextureDraw[] draws, Style[] styles, int count, IntPredicate shaderAllowed) {
        if (draws == null || styles == null || shaderAllowed == null || count <= 0) {
            return new Plan(List.of(), 0, 0, 0);
        }
        int limit = Math.min(count, Math.min(draws.length, styles.length));
        ArrayList<Group> groups = new ArrayList<>();
        Builder current = null;
        boolean depthMaskKnown = false;
        boolean depthMask = true;
        long eligible = 0;
        long planned = 0;
        long redundantMasks = 0;

        for (int i = 0; i < limit; i++) {
            TextureDraw command = draws[i];
            Type type = command == null ? null : command.type;

            if (type == Type.glDepthMask) {
                boolean next = command.a != 0;
                boolean redundant = depthMaskKnown && next == depthMask;
                depthMask = next;
                depthMaskKnown = true;
                if (current != null) {
                    if (redundant) {
                        current.end = i;
                        redundantMasks++;
                    } else {
                        planned += flush(groups, current);
                        current = null;
                    }
                }
                continue;
            }

            if (isPacketStart(draws, styles, i, limit, shaderAllowed)) {
                TextureDraw start = command;
                TextureDraw draw = draws[i + 1];
                DepthUniformSnapshot params = DepthUniformSnapshot.capture(start.drawer);
                eligible++;
                if (current == null
                        || current.shaderId != start.a
                        || current.depthMaskKnown != depthMaskKnown
                        || (depthMaskKnown && current.depthMask != depthMask)
                        || current.draws.size() >= MAX_DRAWS) {
                    if (current != null) planned += flush(groups, current);
                    current = new Builder(i, start.a, depthMaskKnown, depthMask, styles[i + 1]);
                }
                current.add(i, i + 1, start, draw, params);
                i++;
                continue;
            }

            if (current != null) {
                planned += flush(groups, current);
                current = null;
            }
        }
        if (current != null) planned += flush(groups, current);
        return new Plan(Collections.unmodifiableList(groups), eligible, planned, redundantMasks);
    }

    private static long flush(ArrayList<Group> groups, Builder builder) {
        if (builder.draws.size() < 2) return 0;
        Group group = builder.build();
        groups.add(group);
        return group.sourceDraws();
    }

    private static boolean isPacketStart(TextureDraw[] draws, Style[] styles, int at, int limit, IntPredicate shaderAllowed) {
        if (at < 0 || at + 1 >= limit) return false;
        TextureDraw start = draws[at];
        TextureDraw draw = draws[at + 1];
        if (start == null || draw == null || start.type != Type.StartShader || start.a == 0 || draw.type != Type.glDraw) return false;
        if (!shaderAllowed.test(start.a) || !transparent(styles[at]) || !transparent(styles[at + 1])) return false;
        if (DepthUniformSnapshot.capture(start.drawer) == null) return false;
        return supportedDraw(draw);
    }

    private static boolean supportedDraw(TextureDraw d) {
        return d.tex != null && d.tex1 != null && d.tex2 == null && d.useAttribArray == -1
                && finite(d.x0) && finite(d.y0) && finite(d.x1) && finite(d.y1)
                && finite(d.x2) && finite(d.y2) && finite(d.x3) && finite(d.y3)
                && finite(d.u0) && finite(d.v0) && finite(d.u1) && finite(d.v1)
                && finite(d.u2) && finite(d.v2) && finite(d.u3) && finite(d.v3)
                && finite(d.tex1U0) && finite(d.tex1V0) && finite(d.tex1U1) && finite(d.tex1V1)
                && finite(d.tex1U2) && finite(d.tex1V2) && finite(d.tex1U3) && finite(d.tex1V3);
    }

    private static boolean finite(float v) { return Float.isFinite(v); }

    private static boolean transparent(Style style) {
        return style != null && "zombie.core.Styles.TransparentStyle".equals(style.getClass().getName());
    }

    public record DepthDraw(TextureDraw startShader, TextureDraw draw, DepthUniformSnapshot params) {}

    public record Group(int start, int end, int shaderId, boolean depthMaskKnown, boolean depthMask,
                        Style style, int lastStartShaderIndex, List<DepthDraw> draws) {
        public int sourceDraws() { return draws.size(); }
    }

    public record Plan(List<Group> groups, long eligibleDraws, long plannedDraws, long redundantDepthMasks) {}

    private static final class Builder {
        final int start;
        int end;
        final int shaderId;
        final boolean depthMaskKnown;
        final boolean depthMask;
        final Style style;
        int lastStartShaderIndex;
        final ArrayList<DepthDraw> draws = new ArrayList<>();

        Builder(int start, int shaderId, boolean depthMaskKnown, boolean depthMask, Style style) {
            this.start = start;
            this.end = start;
            this.shaderId = shaderId;
            this.depthMaskKnown = depthMaskKnown;
            this.depthMask = depthMask;
            this.style = style;
        }

        void add(int startIndex, int drawIndex, TextureDraw startShader, TextureDraw draw, DepthUniformSnapshot params) {
            lastStartShaderIndex = startIndex;
            end = drawIndex;
            draws.add(new DepthDraw(startShader, draw, params));
        }

        Group build() {
            return new Group(start, end, shaderId, depthMaskKnown, depthMask, style,
                    lastStartShaderIndex, Collections.unmodifiableList(new ArrayList<>(draws)));
        }
    }
}
