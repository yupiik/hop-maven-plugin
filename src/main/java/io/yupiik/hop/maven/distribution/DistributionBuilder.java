/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.maven.distribution;

import io.yupiik.hop.maven.resolve.ArtifactResolver;
import io.yupiik.hop.maven.resolve.Coordinates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Enriches an assembled {@code hop/} distribution: deploys the extra JDBC drivers, libs, javaagent
 * jars, custom transforms, marketplace plugins and the in-place jar overrides, then merges the
 * user {@code baseDirectory} content on top of the distribution so it wins.
 * <p>
 * Artifacts referenced by coordinates are resolved through {@link ArtifactResolver}; the plugin
 * installs reuse the canonical {@code plugins/<category>/<artifactId>/} layout via {@link HopInstaller}.
 */
public class DistributionBuilder {

    private final ArtifactResolver resolver;
    private final Consumer<String> info;
    private final Path hopRoot;

    public DistributionBuilder(final ArtifactResolver resolver, final Path hopRoot, final Consumer<String> info) {
        this.resolver = resolver;
        this.hopRoot = hopRoot;
        this.info = info == null ? ignored -> {} : info;
    }

    /**
     * Run every enrichment step against the already-unzipped distribution {@code hopRoot}.
     */
    public void enrich(final EnrichmentSpec spec) throws IOException {
        if (spec.drivers() != null && !spec.drivers().isEmpty()) {
            copyJars(spec.drivers(), hopRoot.resolve("lib").resolve("jdbc"), "driver");
        }
        if (spec.libs() != null && !spec.libs().isEmpty()) {
            copyJars(spec.libs(), hopRoot.resolve("lib"), "lib");
        }
        if (spec.javaagents() != null && !spec.javaagents().isEmpty()) {
            installJavaagents(spec.javaagents());
        }
        if (spec.transforms() != null && !spec.transforms().isEmpty()) {
            installPlugins(spec.transforms(), hopRoot.resolve("plugins"), null);
        }
        if (spec.marketplacePlugins() != null && !spec.marketplacePlugins().isEmpty()) {
            installPlugins(spec.marketplacePlugins(), hopRoot.resolve("plugins"), null);
        }
        if (spec.overrides() != null && !spec.overrides().isEmpty()) {
            applyOverrides(spec.overrides());
        }
        if (spec.baseDirectory() != null) {
            mergeBaseDirectory(spec.baseDirectory());
        }
    }

    private void copyJars(final List<String> coordinates, final Path target, final String kind) throws IOException {
        Files.createDirectories(target);
        for (final String coordinate : coordinates) {
            final var parsed = Coordinates.of(coordinate);
            final var source = resolver.resolve(parsed);
            final var dest = target.resolve(source.getFileName().toString());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            info.accept("Deployed " + kind + " " + parsed.coordinates() + " -> " + hopRoot.relativize(dest));
        }
    }

    private void installPlugins(final List<String> coordinates, final Path pluginsRoot, final String category)
            throws IOException {
        for (final String coordinate : coordinates) {
            final var parsed = Coordinates.of(coordinate);
            final var files = resolver.resolveWithDependencies(parsed);
            if (files.isEmpty()) {
                throw new IOException("No file resolved for plugin " + parsed.coordinates());
            }
            final var pluginJar = findPluginJar(files, parsed);
            final var folder = HopInstaller.installPlugin(pluginsRoot, parsed.artifactId(), pluginJar, files, category);
            info.accept("Deployed plugin " + parsed.coordinates() + " -> " + hopRoot.relativize(folder));
        }
    }

    private void applyOverrides(final List<OverrideEntry> overrides) throws IOException {
        for (final OverrideEntry override : overrides) {
            final var rel = override.path().replace('\\', '/');
            final var dest = hopRoot.resolve(rel).normalize();
            if (!dest.startsWith(hopRoot.normalize())) {
                throw new IOException("Override path escapes the distribution: " + override.path());
            }
            final var source = resolver.resolve(override.dependency());
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            info.accept("Overrode " + rel + " with " + override.dependency().coordinates());
        }
    }

