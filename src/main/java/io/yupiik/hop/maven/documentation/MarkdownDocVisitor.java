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
 * Serializes the {@link DocSections} and {@link IndexModel} as GitHub / llms-friendly Markdown.
 */
public class MarkdownDocVisitor extends DocVisitor {

    public MarkdownDocVisitor(final boolean configuration, final boolean steps,
                              final boolean flow, final boolean script, final boolean diagram) {
        super(configuration, steps, flow, script, diagram);
    }

    @Override
    public void renderPage(final DocSubject subject, final DocSections sections, final PageHeaders headers,
                           final Writer out) throws IOException {
        out.write("# " + sections.title());
        out.write("\n\n");
        for (final var section : sections.sections()) {
            writeSection(subject, section, out);
        }
    }

    private void writeSection(final DocSubject subject, final Section section, final Writer out)
            throws IOException {
        if (section instanceof OverviewSection overview) {
            out.write("## " + overview.title() + "\n\n");
            out.write(overview.text());
            out.write("\n\n");
        } else if (section instanceof ConfigurationSection configuration) {
            out.write("## Configuration\n\n");
            out.write("| Parameter | Default | Description |\n");
            out.write("| --- | --- | --- |\n");
            for (final var parameter : configuration.parameters()) {
                out.write("| `" + mdEscape(parameter.name()) + "` | "
                        + (parameter.defaultValue() == null || parameter.defaultValue().isBlank()
                        ? "empty" : "`" + mdEscape(parameter.defaultValue()) + "`") + " | "
                        + mdEscape(parameter.description()) + " |\n");
            }
            out.write('\n');
        } else if (section instanceof StepsSection stepsSection) {
            final var workflow = isWorkflow(subject);
            final var label = workflow ? "Action" : "Step";
            out.write("## " + (workflow ? "Actions" : "Steps") + "\n\n");
            out.write("| " + label + " | Type | Description |\n");
            out.write("| --- | --- | --- |\n");
            for (final var step : stepsSection.steps()) {
                out.write("| `" + mdEscape(step.name()) + "` | "
                        + (step.type() == null ? "" : "`" + mdEscape(step.type()) + "`") + " | "
                        + mdEscape(step.description()) + " |\n");
            }
            out.write('\n');
        } else if (section instanceof FlowSection flowSection) {
            out.write("## Flow\n\n");
            for (final var hop : flowSection.hops()) {
                if (hop.enabled()) {
                    out.write("- `" + mdEscape(hop.from()) + "` -> `" + mdEscape(hop.to()) + "`\n");
                }
            }
            out.write('\n');
        } else if (section instanceof DiagramSection diagram) {
            out.write("## Diagram\n\n");
            out.write("![](" + diagram.reference() + ")\n\n");
        } else if (section instanceof ScriptSection script) {
            out.write("## " + script.title() + "\n\n");
            out.write("```bash\n");
            out.write(script.script());
            out.write("\n```\n\n");
        } else if (section instanceof ExtraSection extra) {
            out.write("## " + (extra.title() == null ? "Extra" : extra.title()) + "\n\n");
            out.write(extra.content());
            out.write("\n\n");
        }
    }

    @Override
    public void renderIndex(final IndexModel index, final Writer out) throws IOException {
        out.write("# " + (index.title() == null ? "Index" : index.title()));
        out.write("\n\n");
        if (index.description() != null && !index.description().isBlank()) {
            out.write(index.description());
            out.write("\n\n");
        }
        out.write("## Workflows (.hwf)\n\n");
        writeIndexTable(out, index.workflows(), "Workflow");
        out.write("\n## Pipelines (.hpl)\n\n");
        writeIndexTable(out, index.pipelines(), "Pipeline");
    }

    private void writeIndexTable(final Writer out, final java.util.List<IndexModel.Entry> entries,
                                 final String column) throws IOException {
        out.write("| " + column + " | Role |\n");
        out.write("| --- | --- |\n");
        for (final var entry : entries) {
            final var role = entry.role() == null || entry.role().isBlank() ? entry.description() : entry.role();
            out.write("| [" + entry.pageName() + "](" + entry.pageName() + ".md) | " + mdEscape(role) + " |\n");
        }
    }

    private static String mdEscape(final String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }
}