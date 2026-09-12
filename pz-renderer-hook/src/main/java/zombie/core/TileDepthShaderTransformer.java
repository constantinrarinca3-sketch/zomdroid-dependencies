package zombie.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites PZ's compiled tile-depth GLSL so per-draw values become per-vertex batch data. */
public final class TileDepthShaderTransformer {
    private static final Pattern DIFFUSE_DECL = Pattern.compile("(?m)^\\s*uniform\\s+sampler2D\\s+DIFFUSE\\s*;\\s*$");
    private static final Pattern DEPTH_DECL = Pattern.compile("(?m)^\\s*uniform\\s+sampler2D\\s+DEPTH\\s*;\\s*$");
    private static final Pattern Z_DECL = Pattern.compile("(?m)^\\s*uniform\\s+float\\s+zDepth\\s*;\\s*$");
    private static final Pattern PIX_DECL = Pattern.compile("(?m)^\\s*uniform\\s+int\\s+drawPixels\\s*;\\s*$");
    private static final Pattern BLEND_Z_DECL = Pattern.compile("(?m)^\\s*uniform\\s+float\\s+zDepthBlendZ\\s*;\\s*$");
    private static final Pattern BLEND_TO_DECL = Pattern.compile("(?m)^\\s*uniform\\s+float\\s+zDepthBlendToZ\\s*;\\s*$");
    private static final Pattern DIFFUSE_TOKEN = Pattern.compile("\\bDIFFUSE\\b");
    private static final Pattern DEPTH_TOKEN = Pattern.compile("\\bDEPTH\\b");

    private TileDepthShaderTransformer() {}

    public static Sources transform(String vertex, String fragment, int pairs) {
        if (vertex == null || fragment == null || pairs < 1 || pairs > 8) return null;
        boolean legacyVertex = legacy(vertex);
        boolean legacyFragment = legacy(fragment);
        if (legacyVertex != legacyFragment || !vertex.contains("void main") || !fragment.contains("void main")) return null;

        Matcher diffuse = DIFFUSE_DECL.matcher(fragment);
        Matcher depth = DEPTH_DECL.matcher(fragment);
        Matcher z = Z_DECL.matcher(fragment);
        Matcher pix = PIX_DECL.matcher(fragment);
        Matcher bz = BLEND_Z_DECL.matcher(fragment);
        Matcher bto = BLEND_TO_DECL.matcher(fragment);
        if (!diffuse.find() || !depth.find() || !z.find() || !pix.find() || !bz.find() || !bto.find()) return null;

        String f = DIFFUSE_DECL.matcher(fragment).replaceAll("");
        f = DEPTH_DECL.matcher(f).replaceAll("");
        f = Z_DECL.matcher(f).replaceAll("");
        f = PIX_DECL.matcher(f).replaceAll("");
        f = BLEND_Z_DECL.matcher(f).replaceAll("");
        f = BLEND_TO_DECL.matcher(f).replaceAll("");
        f = rewriteSamplerCalls(f, "texture2D", "DIFFUSE", "zdSampleDiffuse");
        if (f == null) return null;
        f = rewriteSamplerCalls(f, "texture2D", "DEPTH", "zdSampleDepth");
        if (f == null) return null;
        f = rewriteSamplerCalls(f, "texture", "DIFFUSE", "zdSampleDiffuse");
        if (f == null) return null;
        f = rewriteSamplerCalls(f, "texture", "DEPTH", "zdSampleDepth");
        if (f == null) return null;
        if (DIFFUSE_TOKEN.matcher(f).find() || DEPTH_TOKEN.matcher(f).find()) return null;

        String vertexDecl = legacyVertex
                ? "attribute float zd_BatchSlot;\nattribute vec4 zd_Params;\nvarying float zd_BatchSlot_v;\nvarying vec4 zd_Params_v;\n"
                : "in float zd_BatchSlot;\nin vec4 zd_Params;\nout float zd_BatchSlot_v;\nout vec4 zd_Params_v;\n";
        String fragmentDecl = legacyFragment
                ? "varying float zd_BatchSlot_v;\nvarying vec4 zd_Params_v;\n"
                : "in float zd_BatchSlot_v;\nin vec4 zd_Params_v;\n";

        String vertexBody = Z_DECL.matcher(vertex).replaceAll("");
        vertexBody = PIX_DECL.matcher(vertexBody).replaceAll("");
        vertexBody = BLEND_Z_DECL.matcher(vertexBody).replaceAll("");
        vertexBody = BLEND_TO_DECL.matcher(vertexBody).replaceAll("");
        String v = injectAfterVersion(vertexBody, vertexDecl
                + "#define zDepth (zd_Params.x)\n"
                + "#define drawPixels (int(zd_Params.y + 0.5))\n"
                + "#define zDepthBlendZ (zd_Params.z)\n"
                + "#define zDepthBlendToZ (zd_Params.w)\n");
        v = injectMain(v, "zd_BatchSlot_v = zd_BatchSlot; zd_Params_v = zd_Params;");
        if (v == null) return null;

        String textureFunction = legacyFragment ? "texture2D" : "texture";
        StringBuilder prelude = new StringBuilder(2048);
        prelude.append(fragmentDecl)
                .append("#define zDepth (zd_Params_v.x)\n")
                .append("#define drawPixels (int(zd_Params_v.y + 0.5))\n")
                .append("#define zDepthBlendZ (zd_Params_v.z)\n")
                .append("#define zDepthBlendToZ (zd_Params_v.w)\n");
        for (int i = 0; i < pairs; i++) prelude.append("uniform sampler2D ZD_DIFFUSE").append(i).append(";\n");
        for (int i = 0; i < pairs; i++) prelude.append("uniform sampler2D ZD_DEPTH").append(i).append(";\n");
        prelude.append("vec4 zdSampleDiffuse(vec2 uv){ int s=int(zd_BatchSlot_v+0.5);\n");
        for (int i = 0; i < pairs - 1; i++) {
            prelude.append(" if(s==").append(i).append(") return ").append(textureFunction)
                    .append("(ZD_DIFFUSE").append(i).append(",uv);\n");
        }
        prelude.append(" return ").append(textureFunction).append("(ZD_DIFFUSE").append(pairs - 1).append(",uv); }\n");
        prelude.append("vec4 zdSampleDepth(vec2 uv){ int s=int(zd_BatchSlot_v+0.5);\n");
        for (int i = 0; i < pairs - 1; i++) {
            prelude.append(" if(s==").append(i).append(") return ").append(textureFunction)
                    .append("(ZD_DEPTH").append(i).append(",uv);\n");
        }
        prelude.append(" return ").append(textureFunction).append("(ZD_DEPTH").append(pairs - 1).append(",uv); }\n");
        f = injectAfterVersion(f, prelude.toString());
        return new Sources(v, f);
    }

