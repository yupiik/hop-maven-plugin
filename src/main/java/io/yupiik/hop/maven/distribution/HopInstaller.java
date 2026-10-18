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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Installs a resolved Hop plugin (transform, action, engine, tech, ...) into the canonical
 * {@code plugins/<category>/<artifactId>/} layout of an Apache Hop distribution.
 * <p>
 * The plugin jar itself goes at the folder root and its transitive dependencies under a {@code lib/}
 * sub-folder, mirroring how Hop bundles its own plugins. The category is derived from the artifactId
 * prefix (e.g. {@code hop-transform-*} -> {@code transforms}, {@code hop-action-*} -> {@code actions}).
 */
public final class HopInstaller {

    private HopInstaller() {
        // utility class
    }

    private static final String[] CATEGORY_MAPPING = {
            "hop-transform-", "transforms",
            "hop-action-", "actions",
            "hop-engines-", "engines",
            "hop-tech-", "tech",
            "hop-databases-", "databases",
            "hop-misc-", "misc"
    };

    /**
     * @return the {@code plugins/<category>[</artifactId>]} target folder for the given artifactId.
     */
    private static Path pluginFolder(final Path pluginsRoot, final String artifactId, final String categoryOverride) {
        final var category = (categoryOverride != null && !categoryOverride.isBlank())
                ? categoryOverride
                : category(artifactId);
        return pluginsRoot.resolve(category).resolve(artifactId);
    }

    private static String category(final String artifactId) {
        final var lower = artifactId == null ? "" : artifactId.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CATEGORY_MAPPING.length; i += 2) {
            if (lower.startsWith(CATEGORY_MAPPING[i])) {
                return CATEGORY_MAPPING[i + 1];
            }
        }
        return "misc";
    }

    /**
     * Install one plugin jar plus its dependencies into the distribution {@code plugins} tree.
     *
     * @param pluginsRoot        the distribution {@code hop/plugins} folder.
     * @param artifactId         the plugin artifactId.
     * @param pluginJar         the resolved plugin jar.
     * @param dependencies       resolved transitive dependency jars (may be empty); stored under {@code lib/}.
     * @param categoryOverride   optional explicit category, otherwise derived from the artifactId.
     */
    public static Path installPlugin(final Path pluginsRoot, final String artifactId, final Path pluginJar,
                                     final List<Path> dependencies, final String categoryOverride) throws IOException {
        final var folder = pluginFolder(pluginsRoot, artifactId, categoryOverride);
        Files.createDirectories(folder);
        final var targetJar = folder.resolve(pluginJar.getFileName().toString());
        Files.copy(pluginJar, targetJar, StandardCopyOption.REPLACE_EXISTING);
        if (dependencies != null && !dependencies.isEmpty()) {
            final var lib = folder.resolve("lib");
            Files.createDirectories(lib);
            for (final var dep : dependencies) {
                if (dep.equals(pluginJar)) {
                    continue;
                }
                Files.copy(dep, lib.resolve(dep.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return folder;
    }
}