import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.api.EnvType
import net.fabricmc.loader.impl.discovery.ModCandidateImpl
import net.fabricmc.loader.impl.discovery.ModResolver
import net.fabricmc.loader.impl.metadata.DependencyOverrides
import net.fabricmc.loader.impl.metadata.LoaderModMetadata
import net.fabricmc.loader.impl.metadata.ModMetadataParser
import net.fabricmc.loader.impl.metadata.VersionOverrides
import net.neoforged.jarjar.selection.JarSelector

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Optional
import java.util.function.Consumer
import java.util.zip.ZipInputStream

/** Uses the actual pinned loader resolvers; no Minecraft world or live instance is started. */
object KotlinRuntimeResolutionCheck {
    private const val STDLIB_ID = "org_jetbrains_kotlin_kotlin-stdlib"
    private const val UNIT = "kotlin/Unit.class"
    private lateinit var output: Path

    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val fabricArtifact = Path.of(args[0]).toAbsolutePath()
        val neoArtifact = Path.of(args[1]).toAbsolutePath()
        val flk = JarData.read(Path.of(args[2]))
        val kff = JarData.read(Path.of(args[3]))
        output = Path.of(args[4])
        Files.createDirectories(output)
        val newerStdlib = JarData.read(Path.of(args[5]))
        val baseline = args[6]
        val newer = args[7]

