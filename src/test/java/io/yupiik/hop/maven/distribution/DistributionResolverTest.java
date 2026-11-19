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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.hop.maven.resolve.ArtifactResolver;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void downloadsThroughMavenProxy(final boolean authenticated) throws Exception {
        final var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var requestedUri = new AtomicReference<String>();
        final var proxyAuthorization = new AtomicReference<String>();
        final var originAuthorization = new AtomicReference<String>();
        final var challenges = new AtomicInteger();
        proxy.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            proxyAuthorization.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            originAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (authenticated && proxyAuthorization.get() == null) {
                challenges.incrementAndGet();
                exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"maven-proxy\"");
                exchange.sendResponseHeaders(407, -1);
            } else if (originAuthorization.get() == null) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"distribution\"");
                exchange.sendResponseHeaders(401, -1);
            } else {
                final var body = "proxied-zip".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        proxy.start();
        try {
            final var settings = proxySettings(proxy.getAddress().getPort());
            // Neither inactive proxies nor proxies for another protocol should be selected.
            final var inactive = new Proxy();
            inactive.setHost("inactive.invalid");
            inactive.setActive(false);
            settings.getProxies().addFirst(inactive);
            final var socks = new Proxy();
            socks.setProtocol("socks");
            socks.setHost("socks.invalid");
            settings.getProxies().addFirst(socks);
            final var active = settings.getProxies().getLast();
            if (authenticated) {
                active.setUsername("proxy-user");
                active.setPassword("encrypted-password");
            }
            final var resolver = new DistributionResolver(
                    null,
                    settings,
                    null,
                    "origin-user",
                    "origin-password",
                    null,
                    tmp,
                    null,
                    secret -> "encrypted-password".equals(secret) ? "proxy-password" : secret,
                    false,
                    Duration.ofSeconds(10));
            final var url = "http://distribution.invalid/hop.zip";
            assertEquals("proxied-zip", Files.readString(resolver.resolve(url)));
            assertEquals(url, requestedUri.get());
            assertEquals(basic("origin-user:origin-password"), originAuthorization.get());
            if (authenticated) {
                assertEquals(basic("proxy-user:proxy-password"), proxyAuthorization.get());
                assertEquals(1, challenges.get());
            } else {
                assertNull(proxyAuthorization.get());
            }
        } finally {
            proxy.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "127.*", "localhost|127.*|::1", "127.0.0.1"})
    void bypassesInactiveOrExcludedMavenProxy(final String exclusion) throws Exception {
        final var origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var proxyAuthorization = new AtomicReference<String>();
        origin.createContext("/hop.zip", exchange -> {
            proxyAuthorization.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            final var body = "direct-zip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        try {
            final var settings = proxySettings(1); // an accidental proxy connection must fail
            final var proxy = settings.getProxies().getFirst();
            proxy.setActive(!"inactive".equals(exclusion));
            proxy.setNonProxyHosts(exclusion);
            proxy.setUsername("proxy-user");
            proxy.setPassword("proxy-password");
            final var resolver = new DistributionResolver(
                    null, settings, null, null, null, null, tmp, null, null, false, Duration.ofSeconds(10));
            assertEquals(
                    "direct-zip",
                    Files.readString(resolver.resolve(
                            "http://127.0.0.1:" + origin.getAddress().getPort() + "/hop.zip")));
            assertNull(proxyAuthorization.get());
        } finally {
            origin.stop(0);
        }
    }

    @Test
    void reevaluatesMavenProxyOnRedirect() throws Exception {
        final var proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var proxyHits = new AtomicInteger();
        final var originAuthorization = new AtomicReference<String>();
        origin.createContext("/hop.zip", exchange -> {
            originAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            final var body = "redirected-zip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        proxy.createContext("/", exchange -> {
            proxyHits.incrementAndGet();
            exchange.getResponseHeaders()
                    .set("Location", "http://127.0.0.1:" + origin.getAddress().getPort() + "/hop.zip");
            final var body = "redirect-body-longer-than-the-downloaded-zip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        origin.start();
        proxy.start();
        try {
            final var settings = proxySettings(proxy.getAddress().getPort());
            settings.getProxies().getFirst().setNonProxyHosts("127.*");
            final var resolver = new DistributionResolver(
                    null,
                    settings,
                    null,
                    "origin-user",
                    "origin-password",
                    null,
                    tmp,
                    null,
                    null,
                    false,
                    Duration.ofSeconds(10));
            assertEquals("redirected-zip", Files.readString(resolver.resolve("http://distribution.invalid/hop.zip")));
            assertEquals(1, proxyHits.get());
            assertNull(originAuthorization.get(), "download credentials must not follow a cross-origin redirect");
        } finally {
            proxy.stop(0);
            origin.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void tunnelsHttpsThroughMavenProxy(final String protocol) throws Exception {
        try (final var proxy = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.setSoTimeout(5_000);
            final var request = executor.submit(() -> {
                try (final var connection = proxy.accept()) {
                    connection.setSoTimeout(5_000);
                    final var reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                    final var first = reader.readLine();
                    while (true) {
                        final var header = reader.readLine();
                        if (header == null || header.isEmpty()) {
                            break;
                        }
                    }
                    connection
                            .getOutputStream()
                            .write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    return first;
                }
            });
            final var settings = proxySettings(proxy.getLocalPort());
            settings.getProxies().getFirst().setProtocol(protocol);
            final var resolver = new DistributionResolver(
                    null, settings, null, null, null, null, tmp, null, null, false, Duration.ofSeconds(5));
            assertThrows(java.io.IOException.class, () -> resolver.resolve("https://distribution.invalid/hop.zip"));
            assertEquals("CONNECT distribution.invalid:443 HTTP/1.1", request.get(10, TimeUnit.SECONDS));
        }
    }

    private static Settings proxySettings(final int port) {
        final var settings = new Settings();
        final var proxy = new Proxy();
        proxy.setHost("127.0.0.1");
        proxy.setPort(port);
        proxy.setProtocol("http");
        proxy.setActive(true);
        settings.addProxy(proxy);
        return settings;
    }

    private static String basic(final String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
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
