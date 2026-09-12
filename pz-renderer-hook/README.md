# PZRendererHook V4 batch + grammar (experimental)

V4 keeps the validated WORLD-only `SpriteRenderer.buildStateDrawBuffer` hook and the conservative
cached-chunk compiler. UI remains structurally excluded because `buildStateUIDrawBuffer` is never
hooked.

This build does two things in the same run:

1. **Real batching:** the proven cached-chunk renderer now sizes its instanced batch from
   `GL_MAX_TEXTURE_IMAGE_UNITS`, using two samplers per color/depth pair and capping at 16 pairs.
   A 16-unit device stays at batch 8; a 32-unit device can use batch 16.
2. **Passive command-grammar census:** every eligible WORLD `glDraw` with `tex1 != null`,
   `tex2 == null`, transparent style and no attrib array is classified by the exact immediate
   command before and after it, split by whether a non-zero `StartShader` is active. This census
   never changes the command stream and is emitted only when `MOBILEGLUES_PZ_CENSUS=1`.

The broader V3 WORLD census remains in the same JAR so one device run provides both aggregate draw
shape and the exact `tex1` command grammar needed for the next safe compiler extension.

Enable with:

```text
MOBILEGLUES_PZ_WORLD_COMPILER=1
MOBILEGLUES_PZ_CENSUS=1
-Dzomdroid.renderer=MOBILEGLUES_EXPERIMENTAL
-javaagent:/storage/emulated/0/Download/PZRendererHook-v4-batch-grammar.jar
```

Expected startup markers:

```text
ZOMDROID_PZ_WORLD_COMPILER_V4 enabled=1 hook=installed version=4 census=world+grammar
ZOMDROID_PZ_WORLD_COMPILER_V4 hook=transformed class=zombie.core.SpriteRenderer
ZOMDROID_PZ_WORLD_COMPILER_V4 renderer=ready batch=... tex_units=...
```

Every 300 WORLD submissions:

```text
ZOMDROID_PZ_WORLD_CENSUS_V4 ...
ZOMDROID_PZ_WORLD_GRAMMAR_V4 frames=... tex1_draws=... shader0=... shaderN=... top=PREV>NEXT@S0|SN:count,...
ZOMDROID_PZ_WORLD_COMPILER_V4 ... source_draws=... backend_draws=... eliminated=...
```

`top=` is a compact histogram over all observed eligible `tex1` draws. It records the exact immediate
neighbor command pair and whether shader 0 (`S0`) or a non-zero shader (`SN`) was active at the draw.
