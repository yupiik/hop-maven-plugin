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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.maven.resolve.ArtifactResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributionBuilderTest {

    private static final String GROUP = "org.apache.hop";
    private static final String VERSION = "1.0.0";

    @TempDir
    Path tmp;

    private Path repoDir;
    private ArtifactResolver resolver;
    private Path hopRoot;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tmp.resolve("repo");
        buildPluginJar("hop-transform-fake");
        buildPluginJar("hop-driver-fake");
        buildPluginJar("hop-lib-fake");

        final var system = newSystem();
        final var session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(repoDir.toFile())));
        final var remote =
                new RemoteRepository.Builder("local", "default", repoDir.toUri().toString()).build();
        resolver = new ArtifactResolver(system, session, List.of(remote));

        hopRoot = tmp.resolve("hop");
        Files.createDirectories(hopRoot.resolve("lib").resolve("core"));
        Files.writeString(hopRoot.resolve("lib/core/existing.jar"), "old");
        Files.writeString(
                hopRoot.resolve("hop-run.sh"),
                "#! /usr/bin/env bash\n\"${_HOP_JAVA}\" ${HOP_OPTIONS} org.apache.hop.run $@");
    }

    @Test
    void deploysTransformsIntoPlugins() throws Exception {
        new DistributionBuilder(resolver, hopRoot, ignored -> {})
                .enrich(new DistributionBuilder.EnrichmentSpec(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("org.apache.hop:hop-transform-fake:" + VERSION),
                        List.of(),
                        List.of(),
                        null));

        assertTrue(
                Files.isRegularFile(hopRoot.resolve(
                        "plugins/transforms/hop-transform-fake/hop-transform-fake-" + VERSION + ".jar")),
                "transform must be installed in plugins/transforms");
    }

    @Test
    void deploysDriversAndLibs() throws Exception {
        new DistributionBuilder(resolver, hopRoot, ignored -> {})
                .enrich(new DistributionBuilder.EnrichmentSpec(
                        List.of("org.apache.hop:hop-driver-fake:" + VERSION),
                        List.of("org.apache.hop:hop-lib-fake:" + VERSION),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null));

        assertTrue(
                Files.isRegularFile(hopRoot.resolve("lib/jdbc/hop-driver-fake-" + VERSION + ".jar")),
                "driver -> lib/jdbc");
        assertTrue(Files.isRegularFile(hopRoot.resolve("lib/hop-lib-fake-" + VERSION + ".jar")), "lib -> lib");
    }

    @Test
    void appliesOverrides() throws Exception {
        new DistributionBuilder(resolver, hopRoot, ignored -> {})
                .enrich(new DistributionBuilder.EnrichmentSpec(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new DistributionBuilder.OverrideEntry(
                                "lib/core/existing.jar",
                                io.yupiik.hop.maven.resolve.Coordinates.of("org.apache.hop:hop-lib-fake:" + VERSION),
                                Map.of())),
                        null));

        // the override copies the resolved (zip) jar bytes over the target in place
        final var bytes = java.nio.file.Files.readAllBytes(hopRoot.resolve("lib/core/existing.jar"));
        assertTrue(bytes.length > 0, "override must replace the target file");
        assertTrue(bytes[0] == 'P' && bytes[1] == 'K', "replaced file must be the zipped dependency jar");
    }

    @Test
    void mergesBaseDirectoryOnTop() throws Exception {
        final var base = tmp.resolve("src/main/hop/distribution");
        Files.createDirectories(base.resolve("config"));
        Files.writeString(base.resolve("config/project-config.json"), "{\"name\":\"mine\"}");
        Files.writeString(base.resolve("hop-run.sh"), "#! /usr/bin/env bash\necho custom");

        new DistributionBuilder(resolver, hopRoot, ignored -> {})
                .enrich(new DistributionBuilder.EnrichmentSpec(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), base));

        assertTrue(Files.isRegularFile(hopRoot.resolve("config/project-config.json")), "config merged");
        assertTrue(
                Files.readString(hopRoot.resolve("hop-run.sh"), StandardCharsets.UTF_8)
                        .contains("echo custom"),
                "file content must win over the distribution");
        assertTrue(hopRoot.resolve("hop-run.sh").toFile().canExecute(), ".sh must be executable after merge");
    }

    @Test
    void injectsJavaagentIntoScript() throws Exception {
        new DistributionBuilder(resolver, hopRoot, ignored -> {})
                .enrich(new DistributionBuilder.EnrichmentSpec(
                        List.of(),
                        List.of(),
                        List.of("org.apache.hop:hop-lib-fake:" + VERSION),
                        List.of(),
                        List.of(),
                        List.of(),
                        null));

        final var script = Files.readString(hopRoot.resolve("hop-run.sh"), StandardCharsets.UTF_8);
        assertTrue(
                script.contains("-javaagent:lib/hop-lib-fake-" + VERSION + ".jar"),
                "javaagent must be injected: " + script);
        assertTrue(Files.isRegularFile(hopRoot.resolve("lib/hop-lib-fake-" + VERSION + ".jar")));
    }

    private void buildPluginJar(final String artifactId) throws Exception {
        final var versionedDir =
                repoDir.resolve(GROUP.replace('.', '/')).resolve(artifactId).resolve(VERSION);
        Files.createDirectories(versionedDir);
        final var jarFile = versionedDir.resolve(artifactId + "-" + VERSION + ".jar");
        Files.writeString(
                versionedDir.resolve(artifactId + "-" + VERSION + ".pom"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(GROUP, artifactId, VERSION),
                StandardCharsets.UTF_8);
        try (final var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jarFile))) {
            out.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("content.txt"));
            out.write(("content-" + artifactId).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private RepositorySystem newSystem() {
        final var locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }
}
