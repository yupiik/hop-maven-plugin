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

import io.yupiik.hop.maven.documentation.DocSections.ConfigurationSection;
import io.yupiik.hop.maven.documentation.DocSections.DiagramSection;
import io.yupiik.hop.maven.documentation.DocSections.ExtraSection;
import io.yupiik.hop.maven.documentation.DocSections.FlowSection;
import io.yupiik.hop.maven.documentation.DocSections.OverviewSection;
import io.yupiik.hop.maven.documentation.DocSections.ProseSection;
import io.yupiik.hop.maven.documentation.DocSections.ScriptSection;
import io.yupiik.hop.maven.documentation.DocSections.Section;
import io.yupiik.hop.maven.documentation.DocSections.StepsSection;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared, format-agnostic documentation extraction for a single pipeline/workflow.
 * <p>
 * The neutral section model is built by {@link #extract(DocSubject, Enrichment)}; the concrete
 * visitors ({@link AsciidocDocVisitor}, {@link MarkdownDocVisitor}) then serialize those sections
 * and the index. Section selection is controlled by the boolean toggles passed to the constructor;
 * {@link Enrichment} (optional) supplies the human prose and overrides, the rest comes from the
 * {@link DocSubject}.
 */
public abstract class DocVisitor {

    private final boolean configuration;
    private final boolean steps;
    private final boolean flow;
    private final boolean script;
    private final boolean diagram;

    protected DocVisitor(
            final boolean configuration,
            final boolean steps,
            final boolean flow,
            final boolean script,
            final boolean diagram) {
        this.configuration = configuration;
        this.steps = steps;
        this.flow = flow;
        this.script = script;
        this.diagram = diagram;
    }

    protected boolean configuration() {
        return configuration;
    }

    protected boolean steps() {
        return steps;
    }

    protected boolean script() {
        return script;
    }

    /** Whether the visited subject is a workflow (affects section titles and labels). */
    protected boolean isWorkflow(final DocSubject subject) {
        return subject.kind() == DocSubject.Kind.WORKFLOW;
    }

    /** @return the ordered documentation sections for the subject, applying the given enrichment. */
    public DocSections extract(final DocSubject subject, final Enrichment enrichment) {
        if (enrichment != null && enrichment.getBlocks() != null) {
            return extractFromBlocks(subject, enrichment);
        }
        return extractCanonical(subject, enrichment);
    }

    /**
     * Extracts the sections in the canonical order (Overview, Configuration, Steps, Flow, Diagram,
     * Script, Extra sections). Used for inline enrichments (no ordered {@link Enrichment#getBlocks()}).
     */
    private DocSections extractCanonical(final DocSubject subject, final Enrichment enrichment) {
        final var overview = enrichment != null && enrichment.getOverview() != null
                ? enrichment.getOverview()
                : subject.description();

        final var sections = new ArrayList<Section>();
        if (notBlank(overview) || notBlank(subject.extendedDescription())) {
            sections.add(new OverviewSection(defaultOverviewTitle(), overview == null ? "" : overview));
        }
        if (configuration && !subject.parameters().isEmpty()) {
            sections.add(new ConfigurationSection(subject.parameters()));
        }
        if (steps && !subject.steps().isEmpty()) {
            sections.add(new StepsSection(subject.steps()));
        }
        if (flow && !subject.hops().isEmpty()) {
            sections.add(new FlowSection(subject.hops()));
        }
        if (diagram) {
            final var reference = enrichment != null && enrichment.getDiagramReference() != null
                    ? enrichment.getDiagramReference()
                    : defaultDiagramReference(subject);
            if (notBlank(reference)) {
                sections.add(new DiagramSection(reference));
            }
        }
        if (script && enrichment != null && notBlank(enrichment.getScript())) {
            sections.add(new ScriptSection(defaultScriptTitle(), enrichment.getScript()));
        }
        if (enrichment != null && enrichment.getExtraSections() != null) {
            for (final var extra : enrichment.getExtraSections()) {
                if (extra != null && notBlank(extra.getContent())) {
                    sections.add(new ExtraSection(extra.getTitle(), extra.getContent()));
                }
            }
        }
        return new DocSections(subject.name(), sections);
    }

    /**
     * Extracts the sections from a documentation file body, preserving the source narrative order
     * ({@link Enrichment#getBlocks()}). The built-in generated sections are injected where the file
     * anchors them ({@code :configuration}, {@code :steps}, {@code :flow}, {@code :diagram}); any
     * generated section not anchored is appended at the end in canonical order.
     */
    private DocSections extractFromBlocks(final DocSubject subject, final Enrichment enrichment) {
        final var sections = new ArrayList<Section>();
        final var pending = new boolean[4]; // configuration, steps, flow, diagram
        final var overview = enrichment.getOverview() != null ? enrichment.getOverview() : subject.description();
        if (notBlank(overview)) {
            sections.add(new OverviewSection(defaultOverviewTitle(), overview));
        }
        for (final var block : enrichment.getBlocks()) {
            if (block instanceof Enrichment.Block.Generated generated) {
                final var idx = generatedIndex(generated.section());
                if (idx >= 0) {
                    addGenerated(sections, subject, enrichment, idx, pending);
                }
            } else if (block instanceof Enrichment.Block.Overview overviewBlock) {
                if (notBlank(overviewBlock.text())) {
                    sections.add(new ProseSection(overviewBlock.text()));
                }
            } else if (block instanceof Enrichment.Block.Script scriptBlock) {
                if (script && notBlank(scriptBlock.script())) {
                    sections.add(new ScriptSection(defaultScriptTitle(), scriptBlock.script()));
                }
                if (notBlank(scriptBlock.trailingProse())) {
                    sections.add(new ProseSection(scriptBlock.trailingProse()));
                }
            } else if (block instanceof Enrichment.Block.Section extra) {
                if (notBlank(extra.content())) {
                    sections.add(new ExtraSection(extra.title(), extra.content()));
                }
            }
        }
        // any generated section not anchored to the thread: append in canonical order
        for (int i = 0; i < pending.length; i++) {
            addGenerated(sections, subject, enrichment, i, pending);
        }
        return new DocSections(subject.name(), sections);
    }

    private int generatedIndex(final String section) {
        return switch (section == null ? "" : section.toLowerCase(Locale.ROOT)) {
            case "configuration" -> 0;
            case "steps" -> 1;
            case "flow" -> 2;
            case "diagram" -> 3;
            default -> -1;
        };
    }

    /** Adds the generated section at canonical index {@code idx} if enabled and not already emitted. */
    private void addGenerated(
            final List<Section> sections,
            final DocSubject subject,
            final Enrichment enrichment,
            final int idx,
            final boolean[] pending) {
        if (idx < 0 || idx >= pending.length || pending[idx]) {
            return;
        }
        pending[idx] = true;
        switch (idx) {
            case 0 -> {
                if (configuration && !subject.parameters().isEmpty()) {
                    sections.add(new ConfigurationSection(subject.parameters()));
                }
            }
            case 1 -> {
                if (steps && !subject.steps().isEmpty()) {
                    sections.add(new StepsSection(subject.steps()));
                }
            }
            case 2 -> {
                if (flow && !subject.hops().isEmpty()) {
                    sections.add(new FlowSection(subject.hops()));
                }
            }
            case 3 -> {
                if (diagram) {
                    final var reference = enrichment.getDiagramReference() != null
                            ? enrichment.getDiagramReference()
                            : defaultDiagramReference(subject);
                    if (notBlank(reference)) {
                        sections.add(new DiagramSection(reference));
                    }
                }
            }
            default -> {
                // unreachable
            }
        }
    }

    /** Render a single page from the extracted sections. */
    public abstract void renderPage(
            final DocSubject subject, final DocSections sections, final PageHeaders headers, final Writer out)
            throws IOException;

    /** Render the index page listing all documented artefacts. */
    public abstract void renderIndex(final IndexModel index, final Writer out) throws IOException;

    // -- titles and defaults (overridable) ---------------------------------

    protected String defaultOverviewTitle() {
        return "Overview";
    }

    protected String defaultScriptTitle() {
        return "Launch";
    }

    protected String defaultDiagramReference(final DocSubject subject) {
        final var path = subject.relativePath();
        final var dot = path.lastIndexOf('.');
        final var base = dot > 0 ? path.substring(0, dot) : path;
        return "diagrams/" + base + ".svg";
    }

    protected static boolean notBlank(final String value) {
        return value != null && !value.isBlank();
    }
}
