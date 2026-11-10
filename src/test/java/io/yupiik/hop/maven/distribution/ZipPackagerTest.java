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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipPackagerTest {

    @TempDir
    Path tmp;

    @Test
    void packagesTreeUnderRootFolder() throws Exception {
        final var source = tmp.resolve("hop");
        Files.createDirectories(source.resolve("lib"));
        Files.writeString(source.resolve("lib/x.jar"), "x");
        Files.writeString(source.resolve("hop-run.sh"), "#! /bin/bash");

        final var zip = tmp.resolve("out.zip");
        ZipPackager.zip(source, zip, source.getFileName().toString());

        try (final var archive = new ZipFile(zip.toFile())) {
            boolean root = false, lib = false, script = false;
            final var entries = archive.entries();
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                if ("hop/".equals(entry.getName())) {
                    root = true;
                }
                if ("hop/lib/x.jar".equals(entry.getName())) {
                    lib = true;
                }
                if ("hop/hop-run.sh".equals(entry.getName())) {
                    script = true;
                }
            }
            assertTrue(root, "root folder must be created");
            assertTrue(lib, "file must be nested under the root");
            assertTrue(script, "script must be nested under the root");
        }
    }

    @Test
    void packagesWithoutRootWhenNameBlank() throws Exception {
        final var source = tmp.resolve("flat");
        Files.createDirectories(source);
        Files.writeString(source.resolve("a.txt"), "a");
        final var zip = tmp.resolve("flat.zip");
        ZipPackager.zip(source, zip, "");

        try (final var archive = new ZipFile(zip.toFile())) {
            var found = false;
            final var entries = archive.entries();
            while (entries.hasMoreElements()) {
                if ("a.txt".equals(entries.nextElement().getName())) {
                    found = true;
                }
            }
            assertTrue(found, "file must sit at the archive root when no root folder is requested");
        }
    }
}
