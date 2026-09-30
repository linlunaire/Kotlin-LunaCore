> License update (0.3.0): the current LunaCore distribution uses LGPL-3.0-or-later. Historical MIT permissions and notices are retained; Kotlin remains Apache-2.0. See [NOTICE](../../NOTICE.md). The earlier investigation below records the licensing context at the time.

# Kotlin runtime packaging and ecosystem scope

Investigated 2026-09-27. This records source/build evidence, not an in-game compatibility claim.

## Decision

Ship one Transit-Core artifact per loader with the same Minecraft-independent Kotlin API. Nest the original `org.jetbrains.kotlin:kotlin-stdlib:2.4.0` under loader-supported Jar-in-Jar metadata; do not relocate or flatten `kotlin.*` into the outer mod. Our three consumers depend on Transit-Core, not on FLK/KFF. A conventional Java-compatible entrypoint is sufficient; no custom language loader is needed for ordinary Kotlin classes. Kotlin `object` entrypoints and the FLK `kotlin` adapter remain different integration mechanisms and must not be silently substituted. [Fabric entrypoint/adapter metadata](https://docs.fabricmc.net/develop/loader/fabric-mod-json), [KFF language-loader configuration](https://github.com/thedarkcolour/KotlinForForge/tree/9cd48346a4a6e30c0c75df049d4f6fa1b66cb975).

This replaces the runtime prerequisite **for our mods**, not all third-party mods. Mods explicitly requiring the FLK adapter or `kotlinforforge` loader still need those projects. Do not advertise fake `provides` aliases for them.

## Existing alternatives

- FLK targets Fabric and packages stdlib, reflection, and multiple kotlinx libraries. KFF provides NeoForge integration and a comparable runtime bundle. Their current Maven artifacts use nested runtime libraries. [FLK packaging](https://github.com/FabricMC/fabric-language-kotlin/blob/858a0d35937406a19e01c6bab3537550b4f972d9/build.gradle), [KFF packaging](https://github.com/thedarkcolour/KotlinForForge/blob/9cd48346a4a6e30c0c75df049d4f6fa1b66cb975/build.gradle.kts).
- The broader assertion that no Kotlin runtime project covers both loaders is false: Katton has Fabric, NeoForge, and Paper targets, including Minecraft 26.2. Its own builds include Kotlin compiler/scripting/runtime libraries on both mod loaders. This is a much broader scripting product, not evidence that our small runtime should bundle a compiler. [Katton overview](https://github.com/Alumopper/Katton/tree/9acd761d18be1e6277aca8d932efa63341ba1764), [Fabric packaging](https://github.com/Alumopper/Katton/blob/9acd761d18be1e6277aca8d932efa63341ba1764/fabric/build.gradle), [NeoForge packaging](https://github.com/Alumopper/Katton/blob/9acd761d18be1e6277aca8d932efa63341ba1764/neoforge/build.gradle).

Our useful distinction is a small dependency surface, bounded work admission, explicit resource ownership, and measurable allocation behavior—not a claim that Kotlin itself makes Minecraft faster.

## Loader packaging and coexistence

Fabric Loom's `include` is non-transitive and makes a synthetic mod descriptor for an ordinary library. The parent declares nested JARs in `fabric.mod.json`; normal dependency resolution can choose one version of the same generated library identity. Preserve the standard `org_jetbrains_kotlin_kotlin-stdlib` identity. [Loom include documentation](https://docs.fabricmc.net/develop/loom/options), [Fabric metadata](https://docs.fabricmc.net/develop/loader/fabric-mod-json).

NeoForge selects nested libraries by Maven group/artifact and compatible version ranges. Keep the original `org.jetbrains.kotlin:kotlin-stdlib` coordinates; renaming the coordinate or publishing an independently shaded `kotlin.*` copy defeats that selection mechanism. Module names must also be unique. [NeoForge Jar-in-Jar documentation](https://docs.neoforged.net/toolchain/docs/plugins/mdg/#jar-in-jar).

The actual local Architectury Loom 1.17.491 sources expose `IncludeConfigurations.nestJars(project, jarTask, configuration)` for ordinary JAR tasks, including our `shadowJar`. `NestableJarGenerationTask` creates the loader-specific library metadata, and `JarNester` writes the final parent metadata. Its NeoForge default range is `[resolvedVersion,)`, not a hard exact version. This implementation detail was checked in the installed sources JAR; recheck when upgrading Loom. [Corresponding upstream nesting source](https://github.com/architectury/architectury-loom/blob/026ce8305cb7eabc85d918d9be99d6ba95a0900b/src/main/java/net/fabricmc/loom/configuration/IncludeConfigurations.java).

Locally inspected external artifacts: FLK `1.13.14+kotlin.2.4.20` nests stdlib `2.4.20` with the standard Fabric ID; KFF NeoForge `6.3.0` nests stdlib `2.4.0` under its original Maven coordinates with `[2.4.0,)`. These are useful coexistence fixtures, not a guarantee covering every future release.

Keep stdlib as the initial runtime surface. Full JVM reflection is a separate dependency, and coroutines/serialization should be added only when a consumer actually needs them. A scan of the current Core, MTR, and ANTE Kotlin production trees found no `kotlinx.*` or `kotlin.reflect.full/jvm` imports at investigation time. [Kotlin reflection dependency](https://kotlinlang.org/docs/reflection.html).

## Distribution notices

Kotlin stdlib's published POM identifies Apache-2.0. The inspected 2.4.0 JAR contains no `LICENSE`/`NOTICE` entry, so merely copying the JAR does not include a license text. Ship `licenses/Kotlin-LICENSE.txt` and `licenses/Kotlin-NOTICE.txt` with each Core artifact; these are exact upstream texts from Kotlin tag `v2.4.0` (commit `add726ca8c82922b6ab4cb2a27ae738d6a780817`). The upstream NOTICE labels itself a compiler-distribution notice; retaining it is attribution, not an assertion that Transit-Core embeds the compiler. Preserve any further notices if later adding other libraries. [Published stdlib POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.pom), [Kotlin license](https://github.com/JetBrains/kotlin/blob/add726ca8c82922b6ab4cb2a27ae738d6a780817/license/LICENSE.txt), [Kotlin NOTICE](https://github.com/JetBrains/kotlin/blob/add726ca8c82922b6ab4cb2a27ae738d6a780817/license/NOTICE.txt).

Apache-2.0 section 4 requires a license copy, retained relevant notices, and identification of modified files. Library repackaging metadata should be described in our third-party notice; library bytecode should remain unmodified. Transit-Core's original code can remain MIT; that does not relicense the embedded Kotlin runtime. This is an engineering compliance checklist, not legal advice. [Apache-2.0 terms](https://www.apache.org/licenses/LICENSE-2.0).

## Future Reforges port

Reforges currently contains GPL-3.0 licensing, not MIT. A source-derived port should remain a separate GPL-compatible project with its upstream attribution, source/build availability, and modification history. Do not copy its implementation into the MIT Core foundation. Review dependencies and assets separately before distribution. [Reforges license at reviewed commit](https://github.com/Auxilor/Reforges/blob/dc89a6d60263b8f6cebc9fc45fb770174b465ef6/LICENSE.md).

The project is not a loader-neutral mod: its manifest requires `eco` and optionally integrates `libreforge`; builds use Paper API, eco, ecomponent, optional Talismans API, and libreforge Gradle packaging. Bukkit item, event, permission, inventory, persistence, and effect integration therefore need real mod adapters or replacements. [Plugin manifest](https://github.com/Auxilor/Reforges/blob/dc89a6d60263b8f6cebc9fc45fb770174b465ef6/eco-core/core-plugin/src/main/resources/plugin.yml), [Root build](https://github.com/Auxilor/Reforges/blob/dc89a6d60263b8f6cebc9fc45fb770174b465ef6/build.gradle.kts), [Plugin build](https://github.com/Auxilor/Reforges/blob/dc89a6d60263b8f6cebc9fc45fb770174b465ef6/eco-core/core-plugin/build.gradle.kts).

Potential future seams are immutable reforge definitions, weighted selection, validation, and explicit platform effect execution. They are design candidates only: add none to Core before a real port requires them. No Reforges code was copied or ported for this milestone.

## Verification boundary

Require final-artifact metadata checks, isolated runtime loading from the embedded JAR, and actual Fabric resolver / NeoForge JarJar selection tests both alone and alongside FLK/KFF. Test identical and newer compatible runtime versions and preserve Java/Kotlin ABI checks. These headless checks cannot establish rendering, networking, server login, or full third-party modpack compatibility; that still needs clean client/server loader smoke tests and gameplay regression.

Implemented and passed `./gradlew checkRuntimeResolution` on JDK 25: Fabric Loader 0.19.3 resolves Core alone to stdlib 2.4.0 and Core with FLK to 2.4.20; NeoForge JarJarSelector 0.5.1 resolves Core alone and with KFF to one 2.4.0 copy, and Core plus KFF plus a compatible newer-runtime fixture to one 2.4.20 copy. Every case executes the packaged Core `FrameMembership` through an isolated classloader containing only the final Core JAR and the selected runtime. External language mods and loader test dependencies remain test-only configurations. The first test implementation hit signed-package isolation; keeping the fixture outside Fabric's signed package and reflecting only its package-private candidate factories fixed the harness without modifying the loader.
