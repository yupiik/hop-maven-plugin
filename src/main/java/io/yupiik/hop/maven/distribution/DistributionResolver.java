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

import io.yupiik.hop.maven.resolve.ArtifactResolver;
import io.yupiik.hop.maven.resolve.Coordinates;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.DefaultProxySelector;

/**
 * Resolves the Apache Hop distribution archive (zip) either from an HTTP(S) URL or from a Maven
 * coordinate.
 * <p>
 * A string containing a {@code /} is treated as an HTTP(S) URL, with a {@code ${version}} placeholder
 * interpolated with the configured Hop version and optional Basic/Bearer credentials applied from
 * {@link ServerAuthHeader}. A string without any {@code /} is treated as a Maven coordinate
 * ({@code groupId:artifactId:version[:type[:classifier]]}) and resolved through the Maven resolver.
 * The resolved archive is copied into {@code workdir} for later unpacking.
 */
public class DistributionResolver {

    private final ArtifactResolver artifactResolver;
    private final Settings settings;
    private final String hopVersion;
    private final String username;
    private final String password;
    private final String authorizationHeaderName;
    private final Path workdir;
    private final Consumer<String> info;
    private final java.util.function.Function<String, String> decipher;
    private final boolean cacheDownload;
    private final Duration downloadTimeout;

    public DistributionResolver(
            final ArtifactResolver artifactResolver,
            final Settings settings,
            final String hopVersion,
            final String username,
            final String password,
            final String authorizationHeaderName,
            final Path workdir,
            final Consumer<String> info,
            final java.util.function.Function<String, String> decipher,
            final boolean cacheDownload,
            final Duration downloadTimeout) {
        this.artifactResolver = artifactResolver;
        this.settings = settings;
        this.hopVersion = hopVersion;
        this.username = username;
        this.password = password;
        this.authorizationHeaderName = authorizationHeaderName;
        this.workdir = workdir;
        this.info = info == null ? ignored -> {} : info;
        this.decipher = decipher;
        this.cacheDownload = cacheDownload;
        this.downloadTimeout = downloadTimeout == null ? Duration.ofMinutes(15) : downloadTimeout;
    }

    /**
     * @return whether {@code location} should be interpreted as an HTTP(S) URL (contains {@code ://}).
     */
    public static boolean isUrl(final String location) {
        return location != null && location.contains("://");
    }

    /**
     * Resolve the distribution to a local archive file placed under {@link #workdir}.
     *
     * @param location a URL (with optional {@code ${version}}) or a Maven coordinate.
     * @return the path of the downloaded/resolved archive.
     */
    public Path resolve(final String location) throws IOException {
        final var interpolated = interpolateVersion(location);
        if (isUrl(interpolated)) {
            return download(interpolated);
        }
        return resolveMaven(interpolated);
    }

