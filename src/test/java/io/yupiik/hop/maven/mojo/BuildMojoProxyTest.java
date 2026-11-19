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
package io.yupiik.hop.maven.mojo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.hop.maven.resolve.ArtifactResolver;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildMojoProxyTest {
    private static final String ENCRYPTED_PASSWORD = "{encrypted-proxy-secret}";
    private static final String CLEAR_USERNAME = "decrypted-proxy-user";
    private static final String CLEAR_PASSWORD = "decrypted-proxy-secret";

    @TempDir
    Path tmp;

    @Test
    void downloadsWithDecryptedProxyCredentialsWithoutMutatingSettings() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var authorizations = new CopyOnWriteArrayList<String>();
        final var challenges = new AtomicInteger();
        final var expectedAuthorization = "Basic "
                + Base64.getEncoder()
                        .encodeToString((CLEAR_USERNAME + ':' + CLEAR_PASSWORD).getBytes(StandardCharsets.ISO_8859_1));
        server.createContext("/", exchange -> {
            try (exchange) {
                final var authorization = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
                if (authorization != null) {
                    authorizations.add(authorization);
                }
                if (!expectedAuthorization.equals(authorization)) {
                    challenges.incrementAndGet();
                    exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"hop-proxy\"");
                    exchange.sendResponseHeaders(407, -1);
                    return;
                }
                final var body = "downloaded-through-authenticated-proxy".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            final var settings = settings(server.getAddress().getPort());
            final var original = settings.getProxies().get(0);
            final var inactive = original.clone();
            inactive.setId("inactive-proxy");
            inactive.setActive(false);
            settings.addProxy(inactive);
            final var decryptions = new AtomicInteger();
            final var mojo = mojo(settings, request -> {
                decryptions.incrementAndGet();
                assertEquals(1, request.getProxies().size());
                final var requested = request.getProxies().get(0);
                assertNotSame(original, requested, "decryption must operate on a settings clone");
                assertEquals("download-proxy", requested.getId());
                assertEquals("settings-proxy-user", requested.getUsername());
                assertEquals(ENCRYPTED_PASSWORD, requested.getPassword());
                final var decrypted = requested.clone();
                decrypted.setUsername(CLEAR_USERNAME);
                decrypted.setPassword(CLEAR_PASSWORD);
                return result(decrypted, List.of());
            });

            final var downloaded = mojo.resolveDistributionZip(tmp.resolve("success"));

            assertEquals(tmp.resolve("success/apache-hop-client.zip"), downloaded);
            assertEquals("downloaded-through-authenticated-proxy", Files.readString(downloaded));
            assertEquals(1, decryptions.get(), "only the active proxy should be decrypted");
            assertEquals(1, challenges.get(), "the proxy must challenge before receiving credentials");
            assertEquals(List.of(expectedAuthorization), authorizations);
            assertSame(settings, mojo.settings);
            assertSame(original, settings.getProxies().get(0));
            assertEquals("settings-proxy-user", original.getUsername());
            assertEquals(ENCRYPTED_PASSWORD, original.getPassword());
            assertSame(inactive, settings.getProxies().get(1));
            assertEquals(ENCRYPTED_PASSWORD, inactive.getPassword());
            assertFalse(inactive.isActive());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decryptionErrorsBlockDownloadWithoutExposingCredentials() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var hits = new AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
        try {
            for (final var severity : List.of(SettingsProblem.Severity.ERROR, SettingsProblem.Severity.FATAL)) {
                final var settings = settings(server.getAddress().getPort());
                final var original = settings.getProxies().get(0);
                final var decryptions = new AtomicInteger();
                final var mojo = mojo(settings, request -> {
                    decryptions.incrementAndGet();
                    final var decrypted = request.getProxies().get(0).clone();
                    decrypted.setUsername(CLEAR_USERNAME);
                    decrypted.setPassword(CLEAR_PASSWORD);
                    return result(decrypted, List.of(problem(severity)));
                });
                final var logs = new ArrayList<String>();
                mojo.setLog((Log) java.lang.reflect.Proxy.newProxyInstance(
                        Log.class.getClassLoader(), new Class<?>[] {Log.class}, (proxy, method, args) -> {
                            if (method.getReturnType() == boolean.class) {
                                return true;
                            }
                            if (args != null) {
                                for (final var arg : args) {
                                    logs.add(String.valueOf(arg));
                                }
                            }
                            return null;
                        }));
                final var workdir = tmp.resolve(severity.name());

                final var failure = assertThrows(IOException.class, () -> mojo.resolveDistributionZip(workdir));

                assertEquals("Unable to decrypt Maven proxy credentials for 'download-proxy'", failure.getMessage());
                assertEquals(1, decryptions.get());
                assertEquals(0, hits.get(), "decryption failure must prevent any download request");
                assertFalse(Files.exists(workdir), "download must not even create its work directory");
                final var trace = new StringWriter();
                try (final var writer = new PrintWriter(trace)) {
                    failure.printStackTrace(writer);
                }
                final var diagnostics = trace + "\n" + String.join("\n", logs);
                for (final var secret :
                        List.of(ENCRYPTED_PASSWORD, "settings-proxy-user", CLEAR_USERNAME, CLEAR_PASSWORD)) {
                    assertFalse(diagnostics.contains(secret), "diagnostics must not expose proxy credentials");
                }
                assertSame(original, settings.getProxies().get(0));
                assertEquals("settings-proxy-user", original.getUsername());
                assertEquals(ENCRYPTED_PASSWORD, original.getPassword());
            }
        } finally {
            server.stop(0);
        }
    }

    private Settings settings(final int port) {
        final var proxy = new Proxy();
        proxy.setId("download-proxy");
        proxy.setActive(true);
        proxy.setProtocol("http");
        proxy.setHost("127.0.0.1");
        proxy.setPort(port);
        proxy.setUsername("settings-proxy-user");
        proxy.setPassword(ENCRYPTED_PASSWORD);
        final var settings = new Settings();
        settings.addProxy(proxy);
        return settings;
    }

    private BuildMojo mojo(final Settings settings, final SettingsDecrypter decrypter) throws Exception {
        final var mojo = new BuildMojo() {
            @java.lang.Override
            protected ArtifactResolver newResolver() {
                return null;
            }
        };
        mojo.settings = settings;
        // The proxy serves the bytes itself; no origin server or external DNS is needed.
        mojo.versionUrl = "http://distribution.invalid/apache-hop-client.zip";
        mojo.cacheDistribution = false;
        mojo.downloadTimeoutMinutes = 1;
        final var field = BuildMojo.class.getDeclaredField("settingsDecrypter");
        field.setAccessible(true);
        field.set(mojo, decrypter);
        return mojo;
    }

    private SettingsDecryptionResult result(final Proxy decrypted, final List<SettingsProblem> problems) {
        return (SettingsDecryptionResult) java.lang.reflect.Proxy.newProxyInstance(
                SettingsDecryptionResult.class.getClassLoader(),
                new Class<?>[] {SettingsDecryptionResult.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getProxy" -> decrypted;
                    case "getProxies" -> List.of(decrypted);
                    case "getProblems" -> problems;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private SettingsProblem problem(final SettingsProblem.Severity severity) {
        final var sensitiveMessage =
                "Unable to decrypt " + ENCRYPTED_PASSWORD + " as " + CLEAR_USERNAME + ':' + CLEAR_PASSWORD;
        return (SettingsProblem) java.lang.reflect.Proxy.newProxyInstance(
                SettingsProblem.class.getClassLoader(),
                new Class<?>[] {SettingsProblem.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSeverity" -> severity;
                    case "getMessage", "toString" -> sensitiveMessage;
                    case "getException" -> new IllegalStateException(sensitiveMessage);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
