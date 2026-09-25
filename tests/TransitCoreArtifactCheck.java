import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.zip.ZipFile;

/** Exercise the shipped core with only its declared Kotlin stdlib, not a dev classpath. */
public final class TransitCoreArtifactCheck {
    private static final String PREFIX = "io.github.linlunaire.transitcore.";

    public static void main(String[] args) throws Exception {
        Path artifact = Path.of(args[0]).toAbsolutePath();
        boolean fabric = args[1].equals("fabric");
        try (var zip = new ZipFile(artifact.toFile())) {
            String metadataName = fabric ? "fabric.mod.json" : "META-INF/neoforge.mods.toml";
            var metadataEntry = zip.getEntry(metadataName);
            require(metadataEntry != null, "Missing loader metadata");
            String metadata = new String(zip.getInputStream(metadataEntry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            require(metadata.contains("transit_core") && metadata.contains("0.1.0") && metadata.contains("26.2"), "Incorrect identity/version");
            require(!metadata.contains("${"), "Unexpanded dependency metadata");
            require(metadata.contains(fabric ? "fabric-language-kotlin" : "kotlinforforge"), "Missing Kotlin prerequisite");
            require(metadata.contains(fabric ? "1.13.14+kotlin.2.4.20" : "[6.3.0,7)"), "Incorrect Kotlin runtime floor");
            require(!metadata.contains("architectury"), "Library acquired an Architectury runtime requirement");
            if (!fabric) {
                require(metadata.contains("type = \"required\"") && metadata.contains("side = \"BOTH\""), "Kotlin prerequisite must be required on both sides");
                require(zip.getEntry("io/github/linlunaire/transitcore/neoforge/TransitCoreNeoForge.class") != null, "Missing NeoForge mod entrypoint");
            }
            for (var entry : zip.stream().toList()) {
                String name = entry.getName();
                require(!(name.startsWith("kotlin/") && name.endsWith(".class")), "Bundled Kotlin stdlib class: " + name);
                require(!(name.endsWith(".jar") && name.contains("kotlin-stdlib")), "Nested Kotlin stdlib");
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
        try (var loader = new URLClassLoader(new java.net.URL[]{artifact.toUri().toURL(), Path.of(args[2]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
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
        System.out.println("PASS: " + args[1] + " release contains JVM 25 Kotlin policies, isolated Java interop, required runtime metadata, no bundled stdlib or legacy sources");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
