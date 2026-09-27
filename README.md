# Kotlin LunaCore

A next-generation Kotlin foundation for the Minecraft mod ecosystem.

**Fabric · NeoForge · Minecraft 26.2**

## Install

Install the Kotlin LunaCore **0.2.0** JAR matching your loader on both client and server:

| Loader | Artifact |
| --- | --- |
| Fabric Loader 0.19.3+ | `Kotlin-LunaCore-fabric-26.2-0.2.0.jar` |
| NeoForge 26.2.0.75+ | `Kotlin-LunaCore-neoforge-26.2-0.2.0.jar` |

No separate Kotlin language mod or Architectury API is required by Kotlin LunaCore. Kotlin stdlib 2.4.0 is nested using the loader's dependency mechanism with its original identity, not shaded or relocated. Consumers must not embed another copy. Existing FLK/KFF installations may remain for other mods: headless tests exercise both loaders' real dependency resolvers and select one compatible runtime. Full mod-pack/game validation is still required.

This is not an FLK/KFF impersonation: it does not advertise their mod IDs or provide their custom language adapters, reflection, coroutines or serialization modules. Mods explicitly requiring those APIs still need their declared dependencies. Our consumers use ordinary JVM entrypoints. Installing Kotlin LunaCore alone adds no gameplay features.

Formerly Transit Core. The `transit_core` mod ID, JVM packages and `transit-core` library coordinate remain stable for binary compatibility; install only one old/new-named version. ANTE and JCM remain MTR addons, not standalone mods. LunaCore itself has no MTR dependency.

## Modules

See [architecture and ownership](docs/architecture.md) for API boundaries, compatibility and growth rules.

- `core`: Minecraft-independent Kotlin policies with Java-friendly interfaces: identity-based frame geometry caching, incremental frame membership, and bounded task dispatch with owner-thread completion.
- `fabric`, `neoforge`: loader metadata and packaging; each release JAR embeds the same core implementation.
- `tests`: Java caller regression and allocation checks, plus isolated final-JAR verification.
- `legacy`: source-only historical compatibility material, excluded from the runtime and builds.

The cache budget is soft: geometry in the active frame remains pinned even when the working set exceeds the budget. The dispatcher bounds running work **and completed results waiting for upload**; worker count, queue admission limit and executor lifetime remain consumer decisions. These contracts avoid frame churn and unbounded staging memory without adding a general-purpose framework.

## Build

Use **JDK 25** and the included **Gradle 9.5.1** wrapper:

```sh
./gradlew build
```

Release mods are written to `build/release/`. The plain JVM library is `core/build/libs/transit-core-0.2.0.jar`; it is for compilation and isolated tests, not installation beside the loader mod. Kotlin is compiled with 2.4.20 against standard library 2.4.0.

Checks cover Java interoperability, identity and equality contracts, cache eviction and complete disposal, callback retry, checked exceptions and `Error` cleanup, allocation-free stable paths, 1,000 bounded jobs, worker limits, and close-during-build races. Passing these checks does not establish in-game FPS or multiplayer capacity.

Final-artifact checks run the policies using the nested runtime, compare every shipped stdlib resource against its upstream artifact, and reject duplicated runtime classes in consumers. See [runtime packaging and ecosystem boundaries](docs/research/kotlin-runtime-packaging.md) for coexistence tests, alternatives and the future Reforges port's separate licensing boundary.

## License

[MIT](LICENSE) for Kotlin LunaCore code; bundled Kotlin uses [Apache-2.0](licenses/Kotlin-LICENSE.txt) with [upstream attribution](licenses/Kotlin-NOTICE.txt). Extracted MTR and ANTE policies retain their original copyright notices. Material under `legacy/` retains its own license and provenance notices; the root license does not replace them. This project is independently maintained and is not an official upstream MTR release.
