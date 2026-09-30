import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Files
import java.util.ArrayList
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import java.util.function.ToLongFunction
import java.util.zip.ZipFile

/** Exercise the shipped core with its nested runtime, never a development classpath. */
object TransitCoreArtifactCheck {
    private const val PREFIX = "io.github.linlunaire.transitcore."

    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        val artifact = Path.of(args[0]).toAbsolutePath()
        val fabric = args[1] == "fabric"
        val runtime = Files.createTempFile("transit-core-runtime-", ".jar")
        try {
            extractAndCheckMetadata(artifact, runtime, fabric, args[3], args[4])
            checkRuntimeBytes(runtime, Path.of(args[2]), fabric)
            checkIsolatedExecution(artifact, runtime, fabric)
            println("PASS: " + args[1] + " release runs JVM 25 Kotlin policies using only its nested unmodified stdlib, canonical loader identity and license notices (not a game launch)")
        } finally {
            Files.deleteIfExists(runtime)
        }
    }

    private fun extractAndCheckMetadata(artifact: Path, runtime: Path, fabric: Boolean, version: String, runtimeVersion: String) {
        ZipFile(artifact.toFile()).use { zip ->
            val metadataName = if (fabric) "fabric.mod.json" else "META-INF/neoforge.mods.toml"
            val metadataEntry = zip.getEntry(metadataName)
            require(metadataEntry != null, "Missing loader metadata")
            val metadata = zip.getInputStream(metadataEntry).use { it.readAllBytes().toString(Charsets.UTF_8) }
            require(metadata.contains("transit_core") && metadata.contains(version) && metadata.contains("26.2"), "Incorrect identity/version")
            require(!metadata.contains('$' + "{"), "Unexpanded dependency metadata")
            require(!metadata.contains("fabric-language-kotlin") && !metadata.contains("kotlinforforge"), "External language mod is still required")
            val nestedPath = "META-INF/jars/kotlin-stdlib-$runtimeVersion.jar"
            val nested = zip.getEntry(nestedPath)
            require(nested != null, "Missing nested Kotlin stdlib")
            require(zip.stream().filter { it.name.endsWith(".jar") }.count() == 1L, "Runtime must contain only the required stdlib")
            zip.getInputStream(nested).use { Files.copy(it, runtime, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            require(zip.getEntry("META-INF/transit-core/licenses/Kotlin-LICENSE.txt") != null, "Missing Kotlin license")
            require(zip.getEntry("META-INF/transit-core/licenses/Kotlin-NOTICE.txt") != null, "Missing Kotlin attribution")
            require(metadata.contains("LGPL-3.0-or-later"), "Incorrect project license")
            for (path in listOf("META-INF/transit-core/LICENSE", "META-INF/transit-core/COPYING", "META-INF/transit-core/NOTICE.md", "META-INF/transit-core/licenses/LunaCore-MIT.txt")) {
                require(zip.getEntry(path) != null, "Missing license/provenance: $path")
            }
            if (fabric) {
                require(metadata.contains(nestedPath), "Fabric cannot discover its runtime")
            } else {
                val jarjar = zip.getEntry("META-INF/jarjar/metadata.json")
                require(jarjar != null, "Missing NeoForge JarJar metadata")
                val descriptor = zip.getInputStream(jarjar).use { it.readAllBytes().toString(Charsets.UTF_8) }
                require(descriptor.contains("org.jetbrains.kotlin") && descriptor.contains("kotlin-stdlib") && descriptor.contains(nestedPath), "JarJar lost the canonical runtime identity")
                require(descriptor.contains("[$runtimeVersion,)"), "JarJar runtime floor is incorrect")
            }
            require(!metadata.contains("architectury"), "Library acquired an Architectury runtime requirement")
            if (!fabric) {
                require(metadata.contains("type = \"required\"") && metadata.contains("side = \"BOTH\""), "Kotlin prerequisite must be required on both sides")
                require(zip.getEntry("io/github/linlunaire/transitcore/neoforge/TransitCoreNeoForge.class") != null, "Missing NeoForge mod entrypoint")
            }
            for (entry in zip.stream().toList()) {
                val name = entry.name
                require(!(name.startsWith("kotlin/") && name.endsWith(".class")), "Bundled Kotlin stdlib class: $name")
                require(!name.startsWith("legacy/") && !name.startsWith("mtr/") && !name.startsWith("cn/zbx1425/"), "Old namespace/material entered runtime: $name")
                require(!name.endsWith("Check.class") && !name.contains("/interop/"), "Verification code entered runtime: $name")
            }
            val classes = mutableListOf("collection.FrameGeometryCache", "collection.FrameMembership", "concurrent.BoundedTaskDispatcher")
            if (!fabric) classes.add("neoforge.TransitCoreNeoForge")
            for (name in classes) {
                val entry = zip.getEntry((PREFIX + name).replace('.', '/') + ".class")
                require(entry != null, "Missing shipped class: $name")
                java.io.DataInputStream(zip.getInputStream(entry)).use {
                    require(it.readInt() == 0xCAFEBABE.toInt(), "Not a JVM class")
                    it.readUnsignedShort()
                    require(it.readUnsignedShort() == 69, "Shipped class is not JVM 25")
                }
            }
        }
    }

    private fun checkRuntimeBytes(runtime: Path, originalPath: Path, fabric: Boolean) {
        // Compare every original class/resource byte: Loom may add nesting metadata but
        // must not shade, relocate, remove or replace the JetBrains runtime itself.
        ZipFile(runtime.toFile()).use { shipped ->
            ZipFile(originalPath.toFile()).use { original ->
                for (entry in original.stream().filter { !it.isDirectory }.toList()) {
                    val actual = shipped.getEntry(entry.name)
                    require(actual != null, "Missing stdlib resource: " + entry.name)
                    val expectedBytes = original.getInputStream(entry).use { it.readAllBytes() }
                    val actualBytes = shipped.getInputStream(actual).use { it.readAllBytes() }
                    require(expectedBytes.contentEquals(actualBytes), "Modified stdlib resource: " + entry.name)
                }
                if (fabric) {
                    val descriptor = shipped.getEntry("fabric.mod.json")
                    require(descriptor != null, "Nested Fabric library is not discoverable")
                    val metadata = shipped.getInputStream(descriptor).use { it.readAllBytes().toString(Charsets.UTF_8) }
                    require(metadata.contains("org_jetbrains_kotlin_kotlin-stdlib"), "Fabric runtime lost canonical deduplication ID")
                }
            }
        }
    }

    private fun checkIsolatedExecution(artifact: Path, runtime: Path, fabric: Boolean) {
        URLClassLoader(arrayOf(artifact.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use {
            try {
                it.loadClass("kotlin.jvm.internal.Intrinsics")
                throw AssertionError("Runtime leaked from dev classpath")
            } catch (_: ClassNotFoundException) { }
        }
        URLClassLoader(arrayOf(artifact.toUri().toURL(), runtime.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            require(Path.of(loader.loadClass("kotlin.jvm.internal.Intrinsics").protectionDomain.codeSource.location.toURI()) == runtime, "Runtime did not come from the shipped nested JAR")
            val cache = loader.loadClass(PREFIX + "collection.FrameGeometryCache")
            val membership = loader.loadClass(PREFIX + "collection.FrameMembership")
            val dispatcher = loader.loadClass(PREFIX + "concurrent.BoundedTaskDispatcher")
            val types = mutableListOf(cache, membership, dispatcher)
            if (!fabric) {
                val entrypoint = loader.loadClass(PREFIX + "neoforge.TransitCoreNeoForge")
                types.add(entrypoint)
                require(entrypoint.constructors.size == 1 && entrypoint.constructors[0].parameterCount == 0,
                    "NeoForge entrypoint must keep exactly one public no-argument constructor")
                require(entrypoint.getConstructor().newInstance().javaClass === entrypoint,
                    "NeoForge cannot instantiate the Kotlin entrypoint as an ordinary JVM class")
            }
            for (type in types) {
                require(Path.of(type.protectionDomain.codeSource.location.toURI()) == artifact, "Dev classes masked shipped classes")
                require(type.declaredAnnotations.any { it.annotationClass.java.name == "kotlin.Metadata" }, "Shipped class lacks Kotlin metadata")
            }
            var created = 0
            var disposed = 0
            val instance = cache.getConstructor(java.lang.Long.TYPE, Integer.TYPE, ToLongFunction::class.java, Consumer::class.java)
                .newInstance(1L, 120, ToLongFunction<Any> { 2L }, Consumer<Any> { disposed++ })
            val key = Any()
            val create = Function<Any, Any> { created++; Any() }
            repeat(3) {
                cache.getMethod("beginFrame").invoke(instance)
                cache.getMethod("get", Any::class.java, Function::class.java).invoke(instance, key, create)
                cache.getMethod("finishFrame").invoke(instance)
            }
            require(created == 1 && disposed == 0, "Packaged cache evicted active geometry")
            cache.getMethod("close").invoke(instance)
            require(disposed == 1, "Packaged cache failed disposal")

            val members = membership.getConstructor().newInstance()
            val added = ArrayList<Any>()
            val removed = ArrayList<Any>()
            membership.getMethod("mark", Any::class.java).invoke(members, key)
            membership.getMethod("reconcile", Consumer::class.java, Consumer::class.java).invoke(members, Consumer<Any>(added::add), Consumer<Any>(removed::add))
            membership.getMethod("reconcile", Consumer::class.java, Consumer::class.java).invoke(members, Consumer<Any>(added::add), Consumer<Any>(removed::add))
            require(added == listOf(key) && removed == listOf(key), "Packaged membership lost transitions")

            val scheduler = dispatcher.getConstructor(Executor::class.java, Integer.TYPE).newInstance(Executor(Runnable::run), 1)
            val upload = loader.loadClass(PREFIX + "concurrent.BoundedTaskDispatcher" + '$' + "Upload")
            var uploaded = 0
            var freed = 0
            var finished = 0
            val work = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(upload)) { _, method, _ ->
                if (method.name == "upload") uploaded++
                if (method.name == "close") freed++
                null
            }
            val schedule = dispatcher.getMethod("trySchedule", Runnable::class.java, Supplier::class.java, BooleanSupplier::class.java, Runnable::class.java, Consumer::class.java)
            val callbacks = arrayOf(Runnable {}, Supplier { work }, BooleanSupplier { true },
                Runnable { finished++ }, Consumer<Throwable> { throw AssertionError(it) })
            require(schedule.invoke(scheduler, *callbacks) as Boolean, "Packaged dispatcher did not accept work")
            require(!(schedule.invoke(scheduler, *callbacks) as Boolean), "Queued result released its permit too early")
            require(dispatcher.getMethod("uploadOne").invoke(scheduler) as Boolean, "Packaged dispatcher lost its result")
            require(uploaded == 1 && freed == 1 && finished == 1, "Packaged dispatcher failed lifecycle")
        }
    }

    private fun require(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
