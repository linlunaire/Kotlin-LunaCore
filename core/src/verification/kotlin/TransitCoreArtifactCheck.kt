import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.zip.ZipFile;

/** Exercise the shipped core with its nested runtime, never a development classpath. */
public final class TransitCoreArtifactCheck {
    private static final String PREFIX = "io.github.linlunaire.transitcore.";

    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]).toAbsolutePath();
        boolean fabric = args[1].equals("fabric");
        Path runtime = Files.createTempFile("transit-core-runtime-", ".jar");
        try {
        try (var zip = new ZipFile(artifact.toFile())) {
            String metadataName = fabric ? "fabric.mod.json" : "META-INF/neoforge.mods.toml";
            var metadataEntry = zip.getEntry(metadataName);
            require(metadataEntry != null, "Missing loader metadata");
            String metadata = new String(zip.getInputStream(metadataEntry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            require(metadata.contains("transit_core") && metadata.contains(args[3]) && metadata.contains("26.2"), "Incorrect identity/version");
            require(!metadata.contains("${"), "Unexpanded dependency metadata");
            require(!metadata.contains("fabric-language-kotlin") && !metadata.contains("kotlinforforge"), "External language mod is still required");
            String nestedPath = "META-INF/jars/kotlin-stdlib-" + args[4] + ".jar";
            var nested = zip.getEntry(nestedPath);
            require(nested != null, "Missing nested Kotlin stdlib");
            require(zip.stream().filter(entry -> entry.getName().endsWith(".jar")).count() == 1, "Runtime must contain only the required stdlib");
            try (var input = zip.getInputStream(nested)) {
                Files.copy(input, runtime, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            require(zip.getEntry("META-INF/transit-core/licenses/Kotlin-LICENSE.txt") != null, "Missing Kotlin license");
            require(zip.getEntry("META-INF/transit-core/licenses/Kotlin-NOTICE.txt") != null, "Missing Kotlin attribution");
            if (fabric) {
                require(metadata.contains(nestedPath), "Fabric cannot discover its runtime");
            } else {
                var jarjar = zip.getEntry("META-INF/jarjar/metadata.json");
                require(jarjar != null, "Missing NeoForge JarJar metadata");
                String descriptor = new String(zip.getInputStream(jarjar).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                require(descriptor.contains("org.jetbrains.kotlin") && descriptor.contains("kotlin-stdlib") && descriptor.contains(nestedPath), "JarJar lost the canonical runtime identity");
                require(descriptor.contains("[" + args[4] + ",)"), "JarJar runtime floor is incorrect");
            }
            require(!metadata.contains("architectury"), "Library acquired an Architectury runtime requirement");
            if (!fabric) {
                require(metadata.contains("type = \"required\"") && metadata.contains("side = \"BOTH\""), "Kotlin prerequisite must be required on both sides");
                require(zip.getEntry("io/github/linlunaire/transitcore/neoforge/TransitCoreNeoForge.class") != null, "Missing NeoForge mod entrypoint");
            }
            for (var entry : zip.stream().toList()) {
                String name = entry.getName();
                require(!(name.startsWith("kotlin/") && name.endsWith(".class")), "Bundled Kotlin stdlib class: " + name);
                require(!name.startsWith("legacy/") && !name.startsWith("mtr/") && !name.startsWith("cn/zbx1425/"), "Old namespace/material entered runtime: " + name);
            }
            for (String name : List.of("collection.FrameGeometryCache", "collection.FrameMembership", "concurrent.BoundedTaskDispatcher")) {
                var entry = zip.getEntry((PREFIX + name).replace('.', '/') + ".class");
                require(entry != null, "Missing shipped core class: " + name);
                try (var input = new java.io.DataInputStream(zip.getInputStream(entry))) {
                    require(input.readInt() == 0xCAFEBABE, "Not a JVM class");
                    input.readUnsignedShort();
                    require(input.readUnsignedShort() == 69, "Core is not JVM 25");
                }
            }
        }
        // Compare every original class/resource byte: Loom may add nesting metadata but
        // must not shade, relocate, remove or replace the JetBrains runtime itself.
        try (var shipped = new ZipFile(runtime.toFile()); var original = new ZipFile(args[2])) {
            for (var entry : original.stream().filter(entry -> !entry.isDirectory()).toList()) {
                var actual = shipped.getEntry(entry.getName());
                require(actual != null, "Missing stdlib resource: " + entry.getName());
                require(java.util.Arrays.equals(original.getInputStream(entry).readAllBytes(), shipped.getInputStream(actual).readAllBytes()), "Modified stdlib resource: " + entry.getName());
            }
            if (fabric) {
                var descriptor = shipped.getEntry("fabric.mod.json");
                require(descriptor != null, "Nested Fabric library is not discoverable");
                String metadata = new String(shipped.getInputStream(descriptor).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                require(metadata.contains("org_jetbrains_kotlin_kotlin-stdlib"), "Fabric runtime lost canonical deduplication ID");
            }
        }
        try (var missingRuntime = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            try { missingRuntime.loadClass("kotlin.jvm.internal.Intrinsics"); throw new AssertionError("Runtime leaked from dev classpath"); }
            catch (ClassNotFoundException expected) { }
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL(), runtime.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            require(Path.of(loader.loadClass("kotlin.jvm.internal.Intrinsics").getProtectionDomain().getCodeSource().getLocation().toURI()).equals(runtime), "Runtime did not come from the shipped nested JAR");
            Class<?> cache = loader.loadClass(PREFIX + "collection.FrameGeometryCache");
            Class<?> membership = loader.loadClass(PREFIX + "collection.FrameMembership");
            Class<?> dispatcher = loader.loadClass(PREFIX + "concurrent.BoundedTaskDispatcher");
            for (Class<?> type : List.of(cache, membership, dispatcher)) {
                require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(artifact), "Dev classes masked shipped classes");
                require(java.util.Arrays.stream(type.getDeclaredAnnotations()).anyMatch(a -> a.annotationType().getName().equals("kotlin.Metadata")), "Shipped class lacks Kotlin metadata");
            }
            int[] created = {0}, disposed = {0};
            Object instance = cache.getConstructor(long.class, int.class, ToLongFunction.class, Consumer.class)
                    .newInstance(1L, 120, (ToLongFunction<Object>) value -> 2L, (Consumer<Object>) value -> disposed[0]++);
            Object key = new Object();
            Function<Object, Object> create = ignored -> { created[0]++; return new Object(); };
            for (int frame = 0; frame < 3; frame++) {
                cache.getMethod("beginFrame").invoke(instance);
                cache.getMethod("get", Object.class, Function.class).invoke(instance, key, create);
                cache.getMethod("finishFrame").invoke(instance);
            }
            require(created[0] == 1 && disposed[0] == 0, "Packaged cache evicted active geometry");
            cache.getMethod("close").invoke(instance);
            require(disposed[0] == 1, "Packaged cache failed disposal");

            Object members = membership.getConstructor().newInstance();
            List<Object> added = new ArrayList<>(), removed = new ArrayList<>();
            membership.getMethod("mark", Object.class).invoke(members, key);
            membership.getMethod("reconcile", Consumer.class, Consumer.class).invoke(members, (Consumer<Object>) added::add, (Consumer<Object>) removed::add);
            membership.getMethod("reconcile", Consumer.class, Consumer.class).invoke(members, (Consumer<Object>) added::add, (Consumer<Object>) removed::add);
            require(added.equals(List.of(key)) && removed.equals(List.of(key)), "Packaged membership lost transitions");

            Object scheduler = dispatcher.getConstructor(Executor.class, int.class).newInstance((Executor) Runnable::run, 1);
            Class<?> upload = loader.loadClass(PREFIX + "concurrent.BoundedTaskDispatcher$Upload");
            int[] uploaded = {0}, freed = {0}, finished = {0};
            Object work = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{upload}, (proxy, method, arguments) -> {
                if (method.getName().equals("upload")) uploaded[0]++;
                if (method.getName().equals("close")) freed[0]++;
                return null;
            });
            var schedule = dispatcher.getMethod("trySchedule", Runnable.class, Supplier.class, BooleanSupplier.class, Runnable.class, Consumer.class);
            Object[] callbacks = {(Runnable) () -> {}, (Supplier<Object>) () -> work, (BooleanSupplier) () -> true,
                    (Runnable) () -> finished[0]++, (Consumer<Throwable>) error -> { throw new AssertionError(error); }};
            require((boolean) schedule.invoke(scheduler, callbacks), "Packaged dispatcher did not accept work");
            require(!(boolean) schedule.invoke(scheduler, callbacks), "Queued result released its permit too early");
            require((boolean) dispatcher.getMethod("uploadOne").invoke(scheduler), "Packaged dispatcher lost its result");
            require(uploaded[0] == 1 && freed[0] == 1 && finished[0] == 1, "Packaged dispatcher failed lifecycle");
        }
        System.out.println("PASS: " + args[1] + " release runs JVM 25 Kotlin policies using only its nested unmodified stdlib, canonical loader identity and license notices (not a game launch)");
        } finally {
            Files.deleteIfExists(runtime);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
