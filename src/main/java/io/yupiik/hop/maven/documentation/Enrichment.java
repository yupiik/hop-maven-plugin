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
 * Manual documentation content attached to a pipeline/workflow, provided in the mojo configuration.
 * <p>
 * An enrichment is matched to a diagram by {@link #name} (the diagram base name, e.g.
 * {@code T_SYNC_LOAD}) and optionally {@link #kind} (PIPELINE / WORKFLOW). It supplies the human
 * prose that cannot be derived from the HOP file: the {@link #overview} ("role/objectif"), the
 * {@link #script} (launch/run snippet), extra {@link #extraSections}, the one-line {@link #indexRole}
 * shown in the generated index, the {@link #indexOrder} and an optional {@link #diagramReference}
 * override.
 * <p>
 * When the enrichment comes from a documentation file (see
 * {@code io.yupiik.hop.maven.documentation.DocFileLoader}) the original body order is preserved in
 * {@link #blocks}. The {@link #overview}, {@link #script} and {@link #extraSections} are then derived
 * views of that body kept for backward compatibility; {@link DocSections}
 * {@code io.yupiik.hop.maven.documentation.DocVisitor} uses {@link #blocks} to render the narrative
 * thread in the order it was written, resolving the built-in section anchors
 * ({@code :configuration}, {@code :steps}, {@code :flow}, {@code :diagram}).
 */
public class Enrichment {

    private String name;
    private String kind;
    private String overview;
    private String script;
    private Integer indexOrder;
    private String indexRole;
    private String diagramReference;
    private List<ExtraSection> extraSections = List.of();
    private List<Block> blocks;

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(final String kind) {
        this.kind = kind;
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(final String overview) {
        this.overview = overview;
    }

    public String getScript() {
        return script;
    }

    public void setScript(final String script) {
        this.script = script;
    }

    public Integer getIndexOrder() {
        return indexOrder;
    }

    public void setIndexOrder(final Integer indexOrder) {
        this.indexOrder = indexOrder;
    }

    public String getIndexRole() {
        return indexRole;
    }

    public void setIndexRole(final String indexRole) {
        this.indexRole = indexRole;
    }

    public String getDiagramReference() {
        return diagramReference;
    }

    public void setDiagramReference(final String diagramReference) {
        this.diagramReference = diagramReference;
    }

    public List<ExtraSection> getExtraSections() {
        return extraSections;
    }

    public void setExtraSections(final List<ExtraSection> extraSections) {
        this.extraSections = extraSections == null ? List.of() : extraSections;
    }

    /**
     * The ordered body of the documentation file, when the enrichment was loaded from
     * {@code src/main/hop/documentation/<name>.adoc}. {@code null} for inline {@code <enrichments>}.
     * When present, the section renderer follows this order (and resolves the built-in anchors) instead
     * of the canonical {@link #getOverview()}/{@link #getScript()}/{@link #getExtraSections()} order.
     */
    public List<Block> getBlocks() {
        return blocks;
    }

    public void setBlocks(final List<Block> blocks) {
        this.blocks = blocks;
    }

    /**
     * An ordered piece of a documentation file body, preserving the source narrative order. A subset
     * (see {@code DocFileLoader}) can reference the generated sections through {@link Generated} anchors.
     */
    public sealed interface Block permits Block.Overview, Block.Script, Block.Section, Block.Generated {

        /** The leading prose rendered as the page "overview" (no heading/role is added by the author). */
        record Overview(String text) implements Block {}

        /** A launch/run script plus any prose trailing it in the same section. */
        record Script(String script, String trailingProse) implements Block {}

        /** A free-form section (nested {@code ===} headings and code blocks kept verbatim). */
        record Section(String title, String content) implements Block {}

        /** Positions one of the generated sections (configuration/steps/flow/diagram) in the thread. */
        record Generated(String section) implements Block {}
    }

    /** A user-supplied free-form section appended to the generated page. */
    public static class ExtraSection {
        private String title;
        private String content;

        public String getTitle() {
            return title;
        }

        public void setTitle(final String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(final String content) {
            this.content = content;
        }
    }
}
