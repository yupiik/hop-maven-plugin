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
package io.yupiik.hop.maven.mojo;

import io.yupiik.hop.maven.distribution.HopServerConfig;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Gracefully stops a running Hop server by sending the authenticated {@code /shutdown} request that
 * Hop Server exposes (it registers a JVM shutdown hook on it).
 * <p>
 * The target host/port and whether authentication is enabled are read from {@link #serverConfig}
 * ({@code hop-server.xml}, defaulting to the {@code server} goal workdir); {@link #stopHost}/
 * {@link #stopPort} are only a fallback when the config file is not present. After the shutdown
 * request the mojo waits up to {@link #shutdownTimeoutMs} for the port to free up.
 */
@Mojo(name = "server-stop", requiresProject = true, threadSafe = true)
public class ServerStopMojo extends BaseHopMojo {

    /**
     * Path to the {@code hop-server.xml} config of the running server (defaults to the exploded
     * distribution used by the {@code server} goal).
     */
    @Parameter(property = "hop.server.stop.serverConfig",
            defaultValue = "${project.build.directory}/hop-run/exploded/hop-server.xml")
    protected File serverConfig;

    /**
     * Fallback host (used only when {@link #serverConfig} is not readable). Never hardcoded.
     */
    @Parameter(property = "hop.server.stop.host")
    protected String stopHost;

    /**
     * Fallback port (used only when {@link #serverConfig} is not readable). Never hardcoded.
     */
    @Parameter(property = "hop.server.stop.port")
    protected Integer stopPort;

    /**
     * Username for the {@code /shutdown} basic auth (Hop default user is {@code cluster}).
     */
    @Parameter(property = "hop.server.stop.user", defaultValue = "cluster")
    protected String stopUser;

    /**
     * Password for the {@code /shutdown} basic auth.
     */
    @Parameter(property = "hop.server.stop.password")
    protected String stopPassword;

    /**
     * How long to wait for the server port to free up after the shutdown request.
     */
    @Parameter(property = "hop.server.stop.timeoutMs", defaultValue = "30000")
    protected long shutdownTimeoutMs;

    @java.lang.Override
    protected void process(final java.util.List<Path> files, final Path source,
                           final org.apache.hop.metadata.api.IHopMetadataProvider provider,
                           final org.apache.hop.core.variables.Variables variables) {
        // no-op
    }

    @java.lang.Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final HopServerConfig config;
        try {
            config = HopServerConfig.read(serverConfig == null ? null : serverConfig.toPath());
        } catch (final IOException | IllegalArgumentException e) {
            throw new MojoExecutionException("Unable to read the Hop server config " + serverConfig, e);
        }
        final var host = host(config);
        final var port = port(config);
        if (host == null || host.isBlank() || port <= 0) {
            throw new MojoFailureException("Cannot determine the Hop server host/port: read "
                    + serverConfig + " or set hop.server.stop.host/hop.server.stop.port");
        }
        final var auth = (config != null && config.enabledAuth())
                || (stopPassword != null && !stopPassword.isBlank());
        getLog().info("Stopping Hop server at " + host + ":" + port + (auth ? " (basic auth)" : ""));
        try {
            sendShutdown(host, port, auth);
            waitForPortToClose(host, port);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to stop the Hop server at " + host + ":" + port, e);
        }
        getLog().info("Hop server stopped.");
    }

    private String host(final HopServerConfig config) {
        if (config != null && config.hostname() != null && !config.hostname().isBlank()) {
            return config.hostname();
        }
        return stopHost;
    }

    private int port(final HopServerConfig config) {
        if (config != null && config.port() > 0) {
            return config.port();
        }
        return stopPort == null ? -1 : stopPort;
    }

    private void sendShutdown(final String host, final int port, final boolean auth) throws IOException {
        try (final var client = HttpClient.newHttpClient()) {
            final var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/shutdown"))
                    .timeout(Duration.ofSeconds(10))
                    .GET();
            if (auth) {
                final var token = Base64.getEncoder().encodeToString(
                        (stopUser + ":" + (stopPassword == null ? "" : stopPassword))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                request.header("Authorization", "Basic " + token);
            }
            try {
                final var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                getLog().debug("Shutdown response: HTTP " + response.statusCode());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Shutdown request rejected with HTTP " + response.statusCode()
                            + (auth ? " (check hop.server.stop.user/password)" : ""));
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final java.net.ConnectException e) {
                // the server may already have accepted the shutdown and closed the port
                getLog().debug("Connection refused while sending the shutdown request (server stopping): "
                        + e.getMessage());
            }
        }
    }

    private void waitForPortToClose(final String host, final int port) throws IOException {
        final var deadline = System.currentTimeMillis() + shutdownTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isPortOpen(host, port)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IOException("Hop server at " + host + ":" + port + " is still up after "
                + shutdownTimeoutMs + " ms; the shutdown may have failed");
    }

    private static boolean isPortOpen(final String host, final int port) {
        try (final var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }
}