    /**
     * @return the archive file name the {@code hopVersion} Http download would land on, before
     * downloading (used to pick a target folder name).
     */
    private String defaultZipName(final String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private Path download(final String url) throws IOException {
        // a distribution available in the local repository cache is reused without any HTTP request
        if (cacheDownload) {
            final var cacheCoordinates = cacheCoordinates(url);
            try {
                final var cached = artifactResolver.resolveLocalOnly(cacheCoordinates);
                info.accept("Using cached distribution " + cacheCoordinates.coordinates() + " at " + cached);
                return cached;
            } catch (final IllegalStateException miss) {
                info.accept("Distribution " + cacheCoordinates.coordinates() + " not cached, downloading " + url);
            }
        }
        Files.createDirectories(workdir);
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (final IllegalArgumentException e) {
            throw new IOException("Invalid distribution URL: " + url, e);
        }
        final var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IOException("Distribution URL must be http(s): " + url);
        }
        final var auth = ServerAuthHeader.forUrl(uri, settings, username, password, authorizationHeaderName, decipher);
        final var target = workdir.resolve(defaultZipName(url));
        info.accept("Downloading " + url + (auth != null ? " with credentials for " + uri.getHost() : ""));
        final var clientBuilder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30));
        configureProxy(clientBuilder, uri, auth);
        try (final var client = clientBuilder.build()) {
            final HttpResponse<Path> response;
            try {
                response = downloadResponse(client, uri, auth, target);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading " + url, e);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Unable to download " + url + ": HTTP " + response.statusCode());
            }
        }
        if (cacheDownload) {
            final var cacheCoordinates = cacheCoordinates(url);
            try {
                artifactResolver.install(cacheCoordinates, target);
                info.accept("Installed distribution " + cacheCoordinates.coordinates() + " into the local repository");
            } catch (final IllegalStateException e) {
                info.accept("Unable to cache distribution " + cacheCoordinates.coordinates() + ": " + e.getMessage());
            }
        }
        return target;
    }

    private HttpResponse<Path> downloadResponse(
            final HttpClient client, final URI uri, final ServerAuthHeader.Header auth, final Path target)
            throws IOException, InterruptedException {
        var current = uri;
        for (var redirects = 0; redirects < 5; redirects++) {
            final var request =
                    HttpRequest.newBuilder(current).timeout(downloadTimeout).GET();
            if (auth != null && sameOrigin(uri, current)) {
                request.header(auth.name(), auth.value());
            }
            final var response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofFile(
                            target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING));
            final var status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            final var location = response.headers().firstValue("Location");
            if (location.isEmpty()) {
                throw new IOException("Missing Location header in distribution redirect from " + current);
            }
            final URI next;
            try {
                next = current.resolve(location.get());
            } catch (final IllegalArgumentException e) {
                throw new IOException("Invalid distribution redirect from " + current, e);
            }
            if (!("http".equalsIgnoreCase(next.getScheme()) || "https".equalsIgnoreCase(next.getScheme()))
                    || ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme()))) {
                throw new IOException("Unsafe distribution redirect from " + current + " to " + next);
            }
            // A fresh request makes HttpClient reselect the proxy for the redirected host.
            current = next;
        }
        throw new IOException("Too many distribution redirects from " + uri);
    }

    private static boolean sameOrigin(final URI first, final URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(final URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void configureProxy(final HttpClient.Builder builder, final URI uri, final ServerAuthHeader.Header auth) {
        final var proxies = settings == null
                ? List.<org.apache.maven.settings.Proxy>of()
                : settings.getProxies().stream()
                        .filter(org.apache.maven.settings.Proxy::isActive)
                        .toList();
        if (proxies.isEmpty()) {
            return; // preserve the JDK's default proxy selection when Maven has no active proxy
        }
        final var selector = new DefaultProxySelector();
        for (final var proxy : proxies) {
            selector.add(
                    new org.eclipse.aether.repository.Proxy(proxy.getProtocol(), proxy.getHost(), proxy.getPort()),
                    proxy.getNonProxyHosts());
        }
        builder.proxy(new ProxySelector() {
            @Override
            public List<Proxy> select(final URI uri) {
                // Use Maven Resolver's protocol fallback and nonProxyHosts matching, also for redirects.
                final var proxy =
                        selector.getProxy(new RemoteRepository.Builder("download", "default", uri.toString()).build());
                return proxy == null
                        ? List.of(Proxy.NO_PROXY)
                        : List.of(new Proxy(
                                Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
            }

            @Override
            public void connectFailed(final URI uri, final SocketAddress address, final IOException failure) {
                // HttpClient reports the failure to the caller.
            }
        });
        if (proxies.stream().anyMatch(proxy -> proxy.getUsername() != null)) {
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() != RequestorType.PROXY) {
                        // JDK 21 suppresses explicit Authorization headers when an authenticator is set.
                        // Answer Basic server challenges only for the original download origin.
                        if (getRequestorType() == RequestorType.SERVER
                                && getRequestingURL() != null
                                && auth != null
                                && "Authorization".equalsIgnoreCase(auth.name())
                                && auth.value().startsWith("Basic ")
                                && "basic".equalsIgnoreCase(getRequestingScheme())
                                && sameOrigin(uri, URI.create(getRequestingURL().toString()))) {
                            final var credentials = new String(
                                    Base64.getDecoder().decode(auth.value().substring(6)),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            final var separator = credentials.indexOf(':');
                            return new PasswordAuthentication(
                                    credentials.substring(0, separator),
                                    credentials.substring(separator + 1).toCharArray());
                        }
                        return null;
                    }
                    for (final var proxy : proxies) {
                        if (proxy.getHost().equalsIgnoreCase(getRequestingHost())
                                && proxy.getPort() == getRequestingPort()
                                && proxy.getUsername() != null) {
                            final var secret = proxy.getPassword();
                            final var clear = secret == null ? "" : decipher == null ? secret : decipher.apply(secret);
                            return new PasswordAuthentication(proxy.getUsername(), clear.toCharArray());
                        }
                    }
                    return null;
                }
            });
        }
    }

    /**
     * @param url the (interpolated) distribution {@code http(s)://} URL.
     * @return the synthetic local-repository cache coordinates for that URL:
     *         {@code io.yupiik.hop.cache:<filename>:zip:<sha256(url)[..16]>:<version>}. A changed URL maps to a
     *         different classifier, so the cache is naturally busted when the URL changes.
     */
    static Coordinates cacheCoordinates(final String url) {
        final var filename = url.substring(url.lastIndexOf('/') + 1);
        final var withoutExt =
                filename.endsWith(".zip") ? filename.substring(0, filename.length() - ".zip".length()) : filename;
        final String version;
        if (withoutExt.startsWith("apache-hop-client-")) {
            version = withoutExt.substring("apache-hop-client-".length());
        } else {
            final var lastDash = withoutExt.lastIndexOf('-');
            version = lastDash < 0 ? withoutExt : withoutExt.substring(lastDash + 1);
        }
        final var classifier = sha256(url).substring(0, 16);
        return new Coordinates("io.yupiik.hop.cache", filename, version, "zip", classifier);
    }

    private static String sha256(final String value) {
        try {
            final var digest = java.security.MessageDigest.getInstance("SHA-256");
            final var hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final var hex = new StringBuilder(hash.length * 2);
            for (final var b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Path resolveMaven(final String coordinate) {
        final var parsed = Coordinates.of(coordinate);
        info.accept("Resolving distribution " + parsed.coordinates() + " from the Maven repositories");
        return artifactResolver.resolve(parsed);
    }

    /**
     * Replace {@code ${version}} with the configured Hop version.
     */
    private String interpolateVersion(final String location) {
        if (location == null) {
            return null;
        }
        final var version = hopVersion == null ? "" : hopVersion;
        // ${hop.version} survives Maven's interpolation (undefined property); ${version} supports
        // values passed raw via -D on the CLI (Maven would otherwise resolve it to the project version).
        return location.replace("${hop.version}", version).replace("${version}", version);
    }
}