        checkFabric(fabricArtifact, emptyList(), baseline, "fabric-alone")
        checkFabric(fabricArtifact, listOf(flk), newer, "fabric-with-flk-newer")
        checkNeoForge(neoArtifact, emptyList(), baseline, "neoforge-alone")
        checkNeoForge(neoArtifact, listOf(kff), baseline, "neoforge-with-kff-same")
        checkNeoForge(neoArtifact, listOf(kff, jarJarHolder(newerStdlib, newer)), newer, "neoforge-with-kff-and-newer")
        println("PASS: 5 real-loader resolution cases select one stdlib and execute packaged Core through it")
    }

    private fun checkFabric(corePath: Path, extras: List<JarData>, expected: String, label: String) {
        val candidates = ArrayList<ModCandidateImpl>()
        val jars = IdentityHashMap<ModCandidateImpl, JarData>()
        collectFabric(JarData.read(corePath), true, candidates, jars)
        for (extra in extras) collectFabric(extra, true, candidates, jars)
        for ((id, version) in listOf("minecraft" to "26.2", "java" to "25", "fabricloader" to "0.19.3")) {
            val metadata = """{"schemaVersion":1,"id":"$id","version":"$version"}"""
            val parsed = parse(metadata.toByteArray(StandardCharsets.UTF_8), id)
            candidates.add(fabricCandidate(parsed, id, true, emptyList()))
        }
        val selected = ModResolver.resolve(candidates, EnvType.CLIENT, emptyMap())
        val runtimes = selected.filter { it.id == STDLIB_ID }
        require(runtimes.size == 1, "$label: duplicate/missing Fabric stdlib")
        require(runtimes.first().version.friendlyString == expected, "$label: wrong Fabric version")
        require(selected.count { jars[it]?.entries?.containsKey(UNIT) == true } == 1,
            "$label: more than one selected JAR contains kotlin.Unit")
        executeCore(corePath, jars.getValue(runtimes.first()), expected, label)
    }

    private fun collectFabric(jar: JarData, root: Boolean, candidates: MutableList<ModCandidateImpl>,
        jars: MutableMap<ModCandidateImpl, JarData>): ModCandidateImpl {
        val metadata = parse(jar.required("fabric.mod.json"), jar.name)
        val nested = ArrayList<ModCandidateImpl>()
        for (entry in metadata.jars) {
            nested.add(collectFabric(JarData.read(jar.name + "!/" + entry.file, jar.required(entry.file)), false, candidates, jars))
        }
        val candidate = fabricCandidate(metadata, jar.name, root, nested)
        val addParent = ModCandidateImpl::class.java.getDeclaredMethod("addParent", ModCandidateImpl::class.java)
        addParent.isAccessible = true
        for (child in nested) addParent.invoke(child, candidate)
        candidates.add(candidate)
        jars[candidate] = jar
        return candidate
    }

    // Keep test code outside Fabric's signed packages; reflect only over the pinned resolver's fixture construction API.
    private fun fabricCandidate(metadata: LoaderModMetadata, name: String, root: Boolean, nested: List<ModCandidateImpl>): ModCandidateImpl {
        if (root) {
            val create = ModCandidateImpl::class.java.getDeclaredMethod("createPlain", List::class.java, LoaderModMetadata::class.java,
                java.lang.Boolean.TYPE, Collection::class.java)
            create.isAccessible = true
            return create.invoke(null, listOf(Path.of(metadata.id + ".jar")), metadata, false, nested) as ModCandidateImpl
        }
        val create = ModCandidateImpl::class.java.getDeclaredMethod("createNested", String::class.java, java.lang.Long.TYPE,
            LoaderModMetadata::class.java, java.lang.Boolean.TYPE, Collection::class.java)
        create.isAccessible = true
        return create.invoke(null, name, 0L, metadata, false, nested) as ModCandidateImpl
    }

    private fun parse(bytes: ByteArray, name: String): LoaderModMetadata =
        ByteArrayInputStream(bytes).use {
            ModMetadataParser.parseMetadata(it, name, emptyList(), VersionOverrides(),
                DependencyOverrides(output.resolve("no-overrides")), false)
        }

    private fun checkNeoForge(corePath: Path, extras: List<JarData>, expected: String, label: String) {
        val roots = ArrayList<JarData>()
        roots.add(JarData.read(corePath))
        roots.addAll(extras)
        val selected = JarSelector.detectAndSelect(roots,
            { jar, entry -> Optional.ofNullable(jar.entries[entry]).map<java.io.InputStream> { ByteArrayInputStream(it) } },
            { jar, entry -> jar.nested(entry) }, { it.name },
            { IllegalStateException("$label: $it") })
        val runtimes = selected.filter { it.entries.containsKey(UNIT) }
        require(runtimes.size == 1, "$label: duplicate/missing NeoForge stdlib")
        executeCore(corePath, runtimes.first(), expected, label)
    }

    private fun jarJarHolder(runtime: JarData, version: String): JarData {
        val path = "META-INF/jars/kotlin-stdlib-$version.jar"
        val identity = JsonObject().apply {
            addProperty("group", "org.jetbrains.kotlin")
            addProperty("artifact", "kotlin-stdlib")
        }
        val versions = JsonObject().apply {
            addProperty("range", "[$version,)")
            addProperty("artifactVersion", version)
        }
        val entry = JsonObject().apply {
            add("identifier", identity)
            add("version", versions)
            addProperty("path", path)
        }
        val entries = JsonArray().apply { add(entry) }
        val metadata = JsonObject().apply { add("jars", entries) }
        return JarData("compatible-newer-runtime-fixture", null, mapOf(
            "META-INF/jarjar/metadata.json" to metadata.toString().toByteArray(StandardCharsets.UTF_8),
            path to requireNotNull(runtime.bytes)))
    }

    private fun executeCore(artifact: Path, runtime: JarData, expected: String, label: String) {
        val runtimePath = output.resolve("$label-stdlib.jar")
        Files.write(runtimePath, requireNotNull(runtime.bytes))
        URLClassLoader(arrayOf(artifact.toUri().toURL(), runtimePath.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()).use { loader ->
            val version = loader.loadClass("kotlin.KotlinVersion")
            require(version.getField("CURRENT").get(null).toString() == expected, "$label: selected runtime not executed")
            require(java.util.Collections.list(loader.getResources(UNIT)).size == 1, "$label: duplicate runtime classes")
            val membership = loader.loadClass("io.github.linlunaire.transitcore.collection.FrameMembership")
            val instance = membership.getConstructor().newInstance()
            val key = Any()
            val added = ArrayList<Any>()
            val removed = ArrayList<Any>()
            membership.getMethod("mark", Any::class.java).invoke(instance, key)
            val reconcile = membership.getMethod("reconcile", Consumer::class.java, Consumer::class.java)
            reconcile.invoke(instance, Consumer<Any>(added::add), Consumer<Any>(removed::add))
            reconcile.invoke(instance, Consumer<Any>(added::add), Consumer<Any>(removed::add))
            require(added == listOf(key) && removed == listOf(key), "$label: packaged Core execution failed")
        }
        println("PASS: $label selects and runs Kotlin $expected")
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private class JarData(val name: String, val bytes: ByteArray?, val entries: Map<String, ByteArray>) {
        private val nested = LinkedHashMap<String, JarData>()

        fun required(entry: String): ByteArray =
            entries[entry] ?: throw AssertionError("$name: missing nested resource $entry")

        fun nested(entry: String): Optional<JarData> {
            val content = entries[entry] ?: return Optional.empty()
            try {
                return Optional.of(nested.getOrPut(entry) { read("$name!/$entry", content) })
            } catch (error: IOException) {
                throw java.io.UncheckedIOException(error)
            }
        }

        companion object {
            fun read(path: Path): JarData = read(path.toString(), Files.readAllBytes(path))

            fun read(name: String, bytes: ByteArray): JarData {
                val entries = LinkedHashMap<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) entries[entry.name] = zip.readAllBytes()
                    }
                }
                return JarData(name, bytes, entries)
            }
        }
    }
}
