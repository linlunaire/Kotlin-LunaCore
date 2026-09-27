# Kotlin LunaCore architecture

LunaCore is a Mod foundation, not a transit framework or a port of a Bukkit plugin container.

## Dependency direction

```text
ANTE ──┐
       ├── MTR ── Kotlin LunaCore ── Kotlin stdlib
JCM ───┘             ▲
                     │
               future unrelated mods
```

Addons using LunaCore directly also declare it explicitly. MTR remains responsible for trains, stations, tickets, network formats and saves. ANTE owns custom models, scripts and rail tools; JCM owns its facilities and displays. None of these domains enter LunaCore.

## Small modules, explicit ownership

- `core` is Kotlin/JVM only. No game classes, loader API, scripting engine, classpath scanning, service locator or reflection framework. A build check enforces its dependency boundary.
- `fabric` and `neoforge` assemble the same core and stdlib using the loader's native library selection. They do not install global tick handlers or create executors.
- `FrameGeometryCache` owns identity-based retention and disposal. The caller supplies resource creation and measurement; it alone touches GPU state.
- `FrameMembership` owns frame-to-frame additions/removals, retaining stable entries instead of allocating difference sets each frame.
- `BoundedTaskDispatcher` owns nonblocking admission and completion publication. The caller supplies its executor, admission limit, validity check and upload/disposal operations. Completed-but-not-published work still consumes capacity.

Core policies do not make game APIs thread-safe. Capture safe worker inputs on the owning game thread; publish Minecraft/GPU mutations on that thread, and close policies when their world/resource owner ends. Do not store player, world or chunk references in process-global registries.

The cache's memory budget is deliberately soft for the active frame. It cannot promise a fixed heap cap for arbitrary visible scenes. Dispatcher admission bounds retained jobs, not the size of each result. Callers remain responsible for per-resource limits.

## Runtime and compatibility

There is one shared Kotlin runtime owner for our mods. The unchanged JetBrains stdlib is nested with its canonical coordinates, never flattened or relocated. FLK/KFF remain necessary for third-party mods using their custom adapters or additional APIs; LunaCore does not claim their mod IDs.

The public name and repository changed from Transit Core to Kotlin LunaCore in 0.2.0. The `transit_core` mod ID, `io.github.linlunaire.transitcore` JVM packages and `transit-core` plain-library coordinate are intentionally retained. A brand change is not a reason to break existing binary consumers. Old/new named mod JARs must not be installed together.

Java functional interfaces form the current policy API. Changes to null handling, callback order, disposal, rejection or failure behavior require a compatibility test. Existing mutable game state is not cached behind a long-lived index without a real invalidation owner.

## Growth rule

Add a shared capability when real consumers need it and its ownership can be tested independently. A future reforge mod may need configuration validation, weighted selection and platform item/effect adapters; these are not implemented speculatively now. Keep source-derived GPL Reforges work in a separately licensed addon project, not in this MIT foundation.

Performance claims are limited to measured behavior: allocation fixtures, operation counts, bounded admission and cleanup tests. Neither Kotlin syntax nor a successful headless build establishes an FPS/TPS improvement or an 80-player server capacity guarantee.
