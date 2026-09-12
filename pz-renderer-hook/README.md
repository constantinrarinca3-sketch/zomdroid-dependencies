# PZRendererHook V3 census (experimental)

V3 keeps the validated V2 normal cached-chunk compiler and adds a read-only census over the exact
Project Zomboid WORLD submission (`SpriteRenderer.buildStateDrawBuffer`). UI remains structurally
excluded because `buildStateUIDrawBuffer` is never hooked.

The census does **not** batch new tile families yet. It measures how much of the remaining WORLD
stream can be grouped conservatively without crossing PZ command/state barriers:

- plain textured `glDraw` runs: up to 16 distinct textures per estimated batch;
- color+depth `glDraw` runs: up to 8 distinct color textures and 8 distinct depth textures;
- `useAttribArray`, non-transparent styles, null textures, `tex2`, and every non-draw command break
  or exclude a candidate.

This is intentionally a measurement build. The next renderer backend should only target the family
that the census proves is large enough to matter.

Enable with:

```text
MOBILEGLUES_PZ_WORLD_COMPILER=1
MOBILEGLUES_PZ_CENSUS=1
-Dzomdroid.renderer=MOBILEGLUES_EXPERIMENTAL
-javaagent:/storage/emulated/0/Download/PZRendererHook-v3-census.jar
```

Expected startup markers:

```text
ZOMDROID_PZ_WORLD_COMPILER_V3 enabled=1 hook=installed version=3 census=world
ZOMDROID_PZ_WORLD_COMPILER_V3 hook=transformed class=zombie.core.SpriteRenderer
```

Every 300 WORLD submissions the new line is:

```text
ZOMDROID_PZ_WORLD_CENSUS_V3 ... plain_src=... plain_batches16=... plain_elim_est=... depth_src=... depth_batches8=... depth_elim_est=...
```

The existing V2 telemetry remains active in the same run, so the already validated cached-chunk
compiler can be compared against the larger candidate families.
