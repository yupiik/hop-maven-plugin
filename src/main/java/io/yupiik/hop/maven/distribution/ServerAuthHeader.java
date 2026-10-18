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
package io.yupiik.hop.maven.distribution;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.apache.maven.settings.Settings;

/**
 * Builds an HTTP authorization header for a download URL based on the Maven {@code settings.xml}
 * servers and/or explicit credentials.
 * <p>
 * Rules:
 * <ul>
 *   <li>When the URL hostname matches a configured Maven {@code server} (by id or hostname), its
 *       {@code username} / {@code password} are used. The password is deciphered when it looks
 *       encrypted ({@code ${...}}) and a decryptor is provided.</li>
 *   <li>Otherwise explicit {@code username}/{@code password} parameters are used.</li>
 *   <li>If the password starts with {@code Bearer} the credentials become a bearer token: the
 *       username is the header name (default {@code Authorization}) and the password the value.</li>
 *   <li>Otherwise HTTP Basic auth is used.</li>
 * </ul>
 */
public final class ServerAuthHeader {

    public static final String BEARER_PREFIX = "Bearer";

    private ServerAuthHeader() {
        // utility class
    }

    /**
     * @param uri            the target URL.
     * @param settings       the Maven settings (may be {@code null}).
     * @param username       explicit username (used only when no settings server matches).
     * @param password       explicit password.
     * @param headerName     default header name for the bearer case, defaults to {@code Authorization}.
     * @param decipher       optional password decipherer (e.g. from {@code MavenSettingsDecryptor}).
     * @return the header name/value to set for the download, or {@code null} when no credentials.
     */
    public static Header forUrl(final URI uri, final Settings settings,
                                final String username, final String password,
                                final String headerName, final Function<String, String> decipher) {
        final var host = uri != null ? uri.getHost() : null;
        final var resolved = resolveFromSettings(host, settings, username, password, decipher);
        final var user = resolved == null ? username : resolved[0];
        final var pass = resolved == null ? password : resolved[1];
        if (pass == null || pass.isBlank()) {
            return user == null || user.isBlank() ? null : basic(user, "");
        }
        if (pass.startsWith(BEARER_PREFIX) && (pass.length() == BEARER_PREFIX.length()
                || Character.isWhitespace(pass.charAt(BEARER_PREFIX.length())))) {
            // a "Bearer ..." password means: the username is the header name and the whole password
            // string (already `Bearer ...`) is the value.
            final var name = user == null || user.isBlank()
                    ? (headerName == null || headerName.isBlank() ? "Authorization" : headerName)
                    : user;
            return new Header(name, pass);
        }
        return basic(user == null ? "" : user, pass);
    }

    private static Header basic(final String user, final String pass) {
        final var token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Header("Authorization", "Basic " + token);
    }

    private static String[] resolveFromSettings(final String host, final Settings settings,
                                                final String username, final String password,
                                                final Function<String, String> decipher) {
        if (settings == null || host == null || settings.getServers() == null) {
            return null;
        }
        for (final var server : settings.getServers()) {
            final var id = server.getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            // a server may be referenced either by its id matching the hostname, or via
            // ${hostname} prefixed ids; accept both.
            if (matchesHost(host, id) || matchesHost(host, "http://" + id)
                    || matchesHost(host, "https://" + id)
                    || id.equals(host)) {
                if (server.getUsername() != null && server.getUsername().isBlank()) {
                    continue; // a server without credentials is not eligible
                }
                return new String[]{server.getUsername(),
                        decipherOrRaw(server.getPassword(), decipher)};
            }
        }
        return null;
    }

    private static boolean matchesHost(final String host, final String candidate) {
        try {
            final var c = new URI(candidate).getHost();
            return c != null && c.equalsIgnoreCase(host);
        } catch (final Exception e) {
            return false;
        }
    }

    private static String decipherOrRaw(final String password, final Function<String, String> decipher) {
        final var trimmed = password == null ? "" : password.trim();
        final var encrypted = trimmed.startsWith("${") && trimmed.endsWith("}");
        if (encrypted && decipher != null) {
            try {
                final var deciphered = decipher.apply(trimmed);
                if (deciphered != null) {
                    return deciphered;
                }
            } catch (final RuntimeException ignored) {
                // fall through to raw value
            }
        }
        return password;
    }

    /**
     * A header name/value pair to send on the download request.
     */
    public record Header(String name, String value) {
    }
}