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

import io.yupiik.hop.maven.documentation.DocSections.ConfigurationSection;
import io.yupiik.hop.maven.documentation.DocSections.DiagramSection;
import io.yupiik.hop.maven.documentation.DocSections.ExtraSection;
import io.yupiik.hop.maven.documentation.DocSections.FlowSection;
import io.yupiik.hop.maven.documentation.DocSections.OverviewSection;
import io.yupiik.hop.maven.documentation.DocSections.ScriptSection;
import io.yupiik.hop.maven.documentation.DocSections.Section;
import io.yupiik.hop.maven.documentation.DocSections.StepsSection;
import java.io.IOException;
import java.io.Writer;

/**
 * Serializes the {@link DocSections} and {@link IndexModel} as AsciiDoc, matching the layout used
 * by the yupiik minisite template (title, section headings with {@code ==}, {@code image::}
 * diagram references and {@code [source,...]} code blocks).
 */
public class AsciidocDocVisitor extends DocVisitor {

    /** Optional extra AsciiDoc attribute lines emitted right after the page title (site nav, breadcrumb). */
    private final String headerExtra;
    /** The configuration table column layout (default: parameter, default, description). */
    private final String configCols;
    /** Section titles (headings) emitted for each generated section. */
    private final DocTitles titles;
    /** The configuration table header row (pipe-separated: parameter|default|description). */
    private final String configHeaders;
    /** Text shown for an empty parameter default value. */
    private final String emptyDefaultText;
    /** Extra paragraph emitted after the index title (before the workflow/pipeline tables). */
    private final String indexIntro;
    /** Arbitrary AsciiDoc appended at the end of the index page. */
    private final String indexFooter;
    /** Index page prev/next navigation attributes (optional). */
    private final String indexPrevLabel, indexPrevLink, indexNextLabel, indexNextLink;

    public AsciidocDocVisitor(final boolean configuration, final boolean steps,
                              final boolean flow, final boolean script, final boolean diagram,
                              final String headerExtra, final String configCols, final DocTitles titles,
                              final String configHeaders, final String emptyDefaultText,
                              final String indexIntro, final String indexFooter,
                              final String indexPrevLabel, final String indexPrevLink,
                              final String indexNextLabel, final String indexNextLink) {
        super(configuration, steps, flow, script, diagram);
        this.headerExtra = headerExtra == null ? "" : headerExtra;
        this.configCols = configCols == null ? "2,2,5" : configCols;
        this.titles = titles == null ? DocTitles.defaults() : titles;
        this.configHeaders = configHeaders == null ? "Parameter|Default|Description" : configHeaders;
        this.emptyDefaultText = emptyDefaultText == null ? "empty" : emptyDefaultText;
        this.indexIntro = indexIntro == null ? "" : indexIntro;
        this.indexFooter = indexFooter == null ? "" : indexFooter;
        this.indexPrevLabel = indexPrevLabel;
        this.indexPrevLink = indexPrevLink;
        this.indexNextLabel = indexNextLabel;
        this.indexNextLink = indexNextLink;
    }

    @Override
    public void renderPage(final DocSubject subject, final DocSections sections, final PageHeaders headers,
                           final Writer out) throws IOException {
        out.write("= " + sections.title());
        out.write('\n');
        writeMinisiteHeaders(headers, out);
        if (!headerExtra.isBlank()) {
            out.write(headerExtra);
            if (!headerExtra.endsWith("\n")) {
                out.write('\n');
            }
        }
        out.write('\n');
        for (final var section : sections.sections()) {
            writeSection(subject, sections.title(), section, out);
        }
    }

    private void writeMinisiteHeaders(final PageHeaders headers, final Writer out) throws IOException {
        if (headers == null) {
            return;
        }
        if (headers.indexTitle() != null) {
            out.write(":minisite-index-title: " + headers.indexTitle());
            out.write('\n');
        }
        if (headers.indexIcon() != null) {
            out.write(":minisite-index-icon: " + headers.indexIcon());
            out.write('\n');
        }
        if (headers.breadcrumb() != null) {
            out.write(":minisite-breadcrumb: " + headers.breadcrumb());
            out.write('\n');
        }
        if (headers.prevLabel() != null && headers.prevLink() != null) {
            out.write(":minisite-nav-prev-label: " + headers.prevLabel());
            out.write('\n');
            out.write(":minisite-nav-prev-link: " + headers.prevLink());
            out.write('\n');
        }
        if (headers.nextLabel() != null && headers.nextLink() != null) {
            out.write(":minisite-nav-next-label: " + headers.nextLabel());
            out.write('\n');
            out.write(":minisite-nav-next-link: " + headers.nextLink());
            out.write('\n');
        }
    }

