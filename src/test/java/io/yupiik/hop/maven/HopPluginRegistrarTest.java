/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.hop.maven;

import io.yupiik.hop.maven.plugin.HopPluginArtifactMapper;
import io.yupiik.hop.maven.plugin.HopPluginRegistrar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.IndexWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link HopPluginRegistrar}: builds a real local Maven repository
 * ({@code file://}) containing a fake {@code hop-transform-fake} jar with a jandex index and a
 * {@code @Transform} annotated class, then checks the registrar stages the jar and registers the
 * plugin into the real Hop {@link PluginRegistry}.
 */
class HopPluginRegistrarTest {

    private static final String GROUP = "org.apache.hop";
    private static final String ARTIFACT = "hop-transform-fake";
    private static final String VERSION = "1.0.0";

    @TempDir
    Path tmp;

    @Test
    void stagesAndRegistersTransformPlugin() throws Exception {
        final var repoDir = tmp.resolve("repo");
        buildPluginJar(repoDir);

        final var pluginsBase = tmp.resolve("hop-plugins");
        final var registry = PluginRegistry.getInstance();

        // first: register the plugin via the registrar
        final var system = newSystem();
        final var session = MavenRepositorySystemUtils.newSession();
        final var local = new LocalRepository(repoDir.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, local));

        final var remote = new RemoteRepository.Builder("local", "default", repoDir.toUri().toString()).build();
        final var defaultVersion = VERSION;
        // the id FakeTransform does not follow the hop-transform-<id> convention: exercise the
        // user override mapping at the same time.
        final var registrar = new HopPluginRegistrar(
                system, session, List.of(remote), pluginsBase, registry,
                new HopPluginArtifactMapper(
                        Map.of("FakeTransform", "org.apache.hop:hop-transform-fake:" + VERSION), Map.of()),
                defaultVersion);

        final var staged = registrar.resolveAndStage(
                Set.of("FakeTransform"), Set.of(), Set.of(), Set.of());

        assertTrue(staged.getMissingTransformIds().isEmpty(), "no missing transform expected");
        assertEquals(1, staged.getStaged().size());
        assertTrue(Files.exists(staged.getStaged().get(0).jar()),
                "resolved jar must be staged at " + staged.getStaged().get(0).jar());

        registrar.register(staged);

        assertNotNull(registry.findPluginWithId(TransformPluginType.class, "FakeTransform"),
                "the FakeTransform plugin must be registered after register()");
    }

    private RepositorySystem newSystem() {
        // MavenRepositorySystemUtils registers the Maven-specific readers/factories; the
        // DefaultServiceLocator constructor already registers the resolver implementations,
        // only the connector and the file transport are missing.
        final var locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    private void buildPluginJar(final Path repoDir) throws IOException {
        final var versionedDir = repoDir.resolve(GROUP.replace('.', '/')).resolve(ARTIFACT).resolve(VERSION);
        Files.createDirectories(versionedDir);
        final var jarFile = versionedDir.resolve(ARTIFACT + "-" + VERSION + ".jar");

        // a pom is required by the artifact descriptor reader even for a plain jar
        Files.writeString(versionedDir.resolve(ARTIFACT + "-" + VERSION + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(GROUP, ARTIFACT, VERSION), StandardCharsets.UTF_8);

        // need the FakeTransform class bytes; it is compiled by the test compilation itself,
        // so read it from the test classpath
        final var className = FakeTransform.class.getName();
        final var classResource = className.replace('.', '/') + ".class";
        final byte[] classBytes;
        try (var in = FakeTransform.class.getClassLoader().getResourceAsStream(classResource)) {
            assertNotNull(in, "test class " + classResource + " must be on the test classpath");
            classBytes = in.readAllBytes();
        }

        try (final var jar = new JarOutputStream(Files.newOutputStream(jarFile))) {
            jar.putNextEntry(new JarEntry(classResource));
            jar.write(classBytes);
            jar.closeEntry();

            // jandex index with the @Transform annotation
            final var indexer = new Indexer();
            indexer.index(new java.io.ByteArrayInputStream(classBytes));
            final var index = indexer.complete();
            // verify the index contains our annotation before writing
            final var ann = DotName.createSimple("org.apache.hop.core.annotations.Transform");
            var found = false;
            for (final var instance : index.getAnnotations(ann)) {
                if (instance.target() instanceof ClassInfo) {
                    found = true;
                }
            }
            assertTrue(found, "jandex index must contain the @Transform annotation");

            jar.putNextEntry(new JarEntry("META-INF/jandex.idx"));
            final byte[] indexBytes;
            try (final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                new IndexWriter(buffer).write(index);
                indexBytes = buffer.toByteArray();
            }
            jar.write(indexBytes);
            jar.closeEntry();
        }
    }
}