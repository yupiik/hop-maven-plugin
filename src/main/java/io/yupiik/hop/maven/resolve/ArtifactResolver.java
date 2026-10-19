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
package io.yupiik.hop.maven.resolve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.installation.InstallRequest;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;

/**
 * Thin Maven-free wrapper over the aether {@link RepositorySystem} resolving artifacts and,
 * optionally, their transitive dependency graphs.
 * <p>
 * The {@link RepositorySystem}/{@link RepositorySystemSession}/{@link RemoteRepository}s are injected
 * by Maven in the mojo and passed in here; this class keeps all Maven API out of the lower-level
 * distribution code so it stays unit-testable with a {@code file://} repository (see the tests).
 */
public class ArtifactResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public ArtifactResolver(
            final RepositorySystem system,
            final RepositorySystemSession session,
            final List<RemoteRepository> repositories) {
        if (system == null) {
            throw new IllegalStateException("The Maven resolver (RepositorySystem) is not available; "
                    + "make sure this mojo runs inside Maven");
        }
        this.system = system;
        this.session = session;
        this.repositories = repositories == null ? List.of() : repositories;
    }

    /**
     * Resolve a single artifact (no transitive dependencies).
     *
     * @param coordinates the {@code groupId:artifactId:version[:type[:classifier]]} coordinates.
     * @return the resolved local file.
     */
    public Path resolve(final Coordinates coordinates) {
        return resolve(coordinates.toArtifact("jar", ""));
    }

    private Path resolve(final Artifact artifact) {
        try {
            final var request = new ArtifactRequest(artifact, repositories, null);
            final var result = system.resolveArtifact(session, request);
            if (!result.isResolved() || result.getArtifact().getFile() == null) {
                throw new IllegalStateException("Unable to resolve " + artifact);
            }
            return result.getArtifact().getFile().toPath();
        } catch (final ArtifactResolutionException e) {
            throw new IllegalStateException("Unable to resolve " + artifact + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolve an artifact from the local repository only (no remote requests). Used to check whether a
     * locally-cached distribution archive is already installed without paying a remote round-trip on a
     * cache miss.
     *
     * @param coordinates the {@code groupId:artifactId:extension[:classifier]:version} coordinates.
     * @return the resolved local file.
     * @throws IllegalStateException when the artifact is not present in the local repository.
     */
    public Path resolveLocalOnly(final Coordinates coordinates) {
        final var artifact = coordinates.toArtifact("jar", "");
        final var localManager = session.getLocalRepositoryManager();
        final var base = localManager.getRepository().getBasedir().toPath();
        final var localFile = base.resolve(localManager.getPathForLocalArtifact(artifact));
        if (Files.isRegularFile(localFile)) {
            return localFile;
        }
        throw new IllegalStateException("Unable to resolve " + artifact + " from the local repository");
    }

    /**
     * Install an archive into the local repository under the given coordinates, alongside a minimal
     * generated POM so the artifact is registered and resolvable in the local repository.
     *
     * @param coordinates the {@code groupId:artifactId:extension[:classifier]:version} coordinates.
     * @param file        the archive to install.
     */
    public void install(final Coordinates coordinates, final Path file) {
        final var artifact = coordinates.toArtifact("jar", "").setFile(file.toFile());
        final var request = new InstallRequest().addArtifact(artifact);
        final var pom =
                new DefaultArtifact(coordinates.groupId(), coordinates.artifactId(), "pom", coordinates.version());
        final var pomFile = generatePom(coordinates, pom);
        request.addArtifact(pom.setFile(pomFile.toFile()));
        try {
            system.install(session, request);
        } catch (final InstallationException e) {
            throw new IllegalStateException("Unable to install " + artifact + ": " + e.getMessage(), e);
        }
    }

    private static Path generatePom(final Coordinates coordinates, final DefaultArtifact pom) {
        try {
            final var file = Files.createTempFile("hop-cache-", ".pom");
            final var pomXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" "
                    + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                    + "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
                    + "http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                    + "  <modelVersion>4.0.0</modelVersion>\n"
                    + "  <groupId>" + coordinates.groupId() + "</groupId>\n"
                    + "  <artifactId>" + coordinates.artifactId() + "</artifactId>\n"
                    + "  <version>" + coordinates.version() + "</version>\n"
                    + "  <packaging>" + (coordinates.type() == null ? "jar" : coordinates.type()) + "</packaging>\n"
                    + "</project>\n";
            Files.writeString(file, pomXml, java.nio.charset.StandardCharsets.UTF_8);
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to generate the cache POM " + pom + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolve the artifact and its transitive dependencies and copy/return them as a list of local
     * files (the resolved artifact first, then the dependencies in dependency order).
     *
     * @param coordinates the root {@code groupId:artifactId:version[:type[:classifier]]} coordinates.
     */
    public List<Path> resolveWithDependencies(final Coordinates coordinates) {
        final var root = coordinates.toArtifact("jar", "");
        final var dependency = new Dependency(root, "compile");
        try {
            final var collect = new CollectRequest(dependency, repositories);
            final var request = new DependencyRequest(collect, null);
            final var result = system.resolveDependencies(session, request);
            return result.getArtifactResults().stream()
                    .filter(ArtifactResult::isResolved)
                    .map(resultItem -> resultItem.getArtifact().getFile())
                    .filter(file -> file != null)
                    .map(java.io.File::toPath)
                    .toList();
        } catch (final DependencyResolutionException e) {
            throw new IllegalStateException(
                    "Unable to resolve dependencies of " + coordinates + ": " + e.getMessage(), e);
        }
    }
}