    private void writeSection(final DocSubject subject, final String pageTitle,
                              final Section section, final Writer out) throws IOException {
        if (section instanceof OverviewSection overview) {
            heading(out, titles.getOverview() == null ? overview.title() : titles.getOverview());
            out.write('\n');
            out.write(overview.text());
            out.write('\n');
        } else if (section instanceof ConfigurationSection configuration) {
            heading(out, titles.getConfiguration());
            out.write("\n[cols=\"" + configCols + "\",opts=\"headers\"]\n");
            out.write("|===\n");
            out.write("|" + configHeaders.replace("|", "|") + '\n');
            for (final var parameter : configuration.parameters()) {
                out.write("|`" + xmlEscape(parameter.name()) + "`|"
                        + defaultCell(parameter.defaultValue()) + "|"
                        + xmlEscape(parameter.description()) + '\n');
            }
            out.write("|===\n");
        } else if (section instanceof StepsSection stepsSection) {
            final var workflow = isWorkflow(subject);
            heading(out, workflow ? titles.getActions() : titles.getSteps());
            out.write("\n[cols=\"2,2,5\",opts=\"headers\"]\n");
            out.write("|===\n");
            out.write("|" + (workflow ? "Action" : "Step") + "|Type|Description\n");
            for (final var step : stepsSection.steps()) {
                out.write("|`" + xmlEscape(step.name()) + "`|"
                        + (step.type() == null ? "" : "`" + xmlEscape(step.type()) + "`") + "|"
                        + xmlEscape(step.description()) + '\n');
            }
            out.write("|===\n");
        } else if (section instanceof FlowSection flowSection) {
            heading(out, titles.getFlow());
            out.write("\n[cols=\"1,1\",opts=\"headers\"]\n");
            out.write("|===\n");
            out.write("|From|To\n");
            for (final var hop : flowSection.hops()) {
                if (hop.enabled()) {
                    out.write("|`" + xmlEscape(hop.from()) + "`|`" + xmlEscape(hop.to()) + "`\n");
                }
            }
            out.write("|===\n");
        } else if (section instanceof DiagramSection diagram) {
            heading(out, titles.getDiagram());
            out.write("\nimage::" + diagram.reference() + "[Diagramme " + pageTitle + ",width=900]\n");
        } else if (section instanceof ScriptSection script) {
            heading(out, titles.getScript());
            out.write("\n[source,bash]\n----\n");
            out.write(script.script());
            out.write("\n----\n");
        } else if (section instanceof ExtraSection extra) {
            heading(out, extra.title() == null ? "Extra" : extra.title());
            out.write('\n');
            out.write(extra.content());
            out.write('\n');
        }
    }

    @Override
    public void renderIndex(final IndexModel index, final Writer out) throws IOException {
        out.write("= " + (index.title() == null ? "Index" : index.title()));
        out.write('\n');
        if (index.title() != null) {
            out.write(":minisite-index-title: " + index.title());
            out.write('\n');
        }
        if (index.description() != null) {
            out.write(":minisite-index-description: " + index.description());
            out.write('\n');
        }
        if (index.icon() != null) {
            out.write(":minisite-index-icon: " + index.icon());
            out.write('\n');
        }
        if (index.order() != null) {
            out.write(":minisite-index: " + index.order());
            out.write('\n');
        }
        final var parent = index.title() == null ? "Index" : index.title();
        out.write(":minisite-breadcrumb: Home[#] > " + parent);
        out.write('\n');
        if (indexPrevLabel != null && indexPrevLink != null) {
            out.write(":minisite-nav-prev-label: " + indexPrevLabel);
            out.write('\n');
            out.write(":minisite-nav-prev-link: " + indexPrevLink);
            out.write('\n');
        }
        if (indexNextLabel != null && indexNextLink != null) {
            out.write(":minisite-nav-next-label: " + indexNextLabel);
            out.write('\n');
            out.write(":minisite-nav-next-link: " + indexNextLink);
            out.write('\n');
        }
        if (!headerExtra.isBlank()) {
            out.write(headerExtra);
            if (!headerExtra.endsWith("\n")) {
                out.write('\n');
            }
        }
        out.write('\n');
        if (indexIntro != null && !indexIntro.isBlank()) {
            out.write(indexIntro);
            out.write("\n\n");
        } else if (index.description() != null && !index.description().isBlank()) {
            out.write(index.description());
            out.write("\n\n");
        }
        out.write("== " + "Workflows" + " (`.hwf`)\n\n");
        writeIndexTable(out, index.workflows(), "Workflow");
        out.write("\n== Pipelines (`.hpl`)\n\n");
        writeIndexTable(out, index.pipelines(), "Pipeline");
        if (indexFooter != null && !indexFooter.isBlank()) {
            out.write('\n');
            out.write(indexFooter);
            out.write('\n');
        }
    }

    private void writeIndexTable(final Writer out, final java.util.List<IndexModel.Entry> entries,
                                 final String column) throws IOException {
        out.write("[cols=\"2,5\",opts=\"headers\"]\n");
        out.write("|===\n");
        out.write("|" + column + "|Role\n");
        for (final var entry : entries) {
            final var role = entry.role() == null || entry.role().isBlank() ? entry.description() : entry.role();
            out.write("|link:" + entry.pageName() + ".html[" + entry.pageName() + "]|" + xmlEscape(role) + '\n');
        }
        out.write("|===\n");
    }

    private void heading(final Writer out, final String text) throws IOException {
        out.write("== " + text);
        out.write('\n');
    }

    private String defaultCell(final String value) {
        return value == null || value.isBlank() ? emptyDefaultText : "`" + xmlEscape(value) + "`";
    }

    private static String xmlEscape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}