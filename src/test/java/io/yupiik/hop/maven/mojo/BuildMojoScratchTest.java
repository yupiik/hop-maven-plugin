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
package io.yupiik.hop.maven.mojo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.maven.resolve.ArtifactResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildMojoScratchTest {

    private static final String GROUP = "org.apache.hop";
    private static final String VERSION = "1.0.0";

    @TempDir
    Path tmp;

    @Test
    void scratchBuildsOnlyTheCustomContentWithoutAnyBase() throws Exception {
        final var repoDir = tmp.resolve("repo");
        buildPluginJar(repoDir, "hop-transform-custom");
        buildPluginJar(repoDir, "hop-driver-custom");

        final var system = newSystem();
        final var session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(repoDir.toFile())));
        final var remote =
                new RemoteRepository.Builder("local", "default", repoDir.toUri().toString()).build();
        final var resolver = new ArtifactResolver(system, session, List.of(remote));

        // the custom application content (config) the distribution will layer
        final var baseDir = tmp.resolve("src/main/hop/distribution");
        Files.createDirectories(baseDir.resolve("config/projects/sample"));
        Files.writeString(
                baseDir.resolve("config/projects/sample/project-config.json"),
                "{\"name\":\"sample\"}",
                StandardCharsets.UTF_8);

        // a minimal project so workdirPath()/defaults resolve
        final var model = new Model();
        model.setGroupId("io.yupiik.example");
        model.setArtifactId("sample");
        model.setVersion("1.0.0");
        final var build = new Build();
        build.setDirectory(tmp.resolve("target").toString());
        model.setBuild(build);
        final var project = new MavenProject(model);

        final var hop = tmp.resolve("target/hop-build/hop");
        final var hopFolder = new java.io.File(hop.toString());

        final var mojo = new BuildMojo() {
            @java.lang.Override
            protected ArtifactResolver newResolver() {
                return resolver;
            }
        };
        mojo.project = project;
        mojo.hopFolder = hopFolder;
        mojo.baseDirectory = baseDir.toFile();
        mojo.versionUrl = "scratch";
        mojo.drivers = List.of(GROUP + ":hop-driver-custom:" + VERSION);
        mojo.transforms = List.of(GROUP + ":hop-transform-custom:" + VERSION);

        final var assembled = mojo.assembleDistribution();

        assertNotNull(assembled, "assembleDistribution must return the hop root");
        // the base distribution folder is created
        assertTrue(Files.isDirectory(hop), "hop/ must exist");
        // custom content present
        assertTrue(
                Files.isRegularFile(hop.resolve("lib/jdbc/hop-driver-custom-" + VERSION + ".jar")),
                "custom jdbc driver -> lib/jdbc");
        assertTrue(
                Files.isRegularFile(hop.resolve(
                        "plugins/transforms/hop-transform-custom/hop-transform-custom-" + VERSION + ".jar")),
                "custom transform -> plugins/transforms");
        assertTrue(
                Files.isRegularFile(hop.resolve("config/projects/sample/project-config.json")),
                "baseDirectory config merged");
        // NO base distribution content was produced
        assertFalse(Files.exists(hop.resolve("hop-run.sh")), "no base launcher must exist in scratch mode");
        assertFalse(Files.isDirectory(hop.resolve("lib/core")), "no base lib/core must exist in scratch mode");
        assertFalse(Files.isDirectory(hop.resolve("licenses")), "no base licenses must exist in scratch mode");
    }

    @Test
    void isScratchRecognizesTheSentinelAndBlankValues() {
        final var mojo = new BuildMojo();
        assertTrue(mojo.isScratch(), "null versionUrl must be scratch");
        mojo.versionUrl = "   ";
        assertTrue(mojo.isScratch(), "blank versionUrl must be scratch");
        mojo.versionUrl = "SCRATCH";
        assertTrue(mojo.isScratch(), "'SCRATCH' must be scratch (case-insensitive)");
        mojo.versionUrl = "https://downloads.apache.org/hop/${hop.version}/apache-hop-client-${hop.version}.zip";
        assertFalse(mojo.isScratch(), "a URL must not be scratch");
        mojo.versionUrl = "io.yupiik:distribution:1.0.0";
        assertFalse(mojo.isScratch(), "a Maven coordinate must not be scratch");
    }

    @Test
    void runFamilyScratchWarnsSkipsUnzipAndFakesTheDistribution() throws Exception {
        final var repoDir = tmp.resolve("repo");
        buildPluginJar(repoDir, "hop-transform-custom");

        final var system = newSystem();
        final var session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(repoDir.toFile())));
        final var remote =
                new RemoteRepository.Builder("local", "default", repoDir.toUri().toString()).build();
        final var resolver = new ArtifactResolver(system, session, List.of(remote));

        // the user "fakes" a tiny distribution: a launcher + some config via baseDirectory
        final var baseDir = tmp.resolve("src/main/hop/distribution");
        Files.createDirectories(baseDir.resolve("config"));
        Files.writeString(baseDir.resolve("config/hop-config.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(baseDir.resolve("hop-run.sh"), "#!/bin/sh\necho fake-hop\n", StandardCharsets.UTF_8);

        final var model = new Model();
        model.setGroupId("io.yupiik.example");
        model.setArtifactId("sample");
        model.setVersion("1.0.0");
        final var build = new Build();
        build.setDirectory(tmp.resolve("target").toString());
        model.setBuild(build);

        final var mojo = new BaseDistributionExecuteCommand() {
            @java.lang.Override
            protected String scriptBaseName() {
                return "hop-run";
            }

            @java.lang.Override
            protected ArtifactResolver newResolver() {
                return resolver;
            }
        };
        mojo.project = new MavenProject(model);
        mojo.versionUrl = "scratch";
        mojo.baseDirectory = baseDir.toFile();
        mojo.transforms = List.of(GROUP + ":hop-transform-custom:" + VERSION);
        mojo.waitFor = true;
        mojo.failOnError = true;
        final var logLines = new ArrayList<String>();
        mojo.setLog(capturingLog(logLines));

        // must not throw: scratch is handled with a warning, the enrichment builds the fake distro
        // and the provided hop-run.sh (made executable) is run.
        mojo.execute();

        final var exploded = tmp.resolve("target/hop-run/exploded");
        assertTrue(
                logLines.stream().anyMatch(line -> line.contains("[hop] fake-hop")),
                "the launcher output must be captured through the Maven log, got: " + logLines);
        assertTrue(Files.isRegularFile(exploded.resolve("hop-run.sh")), "fake hop-run.sh must exist");
        assertTrue(exploded.resolve("hop-run.sh").toFile().canExecute(), "fake hop-run.sh must be executable");
        assertTrue(Files.isRegularFile(exploded.resolve("config/hop-config.json")), "baseDirectory config merged");
        assertTrue(
                Files.isRegularFile(exploded.resolve(
                        "plugins/transforms/hop-transform-custom/hop-transform-custom-" + VERSION + ".jar")),
                "custom transform installed by the enrichment");
    }

    private static void buildPluginJar(final Path repoDir, final String artifactId) throws Exception {
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
            out.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("content.txt"));
            out.write(("content-" + artifactId).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static RepositorySystem newSystem() {
        final var locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    private static Log capturingLog(final List<String> lines) {
        return new Log() {
            @Override
            public boolean isDebugEnabled() {
                return false;
            }

            @Override
            public void debug(final CharSequence content) {
                lines.add("debug: " + content);
            }

            @Override
            public void debug(final CharSequence content, final Throwable error) {
                lines.add("debug: " + content);
            }

            @Override
            public void debug(final Throwable error) {
                lines.add("debug: " + error.getMessage());
            }

            @Override
            public boolean isInfoEnabled() {
                return true;
            }

            @Override
            public void info(final CharSequence content) {
                lines.add("info: " + content);
            }

            @Override
            public void info(final CharSequence content, final Throwable error) {
                lines.add("info: " + content);
            }

            @Override
            public void info(final Throwable error) {
                lines.add("info: " + error.getMessage());
            }

            @Override
            public boolean isWarnEnabled() {
                return true;
            }

            @Override
            public void warn(final CharSequence content) {
                lines.add("warn: " + content);
            }

            @Override
            public void warn(final CharSequence content, final Throwable error) {
                lines.add("warn: " + content);
            }

            @Override
            public void warn(final Throwable error) {
                lines.add("warn: " + error.getMessage());
            }

            @Override
            public boolean isErrorEnabled() {
                return true;
            }

            @Override
            public void error(final CharSequence content) {
                lines.add("error: " + content);
            }

            @Override
            public void error(final CharSequence content, final Throwable error) {
                lines.add("error: " + content);
            }

            @Override
            public void error(final Throwable error) {
                lines.add("error: " + error.getMessage());
            }
        };
    }
}
