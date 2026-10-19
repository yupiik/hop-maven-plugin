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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerStopMojoTest {

    @TempDir
    Path tmp;

    @Test
    void sendsShutdownToTheConfiguredPortAndWaitsForItToClose() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicReference<String> authHeader = new AtomicReference<>();
        final var port = server.getAddress().getPort();
        server.createContext("/shutdown", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            // free the port shortly after, simulating a graceful Hop shutdown
            new Thread(() -> {
                        try {
                            Thread.sleep(100);
                            server.stop(0);
                        } catch (final InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .start();
        });
        server.start();
        try {
            final var config = tmp.resolve("hop-server.xml");
            Files.writeString(config, """
                    <hop-server-config>
                      <hop-server>
                        <hostname>127.0.0.1</hostname>
                        <port>%d</port>
                        <enable_auth>true</enable_auth>
                      </hop-server>
                    </hop-server-config>
                    """.formatted(port), StandardCharsets.UTF_8);

            final var mojo = new ServerStopMojo();
            mojo.serverConfig = config.toFile();
            mojo.stopUser = "cluster";
            mojo.stopPassword = "secret";
            mojo.shutdownTimeoutMs = 5_000;
            mojo.execute();

            assertTrue(
                    authHeader.get() != null && authHeader.get().startsWith("Basic "),
                    "basic auth must be sent when enable_auth is true, got: " + authHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotSendAuthWhenDisabled() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicReference<String> authHeader = new AtomicReference<>();
        final var port = server.getAddress().getPort();
        server.createContext("/shutdown", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            new Thread(() -> {
                        try {
                            Thread.sleep(100);
                            server.stop(0);
                        } catch (final InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    })
                    .start();
        });
        server.start();
        try {
            final var config = tmp.resolve("hop-server-noauth.xml");
            Files.writeString(config, """
                    <hop-server-config>
                      <hop-server>
                        <hostname>127.0.0.1</hostname>
                        <port>%d</port>
                        <enable_auth>false</enable_auth>
                      </hop-server>
                    </hop-server-config>
                    """.formatted(port), StandardCharsets.UTF_8);

            final var mojo = new ServerStopMojo();
            mojo.serverConfig = config.toFile();
            mojo.shutdownTimeoutMs = 5_000;
            mojo.execute();

            assertNull(authHeader.get(), "no Authorization header must be sent when auth is disabled");
        } finally {
            server.stop(0);
        }
    }
}
