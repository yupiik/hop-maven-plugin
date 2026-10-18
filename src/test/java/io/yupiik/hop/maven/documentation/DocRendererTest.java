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
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocRendererTest {

    private final DocSubject subject = new DocSubject(
            "T_SYNC_LOAD",
            DocSubject.Kind.PIPELINE,
            "utilities/oracle_sync/T_SYNC_LOAD.hpl",
            "Bulk extract and load of one partition.",
            "",
            of(new Parameter("sync.table", "", "Table name"),
                    new Parameter("sync.commit.size", "20000", "Target commit size")),
            of(new Step("EXTRACT", "TableInput", "Partition-pruned extract.", 1),
                    new Step("LOAD", "TableOutput", "Batched load.", 1)),
            of(new Hop("EXTRACT", "LOAD", true)));

    @Test
    void asciidocPageMatchesExpectedSectionLayout() {
        final var visitor =
                new AsciidocDocVisitor(true, true, true, true, true, "", "2,2,5", DocTitles.defaults(),
                        "Parameter|Default|Description", "empty", "", "", null, null, null, null);
        final var sections = visitor.extract(subject, new Enrichment());
        final var out = new StringWriter();
        try {
            visitor.renderPage(subject, sections, PageHeaders.empty(), out);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        final var adoc = out.toString();
        assertTrue(adoc.startsWith("= T_SYNC_LOAD"), "page must start with the title, got:\n" + adoc);
        assertTrue(adoc.contains("== Overview"), "must render the overview heading");
        assertTrue(adoc.contains("== Configuration"), "must render the configuration heading");
        assertTrue(adoc.contains("[cols=\"2,2,5\",opts=\"headers\"]"), "must render the config table layout");
        assertTrue(adoc.contains("|`sync.table`|empty|Table name"), "must render the parameter row");
        assertTrue(adoc.contains("|`sync.commit.size`|`20000`|Target commit size"), "must render the default value");
        assertTrue(adoc.contains("|`EXTRACT`|`TableInput`|Partition-pruned extract."), "must render the step row");
        assertTrue(adoc.contains("== Flow"), "must render the flow heading");
        assertTrue(adoc.contains("image::diagrams/utilities/oracle_sync/T_SYNC_LOAD.svg"),
                "must render the diagram reference with the source relative path");
    }

    @Test
    void asciidocEnrichmentOverridesOverviewScriptAndDiagram() {
        final var enrichment = new Enrichment();
        enrichment.setName("T_SYNC_LOAD");
        enrichment.setOverview("The custom one-line role.");
        enrichment.setScript("hop-run.sh -f T_SYNC_LOAD.hpl -r local");
        enrichment.setDiagramReference("diagrams/custom.svg");
        final var visitor = new AsciidocDocVisitor(true, false, false, true, true, "", "2,2,5",
                DocTitles.defaults(), "Parameter|Default|Description", "empty", "", "", null, null, null, null);
        final var sections = visitor.extract(subject, enrichment);
        final var out = new StringWriter();
        try {
            visitor.renderPage(subject, sections, PageHeaders.empty(), out);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        final var adoc = out.toString();
        assertTrue(adoc.contains("The custom one-line role."), "enrichment overview must be used");
        assertTrue(adoc.contains("[source,bash]"), "script must be a bash source block");
        assertTrue(adoc.contains("image::diagrams/custom.svg"), "enrichment diagram reference must be used");
        assertTrue(adoc.contains("hop-run.sh"), "script content must be present");
        assertTrue(!adoc.contains("== Steps"), "steps must be skipped when disabled");
    }

    @Test
    void markdownPageRendersTablesAndImage() {
        final var visitor = new MarkdownDocVisitor(true, true, true, false, true);
        final var sections = visitor.extract(subject, new Enrichment());
        final var out = new StringWriter();
        try {
            visitor.renderPage(subject, sections, PageHeaders.empty(), out);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        final var md = out.toString();
        assertTrue(md.startsWith("# T_SYNC_LOAD"), "markdown page must start with H1 title, got:\n" + md);
        assertTrue(md.contains("| Parameter | Default | Description |"), "must render a parameter table header");
        assertTrue(md.contains("| `sync.commit.size` | `20000` | Target commit size |"), "must render default value");
        assertTrue(md.contains("| `LOAD` | `TableOutput` | Batched load. |"), "must render the step row");
        assertTrue(md.contains("![]("), "must render the image");
        assertTrue(md.contains("- `EXTRACT` -> `LOAD`"), "must render the flow");
    }

    @Test
    void asciidocIndexListsWorkflowsAndPipelines() {
        final var index = new IndexModel("Pipeline/Workflows", "Docs.", "fas fa-project-diagram", 15, of(
                new IndexModel.Entry("J_SYNC", DocSubject.Kind.WORKFLOW, "J_SYNC.hwf", "Orchestrator.", "Runs the loop.", null),
                new IndexModel.Entry("T_SYNC_LOAD", DocSubject.Kind.PIPELINE, "T_SYNC_LOAD.hpl", "Loads the partition.", null, null)));
        final var visitor = new AsciidocDocVisitor(true, true, true, true, true, "", "2,2,5",
                DocTitles.defaults(), "Parameter|Default|Description", "empty", "", "", null, null, null, null);
        final var out = new StringWriter();
        try {
            visitor.renderIndex(index, out);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        final var adoc = out.toString();
        assertTrue(adoc.startsWith("= Pipeline/Workflows"), "index must start with its title, got:\n" + adoc);
        assertTrue(adoc.contains("== Workflows (`.hwf`)"), "index must have a workflows heading");
        assertTrue(adoc.contains("link:J_SYNC.html[J_SYNC]"), "index must link the workflow page");
        assertTrue(adoc.contains("Runs the loop."), "index role should come from the enrichment");
        assertTrue(adoc.contains("== Pipelines (`.hpl`)"), "index must have a pipelines heading");
    }

    @Test
    void llmsTxtFollowsSpec() {
        final var index = new IndexModel(null, null, null, null, of(
                new IndexModel.Entry("J_SYNC", DocSubject.Kind.WORKFLOW, "J_SYNC.hwf", "Orchestrator.", "Runs the loop.", null),
                new IndexModel.Entry("T_SYNC_LOAD", DocSubject.Kind.PIPELINE, "T_SYNC_LOAD.hpl", "Loads the partition.", null, null)));
        final var out = new StringWriter();
        try {
            new LlmsTxtRenderer("ORA HOP", "A useful summary.").render(index, out);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        final var llms = out.toString();
        assertTrue(llms.startsWith("# ORA HOP"), "llms.txt must start with H1 project title, got:\n" + llms);
        assertTrue(llms.contains("> A useful summary."), "llms.txt must contain the blockquote summary");
        assertTrue(llms.contains("## Workflows (.hwf)"), "llms.txt must have the workflows file list");
        assertTrue(llms.contains("- [J_SYNC](J_SYNC.md): Runs the loop."), "llms.txt must link the markdown mirror with a note");
        assertTrue(llms.contains("## Pipelines (.hpl)"), "llms.txt must have the pipelines file list");
        assertTrue(llms.contains("- [T_SYNC_LOAD](T_SYNC_LOAD.md): Loads the partition."), "llms.txt must link the pipeline mirror");
    }
}