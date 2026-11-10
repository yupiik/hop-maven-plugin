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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureUnzipTest {

    @TempDir
    Path tmp;

    @Test
    void extractsNormally() throws Exception {
        final var zip = tmp.resolve("a.zip");
        buildZip(zip, "hop/lib/core/x.jar", "hop/bin/run.sh", "hop/config/project-config.json");
        final var target = tmp.resolve("out");
        SecureUnzip.unzip(zip, target, true);

        assertTrue(Files.isRegularFile(target.resolve("lib/core/x.jar")), "root folder must be stripped");
        assertTrue(Files.isRegularFile(target.resolve("bin/run.sh")));
        assertTrue(Files.isRegularFile(target.resolve("config/project-config.json")));
        assertTrue(target.resolve("bin/run.sh").toFile().canExecute(), ".sh must be executable");
    }

    @Test
    void rejectsZipSlipTraversal() throws Exception {
        final var zip = tmp.resolve("evil.zip");
        buildZip(zip, "/../escape.txt");
        final var target = tmp.resolve("out-evil");
        assertThrows(IOException.class, () -> SecureUnzip.unzip(zip, target, true));
        assertFalse(Files.exists(target.getParent().resolve("escape.txt")), "no file may escape the target");
    }

    @Test
    void rejectsAbsolutePathEntries() throws Exception {
        final var zip = tmp.resolve("abs.zip");
        buildZip(zip, "/etc/passwd");
        final var target = tmp.resolve("out-abs");
        assertThrows(IOException.class, () -> SecureUnzip.unzip(zip, target, false));
        assertFalse(Files.exists(target.resolve("etc/passwd")));
    }

    @Test
    void keepsRootFolderWhenRequested() throws Exception {
        final var zip = tmp.resolve("b.zip");
        buildZip(zip, "hop/hop-run.sh", "hop/lib/x.jar");
        final var target = tmp.resolve("out-root");
        SecureUnzip.unzip(zip, target, false);
        assertTrue(Files.isRegularFile(target.resolve("hop/hop-run.sh")), "root folder must be kept when skip=false");
    }

    private void buildZip(final Path zip, final String... entries) throws IOException {
        try (final var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (final var entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(("content-" + entry).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
