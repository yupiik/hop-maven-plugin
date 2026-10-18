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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Traversal-safe zip extraction using only the JDK ({@link ZipFile} / {@link ZipEntry}), so the
 * plugin does not need commons-compress.
 * <p>
 * Every entry is canonicalized against the target directory and rejected when it would escape it
 * (absolute paths, {@code ..}, drive letters, symbolic-link or symlink-like prefixes), preventing a
 * "zip slip" attack. Optionally the leading root folder of each entry (e.g. the {@code hop/} folder
 * of an Apache Hop distribution) is stripped. Shell scripts get the executable bit set.
 */
public final class SecureUnzip {

    private SecureUnzip() {
        // utility class
    }

    /**
     * Extract {@code zip} into {@code target}. When {@code skipRootFolder} is true the first path
     * segment of each entry is dropped (the canonical layout of the Apache Hop distribution zip).
     *
     * @param zip            the archive to extract.
     * @param target         the destination directory (created if missing).
     * @param skipRootFolder whether to strip the leading root folder segment.
     * @return the number of extracted file entries.
     */
    public static int unzip(final Path zip, final Path target, final boolean skipRootFolder) throws IOException {
        Files.createDirectories(target);
        final var canonicalTarget = target.toAbsolutePath().normalize();
        final int[] count = {0};
        try (final ZipFile archive = new ZipFile(zip.toFile())) {
            final var entries = archive.entries();
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                var name = entry.getName();
                if (name.isBlank()) {
                    continue;
                }
                name = name.replace('\\', '/');
                if (skipRootFolder) {
                    final var slash = name.indexOf('/');
                    if (slash < 0) {
                        continue; // a top-level file with no root folder, nothing to strip parent for
                    }
                    name = name.substring(slash + 1);
                    if (name.isEmpty()) {
                        continue;
                    }
                }
                extract(archive, entry, name, canonicalTarget, count);
            }
        }
        return count[0];
    }

    private static void extract(final ZipFile archive, final ZipEntry entry, final String name,
                                final Path canonicalTarget, final int[] count) throws IOException {
        final var dest = resolveEntry(canonicalTarget, name);
        if (entry.isDirectory()) {
            Files.createDirectories(dest);
            return;
        }
        final var parent = dest.getParent();
        Files.createDirectories(parent);
        try (final InputStream in = archive.getInputStream(entry)) {
            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (name.endsWith(".sh")) {
            setExecutable(dest);
        }
        count[0]++;
    }

    private static Path resolveEntry(final Path canonicalTarget, final String name) throws IOException {
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new IOException("Zip entry uses an absolute path: " + name);
        }
        final var resolved = canonicalTarget.resolve(name).normalize();
        if (!resolved.startsWith(canonicalTarget)) {
            throw new IOException("Zip entry escapes the destination directory: " + name);
        }
        return resolved;
    }

    private static void setExecutable(final Path file) {
        try {
            file.toFile().setExecutable(true, false);
        } catch (final RuntimeException ignored) {
            // best effort
        }
    }

    /**
     * @return the file name of {@code zip} with the trailing {@code .zip} stripped, lowercase.
     */
    public static String baseName(final String zipName) {
        final var name = zipName == null ? "" : zipName;
        final var slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        final var base = slash >= 0 ? name.substring(slash + 1) : name;
        final var lower = base.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") ? base.substring(0, base.length() - 4) : base;
    }
}