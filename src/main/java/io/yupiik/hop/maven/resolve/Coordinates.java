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
package io.yupiik.hop.maven.resolve;

import java.util.List;
import org.eclipse.aether.artifact.DefaultArtifact;

/**
 * Parsed Maven coordinates following the aether {@link DefaultArtifact} constructor format
 * {@code groupId:artifactId:extension[:classifier]:version} (the version is always the last field).
 * <p>
 * Parsing is delegated to {@link DefaultArtifact} so the exact same semantics as the resolver apply.
 * The parts are exposed so a caller can build an artifact without re-parsing.
 */
public record Coordinates(String groupId, String artifactId, String version, String type, String classifier) {

    /**
     * @param value a {@code groupId:artifactId:extension[:classifier]:version} string.
     */
    public static Coordinates of(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Empty artifact coordinates");
        }
        final var artifact = new DefaultArtifact(value.trim());
        return new Coordinates(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                blankToNull(artifact.getExtension()),
                blankToNull(artifact.getClassifier()));
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @return the aether {@code groupId:artifactId:extension[:classifier]:version} form.
     */
    public String coordinates() {
        final var builder = new StringBuilder()
                .append(groupId)
                .append(':')
                .append(artifactId)
                .append(':')
                .append(type == null || type.isBlank() ? "jar" : type);
        if (classifier != null && !classifier.isBlank()) {
            builder.append(':').append(classifier);
        }
        return builder.append(':').append(version != null ? version : "").toString();
    }

    /**
     * @return an aether artifact for these coordinates, using {@code defaultType}/{@code defaultClassifier}
     * when the type is the implicit {@code jar} or no classifier is present.
     */
    public DefaultArtifact toArtifact(final String defaultType, final String defaultClassifier) {
        return new DefaultArtifact(toCoordinates(defaultType, defaultClassifier));
    }

    /**
     * @return the aether coordinate string with the given type/classifier defaults.
     */
    public String toCoordinates(final String defaultType, final String defaultClassifier) {
        final var implicitType = type == null || type.isBlank() || "jar".equals(type);
        final var t = implicitType && defaultType != null && !defaultType.isBlank() ? defaultType : type;
        final var c = (classifier == null || classifier.isBlank()) ? defaultClassifier : classifier;
        final var builder = new StringBuilder()
                .append(groupId)
                .append(':')
                .append(artifactId)
                .append(':')
                .append(t == null ? "jar" : t);
        if (c != null && !c.isBlank()) {
            builder.append(':').append(c);
        }
        return builder.append(':').append(version != null ? version : "").toString();
    }

    @Override
    public String toString() {
        return coordinates();
    }

    /**
     * Convenience for parsing a list of coordinate strings into a list of {@link Coordinates}.
     */
    public static List<Coordinates> parseAll(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Coordinates::of).toList();
    }
}
