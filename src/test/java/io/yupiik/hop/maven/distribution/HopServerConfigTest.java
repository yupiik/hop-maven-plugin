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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopServerConfigTest {

    @TempDir
    Path tmp;

    @Test
    void readsConfigWhenFileExists() throws Exception {
        final var file = tmp.resolve("hop-server.xml");
        Files.writeString(file, """
                <hop-server-config>
                  <hop-server>
                    <name>server-8181</name>
                    <hostname>localhost</hostname>
                    <port>8181</port>
                    <enable_auth>true</enable_auth>
                  </hop-server>
                </hop-server-config>
                """, StandardCharsets.UTF_8);
        final var config = HopServerConfig.read(file);
        assertEquals("localhost", config.hostname());
        assertEquals(8181, config.port());
        assertTrue(config.enabledAuth());
    }

    @Test
    void supportsAuthDisabled() throws Exception {
        final var file = tmp.resolve("noauth.xml");
        Files.writeString(file, """
                <hop-server-config>
                  <hop-server>
                    <hostname>0.0.0.0</hostname>
                    <port>9100</port>
                    <enable_auth>false</enable_auth>
                  </hop-server>
                </hop-server-config>
                """, StandardCharsets.UTF_8);
        final var config = HopServerConfig.read(file);
        assertEquals(9100, config.port());
        assertFalse(config.enabledAuth());
    }

    @Test
    void returnsNullWhenFileMissing() throws Exception {
        assertNull(HopServerConfig.read(tmp.resolve("missing.xml")));
    }

    @Test
    void complainsWhenFileMalformed() throws Exception {
        final var file = tmp.resolve("bad.xml");
        Files.writeString(file, "not xml at all", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> HopServerConfig.read(file));
    }
}