# PZRendererHook (experimental)

A standalone Java agent for Project Zomboid 42.20.3. It intercepts only the WORLD draw-buffer
build and replaces the semantic `glBuffer(10)..glBuffer(11)` combined-chunk interval with an
instanced renderer. UI and mod code are untouched.

Each backend draw consumes up to eight existing color/depth texture pairs (16 texture units), so
the implementation does not duplicate PZ's chunk textures or create a second texture cache.

Enable with:

```text
-javaagent:/path/PZRendererHook.jar
MOBILEGLUES_PZ_WORLD_COMPILER=1
```

The hook additionally requires `-Dzomdroid.renderer=MOBILEGLUES_EXPERIMENTAL`. Set
`MOBILEGLUES_PZ_CENSUS=1` only for the short validation run; it prints one cumulative line every
300 WORLD frames.

Safety rules:

* no matching semantic chunk block: original renderer runs;
* unsupported chunk draw: original renderer runs;
* shader/GL initialization failure: the original frame still runs and the hook disables itself;
* UI never enters the compiler.
