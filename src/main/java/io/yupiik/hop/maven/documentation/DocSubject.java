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
package io.yupiik.hop.maven.documentation;

import java.util.List;
import java.util.Optional;

/**
 * Value holder describing a single Apache Hop pipeline ({@code .hpl}) or workflow ({@code .hwf})
 * for documentation generation. Populated by {@link DocSubjectFactory} from both the loaded Hop
 * object model and the raw XML file (which carries the structured parameter table and step details
 * that the in-memory object does not always expose).
 */
public record DocSubject(
        String name,
        Kind kind,
        String relativePath,
        String description,
        String extendedDescription,
        List<Parameter> parameters,
        List<Step> steps,
        List<Hop> hops) {

    public enum Kind {
        PIPELINE,
        WORKFLOW;

        public String fileExtension() {
            return this == PIPELINE ? ".hpl" : ".hwf";
        }

        public String label() {
            return this == PIPELINE ? "pipeline" : "workflow";
        }
    }

    /** A named parameter declared by the pipeline/workflow (name + optional default + description). */
    public record Parameter(String name, String defaultValue, String description) {}

    /** A transform (pipeline) or action (workflow) step. */
    public record Step(String name, String type, String description, int copies) {}

    /** A directed hop between two steps, when the file describes a flow. */
    public record Hop(String from, String to, boolean enabled) {}

    public Optional<String> firstParameterValue(final String parameterName) {
        for (final var parameter : parameters) {
            if (parameter.name().equals(parameterName)) {
                return Optional.ofNullable(parameter.defaultValue());
            }
        }
        return Optional.empty();
    }
}
