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
package io.yupiik.hop.maven.distribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.hop.maven.resolve.ArtifactResolver;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributionResolverTest {

    @TempDir
    Path tmp;

    @Test
    void detectsUrlVsCoordinateBySlash() {
        assertTrue(DistributionResolver.isUrl("https://downloads.apache.org/hop/x.zip"));
        assertTrue(DistributionResolver.isUrl("http://host/x.zip"));
        assertFalse(DistributionResolver.isUrl("org.apache.hop:hop-core:2.19.0"));
    }

    @Test
    void downloadsAndInterpolatesVersion() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicReference<String> requestedPath = new AtomicReference<>();
        server.createContext("/hop/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            final var body = "zip-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final var port = server.getAddress().getPort();
            final var resolver = new DistributionResolver(
                    newResolver(tmp.resolve("repo-1")),
                    null,
                    "2.19.0",
                    null,
                    null,
                    null,
                    tmp,
                    ignored -> {},
                    null,
                    true,
                    java.time.Duration.ofMinutes(15));
            final var target =
                    resolver.resolve("http://127.0.0.1:" + port + "/hop/${version}/apache-hop-client-${version}.zip");
            assertTrue(Files.isRegularFile(target), "archive must be downloaded");
            assertTrue(
                    requestedPath.get().contains("/hop/2.19.0/apache-hop-client-2.19.0.zip"),
                    "version placeholder must be substituted: " + requestedPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheCoordinatesDerivation() {
        final var c = DistributionResolver.cacheCoordinates(
                "https://downloads.apache.org/hop/2.19.0/apache-hop-client-2.19.0.zip");
        assertEquals("io.yupiik.hop.cache", c.groupId());
        assertEquals("apache-hop-client-2.19.0.zip", c.artifactId());
        assertEquals("2.19.0", c.version());
        assertEquals("zip", c.type());
        assertNotNull(c.classifier());
        assertEquals(16, c.classifier().length());
        assertNotEquals(
                DistributionResolver.cacheCoordinates(
                                "https://downloads.apache.org/hop/2.19.0/apache-hop-client-2.19.0.zip")
                        .classifier(),
                DistributionResolver.cacheCoordinates(
                                "https://mirror.apache.org/hop/2.19.0/apache-hop-client-2.19.0.zip")
                        .classifier(),
                "a different URL must map to a different classifier");
    }

    @Test
    void downloadsOnceThenReusesTheLocalCache() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var hits = new AtomicInteger();
        server.createContext("/hop/", exchange -> {
            hits.incrementAndGet();
            final var body = "zip-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final var port = server.getAddress().getPort();
            final var localRepo = tmp.resolve("repo-2");
            final var artifactResolver = newResolver(localRepo);
            final var url = "http://127.0.0.1:" + port + "/hop/2.19.0/apache-hop-client-2.19.0.zip";
            final var resolver = new DistributionResolver(
                    artifactResolver,
                    null,
                    null,
                    null,
                    null,
                    null,
                    tmp,
                    ignored -> {},
                    null,
                    true,
                    java.time.Duration.ofMinutes(15));
            assertNotNull(resolver.resolve(url), "first call must download");
            assertEquals(1, hits.get(), "first call must hit the network exactly once");
            final var cached = resolver.resolve(url);
            assertNotNull(cached, "second call must resolve from the local repository");
            assertEquals(1, hits.get(), "second call must be served from cache, no network hit");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsEveryTimeWhenCachingDisabled() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var hits = new AtomicInteger();
        server.createContext("/hop/", exchange -> {
            hits.incrementAndGet();
            final var body = "zip-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final var port = server.getAddress().getPort();
            final var localRepo = tmp.resolve("repo-3");
            final var artifactResolver = newResolver(localRepo);
            final var url = "http://127.0.0.1:" + port + "/hop/2.19.0/apache-hop-client-2.19.0.zip";
            final var resolver = new DistributionResolver(
                    artifactResolver,
                    null,
                    null,
                    null,
                    null,
                    null,
                    tmp,
                    ignored -> {},
                    null,
                    false,
                    java.time.Duration.ofMinutes(15));
            resolver.resolve(url);
            resolver.resolve(url);
            assertEquals(2, hits.get(), "with caching disabled every call must download");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadHonorsConfiguredTimeout() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hop/", exchange -> {
            try {
                Thread.sleep(2_000); // longer than the configured 1s timeout
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            final var port = server.getAddress().getPort();
            final var url = "http://127.0.0.1:" + port + "/hop/2.19.0/apache-hop-client-2.19.0.zip";
            final var resolver = new DistributionResolver(
                    newResolver(tmp.resolve("repo-4")),
                    null,
                    null,
                    null,
                    null,
                    null,
                    tmp,
                    ignored -> {},
                    null,
                    false,
                    java.time.Duration.ofSeconds(1));
            final var e = assertThrows(java.io.IOException.class, () -> resolver.resolve(url));
            assertTrue(
                    e instanceof java.net.http.HttpTimeoutException,
                    "the request timeout must surface as HttpTimeoutException, got "
                            + e.getClass().getName());
        } finally {
            server.stop(0);
        }
    }

    private static ArtifactResolver newResolver(final Path localRepo) throws java.io.IOException {
        final var locator = org.apache.maven.repository.internal.MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(
                org.eclipse.aether.spi.connector.RepositoryConnectorFactory.class,
                org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory.class);
        locator.addService(
                org.eclipse.aether.spi.connector.transport.TransporterFactory.class,
                org.eclipse.aether.transport.file.FileTransporterFactory.class);
        final var system = locator.getService(org.eclipse.aether.RepositorySystem.class);
        final var session = org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession();
        Files.createDirectories(localRepo);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(
                session, new org.eclipse.aether.repository.LocalRepository(localRepo.toFile())));
        return new ArtifactResolver(system, session, List.of());
    }
}
