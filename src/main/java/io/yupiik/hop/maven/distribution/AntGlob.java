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

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ant-style glob matching shared by the diagram {@code includes}/{@code excludes} and the
 * distribution {@code customizations} deletions.
 * <p>
 * {@code *} matches any sequence of non-{@code /} characters, {@code **} matches any sequence
 * (including {@code /}) and {@code ?} matches a single non-{@code /} character. Matching uses
 * {@code find()} semantics against the whole (relative) path, i.e. the pattern may match any
 * sub-part of the path.
 * <p>
 * Patterns are compiled once via {@link #compile(List)} and the resulting {@link Compiled}
 * instance is meant to be reused across many paths (typically one walk of the distribution tree),
 * so {@link Pattern#compile} is not paid per file.
 */
public final class AntGlob {

    private AntGlob() {
        // no-op: utility class
    }

    /**
     * Compile the patterns once so the regexes are not rebuilt per matched path.
     *
     * @param patterns the ant-style patterns, {@code /}-relative and optionally with {@code \}
     *                 separators (null and blank entries are ignored).
     * @return an immutable matcher over the compiled patterns.
     */
    public static Compiled compile(final List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return new Compiled(List.of());
        }
        return new Compiled(patterns.stream()
                .filter(Objects::nonNull)
                .filter(pattern -> !pattern.isBlank())
                .map(pattern -> Pattern.compile(antToRegex(pattern.replace('\\', '/'))))
                .toList());
    }

    /**
     * Compiled patterns, immutable and reusable across many paths.
     */
    public record Compiled(List<Pattern> patterns) {

        public boolean isEmpty() {
            return patterns.isEmpty();
        }

        /**
         * @param relPath the relative path to match.
         * @return whether any compiled pattern matches (anywhere in) the path.
         */
        public boolean matches(final String relPath) {
            for (final Pattern pattern : patterns) {
                if (pattern.matcher(relPath).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String antToRegex(final String pattern) {
        final var out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            final var c = pattern.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        out.append(".*");
                        i++;
                    } else {
                        out.append("[^/]*");
                    }
                }
                case '?' -> out.append("[^/]");
                case '.' -> out.append("\\.");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
