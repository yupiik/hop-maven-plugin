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
package io.yupiik.hop.maven.documentation;

import java.util.List;

/**
 * The data needed to render the generated index page and the {@code llms.txt} file: the ordered
 * list of documented artefacts plus the user-facing index header.
 */
public record IndexModel(String title, String description, String icon, Integer order, List<Entry> entries) {

    /** A single documented artefact as it appears in the index. */
    public record Entry(
            String name, DocSubject.Kind kind, String relativePath, String description, String role, Integer order) {

        public String baseName() {
            final var dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        public String pageName() {
            return baseName();
        }
    }

    public List<Entry> workflows() {
        return entries.stream()
                .filter(e -> e.kind() == DocSubject.Kind.WORKFLOW)
                .toList();
    }

    public List<Entry> pipelines() {
        return entries.stream()
                .filter(e -> e.kind() == DocSubject.Kind.PIPELINE)
                .toList();
    }
}
