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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopInstallerTest {

    @TempDir
    Path tmp;

    @Test
    void installsTransformIntoTransformsCategory() throws Exception {
        final var jar = tmp.resolve("hop-transform-fake-1.0.jar");
        Files.writeString(jar, "jar-bytes");
        final var dep = tmp.resolve("dep-1.0.jar");
        Files.writeString(dep, "dep-bytes");

        final var plugins = tmp.resolve("plugins");
        final var folder = HopInstaller.installPlugin(plugins, "hop-transform-fake", jar, List.of(dep), null);

        assertTrue(folder.startsWith(plugins.resolve("transforms")), "derived category must be transforms");
        assertTrue(Files.exists(folder.resolve("hop-transform-fake-1.0.jar")));
        assertTrue(Files.exists(folder.resolve("lib/dep-1.0.jar")), "dependency goes to lib/");
    }

    @Test
    void installsMarketplacePluginUnderMiscWhenNoKnownPrefix() throws Exception {
        final var jar = tmp.resolve("my-plugin-1.0.jar");
        Files.writeString(jar, "bytes");

        final var plugins = tmp.resolve("plugins");
        final var folder = HopInstaller.installPlugin(plugins, "my-plugintype", jar, List.of(), null);

        assertTrue(folder.startsWith(plugins.resolve("misc")), "unknown prefix -> misc");
    }

    @Test
    void honorsExplicitCategory() throws Exception {
        final var jar = tmp.resolve("hop-action-x-1.0.jar");
        Files.writeString(jar, "bytes");

        final var plugins = tmp.resolve("plugins");
        final var folder = HopInstaller.installPlugin(plugins, "hop-action-x", jar, List.of(), "actions");

        assertTrue(folder.startsWith(plugins.resolve("actions")));
    }
}
