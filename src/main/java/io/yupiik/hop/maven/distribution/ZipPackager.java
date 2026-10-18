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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plain-JDK zip packaging of a folder tree, without any external archive library.
 * <p>
 * Files are stored with relative paths under {@code rootFolderName} (or directly when
 * {@code withRootFolder} is false) so a packaged Hop distribution keeps its canonical {@code hop/}
 * root, exactly like the upstream {@code apache-hop-client-*.zip}.
 */
public final class ZipPackager {

    private ZipPackager() {
        // utility class
    }

    /**
     * Zip the whole {@code source} tree into {@code target}.
     *
     * @param source        the folder to archive.
     * @param target        the output zip file.
     * @param rootFolderName when non-blank the tree is nested under this folder entry (preserves the
     *                       canonical Hop {@code hop/} root).
     * @return the number of files archived.
     */
    public static int zip(final Path source, final Path target, final String rootFolderName) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Not a directory to archive: " + source);
        }
        final var canonicalSource = source.toAbsolutePath().normalize();
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        final var root = rootFolderName == null || rootFolderName.isBlank() ? "" : rootFolderName;
        final int[] count = {0};
        try (final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            if (!root.isEmpty()) {
                zip.putNextEntry(new ZipEntry(root + "/"));
                zip.closeEntry();
            }
            Files.walkFileTree(canonicalSource, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
                        throws IOException {
                    if (dir.equals(canonicalSource)) {
                        return FileVisitResult.CONTINUE;
                    }
                    final var entryName = entryName(canonicalSource, dir, root, true);
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    final var entryName = entryName(canonicalSource, file, root, false);
                    zip.putNextEntry(new ZipEntry(entryName));
                    try (final InputStream in = Files.newInputStream(file)) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                    count[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return count[0];
    }

    private static String entryName(final Path base, final Path path, final String root, final boolean directory) {
        final var rel = base.relativize(path).toString().replace('\\', '/');
        final var withRoot = root.isEmpty() ? rel : (root + "/" + rel);
        return directory && !withRoot.endsWith("/") ? withRoot + "/" : withRoot;
    }
}