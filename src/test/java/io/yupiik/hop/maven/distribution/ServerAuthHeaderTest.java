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
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServerAuthHeaderTest {

    @Test
    void noCredentialsWhenNothingConfigured() {
        final var header = ServerAuthHeader.forUrl(
                URI.create("https://downloads.apache.org/hop/2.19.0/x.zip"), null, null, null, null, null);
        assertNull(header);
    }

    @Test
    void explicitBasicAuth() {
        final var header = ServerAuthHeader.forUrl(
                URI.create("https://host/repo.zip"), null, "user", "pass", null, null);
        assertEquals("Authorization", header.name());
        final var expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, header.value());
    }

    @Test
    void bearerTokenUsesUsernameAsHeaderName() {
        final var header = ServerAuthHeader.forUrl(
                URI.create("https://host/repo.zip"), null, "X-Custom", "Bearer token123", null, null);
        assertEquals("X-Custom", header.name());
        assertEquals("Bearer token123", header.value());
    }

    @Test
    void bearerTokenDefaultsHeaderName() {
        final var header = ServerAuthHeader.forUrl(
                URI.create("https://host/repo.zip"), null, null, "Bearer abc", null, null);
        assertEquals("Authorization", header.name());
        assertEquals("Bearer abc", header.value());
    }

    @Test
    void readsCredentialsFromSettingsServerByHost() {
        final var settings = new Settings();
        final var server = new Server();
        server.setId("downloads.apache.org");
        server.setUsername("svc");
        server.setPassword("secret");
        settings.setServers(List.of(server));

        final var header = ServerAuthHeader.forUrl(
                URI.create("https://downloads.apache.org/hop/x.zip"), settings, null, null, null, null);
        assertEquals("Authorization", header.name());
        final var expected = "Basic " + Base64.getEncoder().encodeToString("svc:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, header.value());
    }

    @Test
    void deciphersEncryptedPasswordWhenAProviderExists() {
        final var settings = new Settings();
        final var server = new Server();
        server.setId("repo.example.com");
        server.setUsername("svc");
        server.setPassword("${ENC_ENCRYPTED}");
        settings.setServers(List.of(server));

        final var header = ServerAuthHeader.forUrl(
                URI.create("https://repo.example.com/x.zip"), settings, null, null, null, p -> "decoded");
        assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:decoded".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                header.value());
    }
}