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
package io.yupiik.hop.maven.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CoordinatesTest {

    @Test
    void parsesGav() {
        final var c = Coordinates.of("org.apache.hop:hop-core:2.19.0");
        assertEquals("org.apache.hop", c.groupId());
        assertEquals("hop-core", c.artifactId());
        assertEquals("2.19.0", c.version());
        assertEquals("jar", c.type());
        assertNull(c.classifier());
    }

    @Test
    void parsesWithTypeAndClassifier() {
        final var c = Coordinates.of("org.apache.hop:hop-assemblies-client:zip:linux:2.19.0");
        assertEquals("zip", c.type());
        assertEquals("linux", c.classifier());
        assertEquals("2.19.0", c.version());
    }

    @Test
    void serializesBackToAetherForm() {
        final var c = Coordinates.of("g:a:jar:class:1.0");
        assertEquals("g:a:jar:class:1.0", c.coordinates());
        assertEquals("g:a:jar:1.0", Coordinates.of("g:a:1.0").coordinates());
    }

    @Test
    void createsArtifactWithDefaults() {
        final var c = Coordinates.of("g:a:1.0");
        final var artifact = c.toArtifact("zip", "win");
        assertEquals("zip", artifact.getExtension());
        assertEquals("win", artifact.getClassifier());
        assertEquals("1.0", artifact.getVersion());
    }

    @Test
    void rejectsBlankCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> Coordinates.of("   "));
        assertThrows(IllegalArgumentException.class, () -> Coordinates.of(""));
    }
}