    private void mergeBaseDirectory(final Path baseDirectory) throws IOException {
        if (!Files.isDirectory(baseDirectory)) {
            info.accept("baseDirectory '" + baseDirectory + "' does not exist, skipping the merge");
            return;
        }
        Files.walkFileTree(baseDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
                    throws IOException {
                if (dir.equals(baseDirectory)) {
                    return FileVisitResult.CONTINUE;
                }
                final var rel = baseDirectory.relativize(dir).toString().replace('\\', '/');
                if (rel.startsWith("..")) {
                    throw new IOException("baseDirectory entry escapes its root: " + dir);
                }
                Files.createDirectories(hopRoot.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                final var rel = baseDirectory.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("..")) {
                    throw new IOException("baseDirectory entry escapes its root: " + file);
                }
                final var dest = hopRoot.resolve(rel);
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                if (rel.endsWith(".sh")) {
                    file.toFile().setExecutable(true, false);
                    dest.toFile().setExecutable(true, false);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        info.accept("Merged baseDirectory " + baseDirectory + " into " + hopRoot);
    }

    private void installJavaagents(final List<String> javaagents) throws IOException {
        final var lib = hopRoot.resolve("lib");
        Files.createDirectories(lib);
        final var injections = new StringBuilder();
        for (final String raw : javaagents) {
            final var argSep = firstArgSeparator(raw);
            final var coordinate = argSep < 0 ? raw : raw.substring(0, argSep);
            final var args = argSep < 0 ? "" : raw.substring(argSep);
            final var parsed = Coordinates.of(coordinate);
            final var source = resolver.resolve(parsed);
            final var targetJar = lib.resolve(source.getFileName().toString());
            Files.copy(source, targetJar, StandardCopyOption.REPLACE_EXISTING);
            final var agent = "lib/" + targetJar.getFileName();
            injections.append(' ').append("-javaagent:").append(agent).append(args);
            info.accept("Deployed javaagent " + parsed.coordinates() + " -> " + hopRoot.relativize(targetJar));
        }
        if (injections.length() > 0) {
            injectJavaagentIntoScripts(injections.substring(1));
        }
    }

    private static int firstArgSeparator(final String raw) {
        final var eq = raw.indexOf('=');
        final var question = raw.indexOf('?');
        if (eq < 0) {
            return question;
        }
        if (question < 0) {
            return eq;
        }
        return Math.min(eq, question);
    }

    private void injectJavaagentIntoScripts(final String javaagentArg) throws IOException {
        try (var stream = Files.list(hopRoot)) {
            for (final Path script : stream.filter(p -> {
                        final var n = p.getFileName().toString();
                        return n.startsWith("hop-") && (n.endsWith(".sh") || n.endsWith(".bat"));
                    })
                    .toList()) {
                injectOne(script, javaagentArg);
            }
        }
    }

    private void injectOne(final Path script, final String javaagentArg) throws IOException {
        final var content = Files.readString(script, StandardCharsets.UTF_8);
        final var bat = script.getFileName().toString().endsWith(".bat");
        final var injection = bat
                ? "\nset HOP_OPTIONS=%HOP_OPTIONS% " + javaagentArg.replace('/', '\\') + "\n"
                : "\nHOP_OPTIONS=\"${HOP_OPTIONS} " + javaagentArg + "\"; export HOP_OPTIONS\n";
        final String updated;
        // the launch line is the first occurrence of the java invocation; insert right before it
        final var launchIdx = bat ? content.indexOf("%_HOP_JAVA%") : content.indexOf("\"${_HOP_JAVA}\"");
        if (launchIdx < 0) {
            info.accept("Unable to locate the java launch line in " + script + ", javaagent was not injected");
            return;
        }
        updated = content.substring(0, launchIdx) + injection + content.substring(launchIdx);
        Files.writeString(script, updated, StandardCharsets.UTF_8);
        if (!bat) {
            script.toFile().setExecutable(true, false);
        }
        info.accept("Injected javaagent into " + hopRoot.relativize(script));
    }

    private static Path findPluginJar(final List<Path> files, final Coordinates parsed) {
        final var expected = parsed.artifactId() + "-" + parsed.version();
        for (final Path file : files) {
            final var name = file.getFileName().toString();
            if (name.startsWith(expected) && name.endsWith(".jar")) {
                return file;
            }
        }
        // fall back to any jar matching the artifact base name
        for (final Path file : files) {
            final var name = file.getFileName().toString();
            if (name.startsWith(parsed.artifactId() + "-") && name.endsWith(".jar")) {
                return file;
            }
        }
        return files.get(0);
    }

    /**
     * All the enrichment inputs; fields mirror the build mojo parameters.
     */
    public record EnrichmentSpec(
            List<String> drivers,
            List<String> libs,
            List<String> javaagents,
            List<String> transforms,
            List<String> marketplacePlugins,
            List<OverrideEntry> overrides,
            Path baseDirectory) {}

    /**
     * An in-place jar override: a relative {@code path} inside the distribution overridden by a
     * resolved {@code dependency} coordinate.
     */
    public record OverrideEntry(String path, Coordinates dependency, Map<String, String> attributes) {}
}
