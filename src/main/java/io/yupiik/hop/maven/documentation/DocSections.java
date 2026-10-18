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
package io.yupiik.hop.maven.documentation;

import io.yupiik.hop.maven.documentation.DocSubject.Hop;
import io.yupiik.hop.maven.documentation.DocSubject.Parameter;
import io.yupiik.hop.maven.documentation.DocSubject.Step;
import java.util.List;

/**
 * The ordered, format-agnostic documentation sections for a single pipeline/workflow, produced by
 * {@link DocVisitor} and serialized by the concrete renderers (Asciidoc, Markdown, llms.txt).
 */
public record DocSections(
        String title,
        List<Section> sections) {

    /** A single documentation section. Each variant carries enough data for any renderer. */
    public sealed interface Section permits OverviewSection, ConfigurationSection, StepsSection,
            FlowSection, DiagramSection, ScriptSection, ExtraSection {
    }

    /** Free-form prose (the "role"/"objectif" section). Title may be null to use the renderer default. */
    public record OverviewSection(String title, String text) implements Section {
    }

    /** The declared parameters, rendered as a table. */
    public record ConfigurationSection(List<Parameter> parameters) implements Section {
    }

    /** The pipeline transforms or workflow actions, rendered as a table or list. */
    public record StepsSection(List<Step> steps) implements Section {
    }

    /** The hop flow (from/to edges). */
    public record FlowSection(List<Hop> hops) implements Section {
    }

    /** A reference to the rendered diagram image. */
    public record DiagramSection(String reference) implements Section {
    }

    /** A launch/run script, rendered as a code block. */
    public record ScriptSection(String title, String script) implements Section {
    }

    /** A user provided extra section (arbitrary content). */
    public record ExtraSection(String title, String content) implements Section {
    }
}