    private static boolean legacy(String source) {
        return source.contains("#version 110") || source.contains("#version 120");
    }

    private static String injectAfterVersion(String source, String code) {
        int lineEnd = source.indexOf('\n');
        if (source.startsWith("#version") && lineEnd >= 0) {
            return source.substring(0, lineEnd + 1) + code + source.substring(lineEnd + 1);
        }
        return code + source;
    }

    private static String injectMain(String source, String code) {
        int main = source.indexOf("void main");
        if (main < 0) return null;
        int brace = source.indexOf('{', main);
        if (brace < 0) return null;
        return source.substring(0, brace + 1) + code + source.substring(brace + 1);
    }

    /** Rewrites only calls whose first argument is exactly the named sampler. */
    private static String rewriteSamplerCalls(String source, String function, String sampler, String helper) {
        StringBuilder out = new StringBuilder(source.length() + 128);
        int cursor = 0;
        while (true) {
            int pos = indexOfWord(source, function, cursor);
            if (pos < 0) {
                out.append(source, cursor, source.length());
                return out.toString();
            }
            int p = pos + function.length();
            while (p < source.length() && Character.isWhitespace(source.charAt(p))) p++;
            if (p >= source.length() || source.charAt(p) != '(') {
                out.append(source, cursor, p);
                cursor = p;
                continue;
            }
            int close = matchingParen(source, p);
            if (close < 0) return null;
            int comma = topLevelComma(source, p + 1, close);
            if (comma < 0) {
                out.append(source, cursor, close + 1);
                cursor = close + 1;
                continue;
            }
            String first = source.substring(p + 1, comma).trim();
            if (!sampler.equals(first)) {
                out.append(source, cursor, close + 1);
                cursor = close + 1;
                continue;
            }
            if (topLevelComma(source, comma + 1, close) >= 0) return null;
            String second = source.substring(comma + 1, close).trim();
            out.append(source, cursor, pos).append(helper).append('(').append(second).append(')');
            cursor = close + 1;
        }
    }

    private static int indexOfWord(String source, String word, int from) {
        int pos = from;
        while ((pos = source.indexOf(word, pos)) >= 0) {
            boolean left = pos == 0 || !Character.isJavaIdentifierPart(source.charAt(pos - 1));
            int end = pos + word.length();
            boolean right = end >= source.length() || !Character.isJavaIdentifierPart(source.charAt(end));
            if (left && right) return pos;
            pos = end;
        }
        return -1;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static int topLevelComma(String s, int from, int to) {
        int depth = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    public record Sources(String vertex, String fragment) {}
}
