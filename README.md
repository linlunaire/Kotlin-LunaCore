# Transit Core

A small, general-purpose Kotlin foundation for **Minecraft 26.2** mods on **Fabric and NeoForge**. Minecraft Transit Railway and ANTE are its first consumers, not its domain model. It centralizes resource lifetime and bounded background-work policies without taking ownership of game state, rendering, or save formats.

## Install

Install the Transit Core JAR matching your loader on both client and server, together with the loader's Kotlin runtime:

| Loader | Kotlin runtime |
| --- | --- |
| Fabric Loader 0.19.3+ | [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) 1.13.14+kotlin.2.4.20 or compatible |
| NeoForge 26.2.0.75+ | [Kotlin for Forge](https://modrinth.com/mod/kotlin-for-forge) 6.3.0 (NeoForge) |

Transit Core does not bundle a second Kotlin standard library or require Architectury API. It is a library: installing it alone adds no gameplay features.

## Modules

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

Release mods are written to `build/release/`. The plain JVM library is `core/build/libs/transit-core-0.1.0.jar`; it is for compilation and isolated tests, not installation beside the loader mod. Kotlin is compiled with 2.4.20 against standard library 2.4.0, the lower shared runtime floor.

Checks cover Java interoperability, identity and equality contracts, cache eviction and complete disposal, callback retry, checked exceptions and `Error` cleanup, allocation-free stable paths, 1,000 bounded jobs, worker limits, and close-during-build races. Passing these checks does not establish in-game FPS or multiplayer capacity.

## License

[MIT](LICENSE) for the runtime library. Extracted MTR and ANTE policies retain their original copyright notices. Material under `legacy/` retains its own license and provenance notices; the root license does not replace them. This project is independently maintained and is not an official upstream MTR release.
