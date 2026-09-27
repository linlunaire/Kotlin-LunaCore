import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.ModResolver;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.neoforged.jarjar.selection.JarSelector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Uses the actual pinned loader resolvers; no Minecraft world or live instance is started. */
public final class KotlinRuntimeResolutionCheck {
    private static final String STDLIB_ID = "org_jetbrains_kotlin_kotlin-stdlib";
    private static final String UNIT = "kotlin/Unit.class";
    private static Path output;

    public static void main(String[] args) throws Exception {
        Path fabricArtifact = Path.of(args[0]).toAbsolutePath();
        Path neoArtifact = Path.of(args[1]).toAbsolutePath();
        JarData flk = JarData.read(Path.of(args[2]));
        JarData kff = JarData.read(Path.of(args[3]));
        output = Path.of(args[4]);
        Files.createDirectories(output);
        JarData newerStdlib = JarData.read(Path.of(args[5]));
        String baseline = args[6];
        String newer = args[7];

        checkFabric(fabricArtifact, List.of(), baseline, "fabric-alone");
        checkFabric(fabricArtifact, List.of(flk), newer, "fabric-with-flk-newer");
        checkNeoForge(neoArtifact, List.of(), baseline, "neoforge-alone");
        checkNeoForge(neoArtifact, List.of(kff), baseline, "neoforge-with-kff-same");
        checkNeoForge(neoArtifact, List.of(kff, jarJarHolder(newerStdlib, newer)), newer,
                "neoforge-with-kff-and-newer");
        System.out.println("PASS: 5 real-loader resolution cases select one stdlib and execute packaged Core through it");
    }

    private static void checkFabric(Path corePath, List<JarData> extras, String expected, String label) throws Exception {
        List<ModCandidateImpl> candidates = new ArrayList<>();
        Map<ModCandidateImpl, JarData> jars = new IdentityHashMap<>();
        collectFabric(JarData.read(corePath), true, candidates, jars);
        for (JarData extra : extras) collectFabric(extra, true, candidates, jars);
        for (String[] builtin : List.of(new String[]{"minecraft", "26.2"}, new String[]{"java", "25"},
                new String[]{"fabricloader", "0.19.3"})) {
            String metadata = "{\"schemaVersion\":1,\"id\":\"" + builtin[0] + "\",\"version\":\"" + builtin[1] + "\"}";
            LoaderModMetadata parsed = parse(metadata.getBytes(StandardCharsets.UTF_8), builtin[0]);
            candidates.add(fabricCandidate(parsed, builtin[0], true, List.of()));
        }
        List<ModCandidateImpl> selected = ModResolver.resolve(candidates, EnvType.CLIENT, Map.of());
        List<ModCandidateImpl> runtimes = selected.stream().filter(mod -> mod.getId().equals(STDLIB_ID)).toList();
        require(runtimes.size() == 1, label + ": duplicate/missing Fabric stdlib");
        require(runtimes.getFirst().getVersion().getFriendlyString().equals(expected), label + ": wrong Fabric version");
        require(selected.stream().filter(mod -> jars.containsKey(mod) && jars.get(mod).entries.containsKey(UNIT)).count() == 1,
                label + ": more than one selected JAR contains kotlin.Unit");
        executeCore(corePath, jars.get(runtimes.getFirst()), expected, label);
    }

    private static ModCandidateImpl collectFabric(JarData jar, boolean root, List<ModCandidateImpl> candidates,
            Map<ModCandidateImpl, JarData> jars) throws Exception {
        LoaderModMetadata metadata = parse(jar.required("fabric.mod.json"), jar.name);
        List<ModCandidateImpl> nested = new ArrayList<>();
        for (var entry : metadata.getJars()) {
            nested.add(collectFabric(JarData.read(jar.name + "!/" + entry.getFile(), jar.required(entry.getFile())),
                    false, candidates, jars));
        }
        ModCandidateImpl candidate = fabricCandidate(metadata, jar.name, root, nested);
        var addParent = ModCandidateImpl.class.getDeclaredMethod("addParent", ModCandidateImpl.class);
        addParent.setAccessible(true);
        for (ModCandidateImpl child : nested) addParent.invoke(child, candidate);
        candidates.add(candidate);
        jars.put(candidate, jar);
        return candidate;
    }

