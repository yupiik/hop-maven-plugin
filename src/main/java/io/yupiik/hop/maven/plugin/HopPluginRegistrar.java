/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.maven.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import org.apache.hop.core.annotations.Action;
import org.apache.hop.core.annotations.Transform;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;

/**
 * Resolves Hop transform/action artifacts with the Maven resolver and registers their plugins
 * into the Hop {@link PluginRegistry} programmatically, without touching any JVM-global property.
 * <p>
 * The work is split in two phases so the caller can keep the lock scope minimal:
 * <ul>
 *   <li>{@link #resolveAndStage} - resolves the artifacts and stages them under
 *       {@code <base>/transforms/<artifact>/} or {@code <base>/actions/<artifact>/} (dependencies
 *       in a {@code lib/} sub-folder, Hop canonical layout). This phase only touches the local
 *       staging folder and the Maven repositories: it is safe to run concurrently.</li>
 *   <li>{@link #register} - reads {@code META-INF/jandex.idx} of each staged jar and registers the
 *       annotated classes with a dedicated class loader (which also serves the SVG icons, exactly
 *       like a folder-based Hop plugin would). This phase mutates the shared registry and must be
 *       serialized; it re-checks the registry so the first mojo registering an id wins.</li>
 * </ul>
 * Because the classes are registered with a per-mojo class loader and no system property is set,
 * several mojos can run concurrently in the same build: the only shared state is the registry
 * itself, which is thread-safe.
 */
public class HopPluginRegistrar {

    private static final String JANDEX_INDEX = "META-INF/jandex.idx";
    private static final DotName TRANSFORM_ANNOTATION = DotName.createSimple(Transform.class.getName());
    private static final DotName ACTION_ANNOTATION = DotName.createSimple(Action.class.getName());

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final Path base;
    private final PluginRegistry registry;
    private final HopPluginArtifactMapper mapper;
    private final String defaultVersion;
    /** Number of artifacts resolved in parallel (aligned on Maven's maven.artifact.threads). */
    private final int resolutionThreads;
    /** Debug log sink (optional, no-op by default). */
    private final Consumer<String> debugLog;
    /** file names (e.g. hop-core-2.20.0.jar) already provided by the plugin realm. */
    private final Set<String> providedJarNames;

    public HopPluginRegistrar(
            final RepositorySystem system,
            final RepositorySystemSession session,
            final List<RemoteRepository> repositories,
            final Path base,
            final PluginRegistry registry,
            final HopPluginArtifactMapper mapper,
            final String defaultVersion,
            final int resolutionThreads,
            final Set<String> providedJarNames,
            final Consumer<String> debugLog) {
        this.system = system;
        this.session = session;
        this.repositories = repositories;
        this.base = base;
        this.registry = registry;
        this.mapper = mapper;
        this.defaultVersion = defaultVersion == null || defaultVersion.isBlank() ? null : defaultVersion.trim();
        this.resolutionThreads = Math.max(1, resolutionThreads);
        this.providedJarNames =
                providedJarNames == null ? providedJarNames(HopPluginRegistrar.class) : providedJarNames;
        this.debugLog = debugLog == null ? ignored -> {} : debugLog;
    }

