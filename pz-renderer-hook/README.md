# PZRendererHook V5.3.1 texture fix

V5 keeps the WORLD-only `SpriteRenderer.buildStateDrawBuffer` hook, the validated V2 cached-chunk compiler,
and the V4 passive world/grammar census.  It adds an actual batching path for the dominant B42.20.3
`TileDepthShader` command family observed on device.

Safety rules:

- only `TransparentStyle` `StartShader(non-zero) -> glDraw` packets are considered;
- draw must have `tex != null`, `tex1 != null`, `tex2 == null`, `useAttribArray == -1`, finite geometry/UVs;
- shader must be PZ `TileDepthShader` (this includes `tileWithDepth` / `opaqueWithDepth`), never arbitrary shaders;
- the exact 4-uniform `zDepth, drawPixels, zDepthBlendZ, zDepthBlendToZ` setter chain is copied per draw;
- any non-redundant GL state command is a hard barrier;
- `glDepthMask` is absorbed only when it writes the already-known current value;
- backend is capped at 8 distinct color/depth texture pairs (16 samplers total), the proven device-safe limit;
- PZ's already-compiled tile-depth GLSL is captured at runtime and cloned.  If the source shape cannot be
  transformed and linked exactly, that shader is marked failed and those draws stay on the original path;
- after a compiled group, V5 replays the final original `StartShader` so following commands see the same PZ
  shader/uniform state.

The passive V5 grammar/census remains enabled with `MOBILEGLUES_PZ_CENSUS=1` in the same renderer build.

V5.2 accepts PZ's `NIL` placeholder for the optimized-out `drawPixels` uniform and transforms the
actual B42.20.3 mixed-version shader pair (`#version 330` vertex / `#version 120` fragment), including
its zero-bias `texture2D` call. Shader-family validation remains restricted to `TileDepthShader`.

V5.3 also accepts the semantically identical ESSL returned by MobileGlues/Adreno, where SPIRV-Cross
adds `layout(...)` and precision qualifiers to uniform declarations.

The stable build leaves the WORLD compiler opt-in. With Census disabled, it bypasses the passive
WORLD/grammar scans, cumulative optimization counters and periodic reports; only the compiler and
the two validated batching backends remain in the WORLD hot path.

V5.3.1 binds every cached-chunk color texture directly after selecting its texture unit. PZ's
`Texture.bind()` cache tracks only one global texture ID and could otherwise skip a required bind
when the same texture moved between sampler slots, leaving a stale texture visible or hiding a tile.

Enable:

```text
MOBILEGLUES_PZ_WORLD_COMPILER=1
MOBILEGLUES_PZ_CENSUS=1
-Dzomdroid.renderer=MOBILEGLUES_EXPERIMENTAL
-javaagent:/storage/emulated/0/Download/PZRendererHook-v5.3.1-texture-fix.jar
```

Expected startup:

```text
ZOMDROID_PZ_WORLD_COMPILER_V5 enabled=1 hook=installed version=5.3.1-texture-fix census=1
ZOMDROID_PZ_WORLD_COMPILER_V5 hook=transformed class=zombie.core.SpriteRenderer
```

When a tile-depth shader clone succeeds:

```text
ZOMDROID_PZ_DEPTH_BATCH_V5 renderer=ready shader=... pairs=8
```

Every 300 WORLD submissions the compiler line includes both the old cached-chunk telemetry and:

```text
depth_eligible=... depth_planned=... depth_groups=... depth_src=... depth_backend=...
depth_eliminated=... depth_max_batch=... depth_prewarm=... depth_shader_fail=... depth_redundant_mask=...
```

`depth_eliminated` is actual source-draw -> V5 backend-draw reduction for compiled V5 groups, not a census estimate.