    // Keep test code outside Fabric's signed packages; reflect only over the pinned resolver's fixture construction API.
    private static ModCandidateImpl fabricCandidate(LoaderModMetadata metadata, String name, boolean root,
            List<ModCandidateImpl> nested) throws ReflectiveOperationException {
        if (root) {
            var create = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class,
                    boolean.class, Collection.class);
            create.setAccessible(true);
            return (ModCandidateImpl) create.invoke(null, List.of(Path.of(metadata.getId() + ".jar")), metadata, false, nested);
        }
        var create = ModCandidateImpl.class.getDeclaredMethod("createNested", String.class, long.class,
                LoaderModMetadata.class, boolean.class, Collection.class);
        create.setAccessible(true);
        return (ModCandidateImpl) create.invoke(null, name, 0L, metadata, false, nested);
    }

    private static LoaderModMetadata parse(byte[] bytes, String name) throws Exception {
        return ModMetadataParser.parseMetadata(new ByteArrayInputStream(bytes), name, List.of(),
                new VersionOverrides(), new DependencyOverrides(output.resolve("no-overrides")), false);
    }

    private static void checkNeoForge(Path corePath, List<JarData> extras, String expected, String label) throws Exception {
        List<JarData> roots = new ArrayList<>();
        roots.add(JarData.read(corePath));
        roots.addAll(extras);
        List<JarData> selected = JarSelector.detectAndSelect(roots,
                (jar, entry) -> Optional.ofNullable(jar.entries.get(entry)).map(ByteArrayInputStream::new),
                (jar, entry) -> jar.nested(entry), jar -> jar.name,
                failures -> new IllegalStateException(label + ": " + failures));
        List<JarData> runtimes = selected.stream().filter(jar -> jar.entries.containsKey(UNIT)).toList();
        require(runtimes.size() == 1, label + ": duplicate/missing NeoForge stdlib");
        executeCore(corePath, runtimes.getFirst(), expected, label);
    }

    private static JarData jarJarHolder(JarData runtime, String version) {
        String path = "META-INF/jars/kotlin-stdlib-" + version + ".jar";
        JsonObject identity = new JsonObject();
        identity.addProperty("group", "org.jetbrains.kotlin");
        identity.addProperty("artifact", "kotlin-stdlib");
        JsonObject versions = new JsonObject();
        versions.addProperty("range", "[" + version + ",)");
        versions.addProperty("artifactVersion", version);
        JsonObject entry = new JsonObject();
        entry.add("identifier", identity);
        entry.add("version", versions);
        entry.addProperty("path", path);
        JsonArray entries = new JsonArray();
        entries.add(entry);
        JsonObject metadata = new JsonObject();
        metadata.add("jars", entries);
        return new JarData("compatible-newer-runtime-fixture", null, Map.of(
                "META-INF/jarjar/metadata.json", metadata.toString().getBytes(StandardCharsets.UTF_8),
                path, runtime.bytes));
    }

    private static void executeCore(Path artifact, JarData runtime, String expected, String label) throws Exception {
        Path runtimePath = output.resolve(label + "-stdlib.jar");
        Files.write(runtimePath, runtime.bytes);
        try (var loader = new URLClassLoader(new URL[]{artifact.toUri().toURL(), runtimePath.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            var version = loader.loadClass("kotlin.KotlinVersion");
            require(version.getField("CURRENT").get(null).toString().equals(expected), label + ": selected runtime not executed");
            require(java.util.Collections.list(loader.getResources(UNIT)).size() == 1, label + ": duplicate runtime classes");
            Class<?> membership = loader.loadClass("io.github.linlunaire.transitcore.collection.FrameMembership");
            Object instance = membership.getConstructor().newInstance();
            Object key = new Object();
            List<Object> added = new ArrayList<>();
            List<Object> removed = new ArrayList<>();
            membership.getMethod("mark", Object.class).invoke(instance, key);
            var reconcile = membership.getMethod("reconcile", Consumer.class, Consumer.class);
            reconcile.invoke(instance, (Consumer<Object>) added::add, (Consumer<Object>) removed::add);
            reconcile.invoke(instance, (Consumer<Object>) added::add, (Consumer<Object>) removed::add);
            require(added.equals(List.of(key)) && removed.equals(List.of(key)), label + ": packaged Core execution failed");
        }
        System.out.println("PASS: " + label + " selects and runs Kotlin " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class JarData {
        final String name;
        final byte[] bytes;
        final Map<String, byte[]> entries;
        final Map<String, JarData> nested = new LinkedHashMap<>();

        JarData(String name, byte[] bytes, Map<String, byte[]> entries) {
            this.name = name;
            this.bytes = bytes;
            this.entries = entries;
        }

        static JarData read(Path path) throws IOException {
            return read(path.toString(), Files.readAllBytes(path));
        }

        static JarData read(String name, byte[] bytes) throws IOException {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) entries.put(entry.getName(), zip.readAllBytes());
                }
            }
            return new JarData(name, bytes, entries);
        }

        byte[] required(String entry) {
            byte[] content = entries.get(entry);
            require(content != null, name + ": missing nested resource " + entry);
            return content;
        }

        Optional<JarData> nested(String entry) {
            if (!entries.containsKey(entry)) return Optional.empty();
            try {
                if (!nested.containsKey(entry)) nested.put(entry, read(name + "!/" + entry, entries.get(entry)));
                return Optional.of(nested.get(entry));
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }
    }
}