    /**
     * First phase (safe to run outside any lock): for every id not yet in {@code alreadyKnown},
     * resolve the artifact with Maven and stage it under the base folder. The returned
     * {@link Staged} carries everything needed by {@link #register} plus the ids which could not
     * be resolved. The resolutions run concurrently ({@link #resolutionThreads} workers, aligned
     * on Maven's {@code maven.artifact.threads}); staging is idempotent (temp file + atomic move)
     * so concurrent workers cannot observe partial files.
     */
    public Staged resolveAndStage(
            final Set<String> transformIds,
            final Set<String> actionIds,
            final Set<String> alreadyKnownTransformIds,
            final Set<String> alreadyKnownActionIds) {
        final List<ResolveTask> tasks = new ArrayList<>();
        for (final var id : transformIds) {
            if (alreadyKnownTransformIds.contains(id)) {
                debugLog.accept("Resolution: skipping transform '" + id + "' (already registered)");
            } else {
                tasks.add(new ResolveTask(id, mapper.transformArtifactId(id), true));
            }
        }
        for (final var id : actionIds) {
            if (alreadyKnownActionIds.contains(id)) {
                debugLog.accept("Resolution: skipping action '" + id + "' (already registered)");
            } else {
                tasks.add(new ResolveTask(id, mapper.actionArtifactId(id), false));
            }
        }

        final var staged = new Staged();
        if (tasks.isEmpty()) {
            return staged;
        }
        if (tasks.size() == 1 || resolutionThreads == 1) {
            for (final var task : tasks) {
                stage(staged, task.pluginId(), task.artifact(), task.transform());
            }
            return staged;
        }

        final var pool = Executors.newFixedThreadPool(Math.min(resolutionThreads, tasks.size()));
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (final var task : tasks) {
                futures.add(pool.submit(() -> stage(staged, task.pluginId(), task.artifact(), task.transform())));
            }
            for (final var future : futures) {
                try {
                    future.get();
                } catch (final ExecutionException e) {
                    throw new IllegalStateException("Unable to auto-resolve a Hop plugin artifact", e.getCause());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while auto-resolving Hop plugin artifacts", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return staged;
    }

    private record ResolveTask(String pluginId, String artifact, boolean transform) {}

    private void stage(
            final Staged staged, final String pluginId, final String artifactIdOrCoordinates, final boolean transform) {
        final var coordinates = Coordinates.of(artifactIdOrCoordinates, defaultVersion);
        final var dir = base.resolve(transform ? "transforms" : "actions").resolve(coordinates.artifactId());
        final var jar = dir.resolve(coordinates.jarName());
        debugLog.accept("Resolution: " + (transform ? "transform" : "action") + " '" + pluginId + "' -> '"
                + coordinates.coordinates() + "', staging dir '" + dir + "'");
        try {
            if (!Files.exists(jar)) {
                final var files = resolve(coordinates);
                if (!files.isEmpty()) {
                    stage(files, dir);
                } else {
                    debugLog.accept("Resolution: no file resolved for '" + coordinates.coordinates() + "'");
                }
            } else {
                debugLog.accept("Resolution: '" + jar + "' already staged, skipping copy");
            }
            if (Files.exists(jar)) {
                debugLog.accept("Resolution: staged '" + pluginId + "' from '" + jar + "'");
                staged.staged.add(new StagedPlugin(pluginId, transform, jar));
            } else {
                debugLog.accept(
                        "Resolution: missing '" + pluginId + "' (artifact '" + coordinates.coordinates() + "')");
                staged.missing(pluginId, transform);
            }
        } catch (final DependencyResolutionException | ArtifactResolutionException e) {
            debugLog.accept("Resolution: failed for '" + pluginId + "' (artifact '" + coordinates.coordinates() + "'): "
                    + e.getMessage());
            staged.missing(pluginId, transform);
        }
    }

    /**
     * Resolve the root artifact and its transitive dependencies. Jars already provided by the
     * plugin realm (hop-core, hop-engine, ...) are skipped so the plugin class loader does not
     * shadow them. The returned list starts with the root jar.
     */
    private List<Path> resolve(final Coordinates coordinates)
            throws DependencyResolutionException, ArtifactResolutionException {
        final var artifact = new DefaultArtifact(coordinates.coordinates());
        final List<Path> files = new ArrayList<>();
        final Set<Path> seen = new HashSet<>();

        final var root = system.resolveArtifact(session, new ArtifactRequest(artifact, repositories, null));
        if (!root.isMissing()
                && root.getArtifact() != null
                && root.getArtifact().getFile() != null) {
            final var file = root.getArtifact().getFile().toPath().toAbsolutePath();
            debugLog.accept("Resolution: root artifact '" + coordinates.coordinates() + "' -> '" + file + "'");
            files.add(file);
            seen.add(file);
        }

        final var request =
                new DependencyRequest(new CollectRequest(new Dependency(artifact, "runtime"), repositories), null);
        for (final var result : system.resolveDependencies(session, request).getArtifactResults()) {
            if (result.isMissing()
                    || result.getArtifact() == null
                    || result.getArtifact().getFile() == null) {
                continue;
            }
            final var file = result.getArtifact().getFile().toPath().toAbsolutePath();
            if (seen.add(file) && !providedJarNames.contains(file.getFileName().toString())) {
                debugLog.accept("Resolution: dependency '" + file.getFileName() + "' -> '" + file + "'");
                files.add(file);
            } else if (providedJarNames.contains(file.getFileName().toString())) {
                debugLog.accept("Resolution: skipping provided jar '" + file.getFileName() + "'");
            }
        }
        debugLog.accept("Resolution: " + files.size() + " file(s) to stage for '" + coordinates.coordinates() + "'");
        return files;
    }

    /**
     * Copy the resolved jar(s) into the staging folder, idempotent. The plugin jar lands at the
     * root of the folder, the other dependencies in a {@code lib/} sub-folder. The copy happens in
     * the same directory (temp + atomic move) so concurrent mojos cannot observe partial files.
     */
    private static void stage(final List<Path> files, final Path dir) {
        try {
            Files.createDirectories(dir.resolve("lib"));
            copyIfAbsent(files.get(0), dir.resolve(files.get(0).getFileName()));
            for (int i = 1; i < files.size(); i++) {
                copyIfAbsent(
                        files.get(i), dir.resolve("lib").resolve(files.get(i).getFileName()));
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to stage plugin artifacts in " + dir, e);
        }
    }

    private static final AtomicLong COPY_SEQUENCE = new AtomicLong();

    private static void copyIfAbsent(final Path source, final Path target) throws IOException {
        if (!Files.exists(target)) {
            final var tmp = target.resolveSibling(target.getFileName() + "."
                    + ProcessHandle.current().pid() + "." + COPY_SEQUENCE.incrementAndGet() + ".tmp");
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
        }
    }

    /**
     * Second phase (must run under the caller's lock): register every staged plugin whose id is
     * still absent from the registry (another mojo may have registered it in the meantime).
     */
    public void register(final Staged staged) {
        for (final var plugin : staged.staged) {
            final var typeClass = plugin.transform ? TransformPluginType.class : ActionPluginType.class;
            if (registry.findPluginWithId(typeClass, plugin.pluginId) != null) {
                debugLog.accept("Registration: '" + plugin.pluginId + "' already registered, skipping");
                continue; // already registered by another mojo or by a plugin dependency
            }
            debugLog.accept("Registration: registering '" + plugin.pluginId + "' from '" + plugin.jar + "'");
            registerOne(plugin, typeClass);
        }
    }

    private void registerOne(final StagedPlugin plugin, final Class<? extends IPluginType> typeClass) {
        final var jar = plugin.jar;
        final Index index;
        try (final var jarFile = new JarFile(jar.toFile())) {
            final var entry = jarFile.getEntry(JANDEX_INDEX);
            if (entry == null) {
                return;
            }
            try (final var in = jarFile.getInputStream(entry)) {
                index = new IndexReader(in).read();
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to read " + jar + " jandex index", e);
        }

        final var annotationName = plugin.transform ? TRANSFORM_ANNOTATION : ACTION_ANNOTATION;
        final List<String> classNames = new ArrayList<>();
        for (final var instance : index.getAnnotations(annotationName)) {
            if (instance.target() instanceof org.jboss.jandex.ClassInfo classInfo) {
                classNames.add(classInfo.name().toString());
            }
        }
        if (classNames.isEmpty()) {
            return;
        }

        final List<URL> urls = new ArrayList<>();
        urls.add(toUrl(jar));
        final var lib = jar.getParent().resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar")).forEach(p -> urls.add(toUrl(p)));
            } catch (final IOException e) {
                throw new IllegalStateException("Unable to list " + lib, e);
            }
        }
        final var loader =
                new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());

        final var annotationClass = plugin.transform ? Transform.class : Action.class;
        // libraries drives the class loader Hop builds for the plugin instances at render time:
        // the plugin jar first, then the lib/ dependencies (canonical Hop layout).
        final List<String> libraries = new ArrayList<>();
        libraries.add(jar.toString());
        if (Files.isDirectory(lib)) {
            try (var stream = Files.list(lib)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(p -> libraries.add(p.toString()));
            } catch (final IOException e) {
                throw new IllegalStateException("Unable to list " + lib, e);
            }
        }
        final var pluginUrl = toUrl(jar);
        for (final var className : classNames) {
            try {
                registry.registerPluginClass(
                        loader, libraries, pluginUrl, className, typeClass, annotationClass, false);
                debugLog.accept("Registration: registered '" + plugin.pluginId + "' with class '" + className
                        + "' (annotation " + annotationClass.getSimpleName() + ")");
            } catch (final Exception e) {
                throw new IllegalStateException("Unable to register plugin " + className + " from " + jar, e);
            }
        }
    }

    private static Set<String> providedJarNames(final Class<?> reference) {
        final Set<String> names = new HashSet<>();
        final var loader = reference.getClassLoader();
        if (loader instanceof URLClassLoader urlClassLoader) {
            for (final var url : urlClassLoader.getURLs()) {
                if (url.getPath().endsWith(".jar")) {
                    names.add(Path.of(url.getPath()).getFileName().toString());
                }
            }
        }
        return names;
    }

    private static URL toUrl(final Path path) {
        try {
            return path.toUri().toURL();
        } catch (final IOException e) {
            throw new IllegalStateException("Invalid plugin jar URL: " + path, e);
        }
    }

    /** Result of {@link #resolveAndStage}: staged plugins plus the ids which could not be resolved. */
    public static class Staged {
        private final List<StagedPlugin> staged = new CopyOnWriteArrayList<>();
        private final Set<String> missingTransformIds = ConcurrentHashMap.newKeySet();
        private final Set<String> missingActionIds = ConcurrentHashMap.newKeySet();

        private void missing(final String pluginId, final boolean transform) {
            (transform ? missingTransformIds : missingActionIds).add(pluginId);
        }

        public List<StagedPlugin> getStaged() {
            return staged;
        }

        public Set<String> getMissingTransformIds() {
            return missingTransformIds;
        }

        public Set<String> getMissingActionIds() {
            return missingActionIds;
        }

        public boolean hasMissing() {
            return !missingTransformIds.isEmpty() || !missingActionIds.isEmpty();
        }
    }

    /** A plugin jar staged under the base folder, ready to be registered. */
    public record StagedPlugin(String pluginId, boolean transform, Path jar) {}

    /**
     * Normalized artifact coordinates: an {@code artifactId} alone becomes
     * {@code org.apache.hop:artifactId:<defaultVersion>}, {@code groupId:artifactId} keeps the
     * default version and {@code groupId:artifactId:version} is used as-is.
     */
    record Coordinates(String coordinates) {
        static Coordinates of(final String artifactIdOrCoordinates, final String defaultVersion) {
            final var value = artifactIdOrCoordinates == null ? "" : artifactIdOrCoordinates.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Empty artifact id");
            }
            final var version = defaultVersion == null ? null : defaultVersion.trim();
            if (value.chars().filter(c -> c == ':').count() >= 2) {
                return new Coordinates(value);
            }
            if (value.indexOf(':') > 0) {
                if (version == null || version.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cannot determine version for " + value + "; set the autoResolveVersion parameter");
                }
                return new Coordinates(value + ":" + version);
            }
            if (version == null || version.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cannot determine version for " + value + "; set the autoResolveVersion parameter");
            }
            return new Coordinates("org.apache.hop:" + value + ":" + version);
        }

        String artifactId() {
            final var parts = coordinates.split(":");
            return parts.length >= 2 ? parts[1] : coordinates;
        }

        String jarName() {
            final var parts = coordinates.split(":");
            final var artifact = parts.length >= 2 ? parts[1] : coordinates;
            final var version = parts.length >= 3 ? parts[2] : "";
            return artifact + "-" + version + ".jar";
        }
    }
}
