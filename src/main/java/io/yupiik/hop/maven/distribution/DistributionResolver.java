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

import io.yupiik.hop.maven.resolve.ArtifactResolver;
import io.yupiik.hop.maven.resolve.Coordinates;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import org.apache.maven.settings.Settings;

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
        try (final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            final var request =
                    HttpRequest.newBuilder(uri).timeout(downloadTimeout).GET();
            if (auth != null) {
                request.header(auth.name(), auth.value());
            }
            final HttpResponse<Path> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofFile(target));
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
