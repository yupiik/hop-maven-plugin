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
package io.yupiik.hop.maven.mojo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.maven.documentation.Enrichment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.encryption.Encr;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.metadata.serializer.multi.MultiMetadataProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test for {@link GenerateDocMojo} using the real Hop subject loaders against a small
 * fixture tree. The mojo fields are set directly (no Maven DI) and {@link GenerateDocMojo#process}
 * is invoked after {@link HopEnvironment#init()}.
 */
class GenerateDocMojoTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void initHop() throws Exception {
        try {
            HopEnvironment.init();
        } catch (final Exception ignored) {
            // already initialised or plugin registry already set up
        }
    }

    private GenerateDocMojo mojo(final Path output) {
        final var mojo = new GenerateDocMojo();
        mojo.outputDirectory = output.toFile();
        mojo.format = GenerateDocMojo.DocFormat.ADOC;
        mojo.configuration = true;
        mojo.steps = true;
        mojo.flow = true;
        mojo.script = true;
        mojo.includeImage = false;
        mojo.generateIndex = true;
        mojo.includeMd = false;
        mojo.failIfMissing = false; // most tests provide inline enrichments or none
        return mojo;
    }

    @Test
    void generatesAsciidocPagesAndIndex() throws Exception {
        final var source = tmp.resolve("src");
        Files.createDirectories(source);
        Files.writeString(source.resolve("T_SYNC_LOAD.hpl"), """
                <pipeline>
                  <info>
                    <name>T_SYNC_LOAD</name>
                    <description>Bulk extract and load of one partition.</description>
                    <parameters>
                      <parameter><name>sync.table</name><description>Table name</description><default_value/></parameter>
                    </parameters>
                  </info>
                  <order><hop><from>EXTRACT</from><to>LOAD</to><enabled>Y</enabled></hop></order>
                  <transform><name>EXTRACT</name><type>TableInput</type><description>Extract.</description></transform>
                  <transform><name>LOAD</name><type>TableOutput</type><description>Load.</description></transform>
                </pipeline>
                """);
        Files.writeString(source.resolve("J_SYNC.hwf"), """
                <workflow>
                  <info>
                    <name>J_SYNC</name>
                    <description>Orchestrator.</description>
                  </info>
                  <actions>
                    <action><name>START</name><type>SPECIAL</type></action>
                  </actions>
                </workflow>
                """);

        final var output = tmp.resolve("out");
        final var mojo = mojo(output);
        mojo.enrichments = List.of(enrichment("T_SYNC_LOAD", "Loads the source partition.", "custom-role"));
        callProcess(mojo, source, output);

        final var page = Files.readString(output.resolve("T_SYNC_LOAD.adoc"));
        assertTrue(page.contains("Loads the source partition."), "enrichment overview must be present");
        assertTrue(page.contains("|`sync.table`|empty|Table name"), "parameter table must be rendered");
        assertTrue(page.contains("|`EXTRACT`|`TableInput`|Extract."), "step table must be rendered");
        assertTrue(page.contains("== Flow"), "flow section must be rendered");
        assertTrue(Files.exists(output.resolve("J_SYNC.adoc")), "workflow page must be generated");

        final var index = Files.readString(output.resolve("index.adoc"));
        assertTrue(index.contains("link:T_SYNC_LOAD.html[T_SYNC_LOAD]"), "index must link the pipeline page");
        assertTrue(index.contains("custom-role"), "index role must come from the enrichment");
        assertTrue(index.contains("link:J_SYNC.html[J_SYNC]"), "index must link the workflow page");
        assertFalse(Files.exists(output.resolve("llms.txt")), "llms.txt must not be generated when includeMd is off");
    }

    @Test
    void generatesMarkdownMirrorAndLlmsTxtWhenEnabled() throws Exception {
        final var source = tmp.resolve("src2");
        Files.createDirectories(source);
        Files.writeString(source.resolve("SIMPLE.hpl"), """
                <pipeline>
                  <info>
                    <name>SIMPLE</name>
                    <description>A simple pipeline.</description>
                    <parameters>
                      <parameter><name>p1</name><description>Param</description><default_value>5</default_value></parameter>
                    </parameters>
                  </info>
                  <transform><name>D</name><type>Dummy</type><description>Dummy.</description></transform>
                </pipeline>
                """);

        final var output = tmp.resolve("out2");
        final var mojo = mojo(output);
        mojo.format = GenerateDocMojo.DocFormat.MD;
        mojo.includeMd = true;
        mojo.llmsProjectTitle = "My Hop Docs";
        mojo.llmsSummary = "A helpful summary.";
        callProcess(mojo, source, output);

        assertTrue(Files.exists(output.resolve("index.md")), "markdown index must be generated");
        final var page = Files.readString(output.resolve("SIMPLE.md"));
        assertTrue(page.startsWith("# SIMPLE"), "markdown page must start with H1, got:\n" + page);
        assertTrue(page.contains("| `p1` | `5` | Param |"), "markdown parameter table must be rendered");

        final var llms = Files.readString(output.resolve("llms.txt"));
        assertTrue(llms.contains("# My Hop Docs"), "llms.txt must carry the project title");
        assertTrue(llms.contains("> A helpful summary."), "llms.txt must carry the summary");
        assertTrue(llms.contains("- [SIMPLE](SIMPLE.md): A simple pipeline."), "llms.txt must link the page mirror");
    }

    private static Enrichment enrichment(final String name, final String overview, final String role) {
        final var enrichment = new Enrichment();
        enrichment.setName(name);
        enrichment.setOverview(overview);
        enrichment.setScript("hop-run.sh -f " + name + ".hpl -r local");
        enrichment.setIndexRole(role);
        return enrichment;
    }

    @Test
    void loadsEnrichmentByConventionFromDocumentationFolder() throws Exception {
        final var source = tmp.resolve("srcD");
        Files.createDirectories(source);
        Files.writeString(source.resolve("PIPEDOC.hpl"), """
                <pipeline>
                  <info><name>PIPEDOC</name><description>Pipeline doc.</description></info>
                  <transform><name>D</name><type>Dummy</type><description>x</description></transform>
                </pipeline>
                """);
        final var docFolder = tmp.resolve("hop-doc");
        Files.createDirectories(docFolder);
        Files.writeString(docFolder.resolve("PIPEDOC.adoc"), """
                = PIPEDOC
                :hop-index-role: Role from file.
                :hop-index-order: 3

                The PIPEDOC prose.
                """);

        final var output = tmp.resolve("outD");
        final var mojo = mojo(output);
        mojo.enrichmentLocation = docFolder.toFile();
        mojo.failIfMissing = true;
        callProcess(mojo, source, output);

        final var page = Files.readString(output.resolve("PIPEDOC.adoc"));
        assertTrue(page.contains("The PIPEDOC prose."), "overview must come from the documentation file");
        final var index = Files.readString(output.resolve("index.adoc"));
        assertTrue(index.contains("Role from file."), "index role must come from the documentation file attribute");
    }

    @Test
    void failsWhenDocFileMissingAndFailIfMissingEnabled() throws Exception {
        final var source = tmp.resolve("srcF");
        Files.createDirectories(source);
        Files.writeString(source.resolve("UNDOCUMENTED.hpl"), """
                <pipeline>
                  <info><name>UNDOCUMENTED</name><description>x</description></info>
                  <transform><name>D</name><type>Dummy</type><description>x</description></transform>
                </pipeline>
                """);
        final var docFolder = tmp.resolve("hop-doc-empty");
        Files.createDirectories(docFolder);

        final var mojo = mojo(tmp.resolve("outF"));
        mojo.enrichmentLocation = docFolder.toFile();
        mojo.failIfMissing = true;
        org.junit.jupiter.api.Assertions.assertThrows(
                org.apache.maven.plugin.MojoFailureException.class,
                () -> callProcess(mojo, source, tmp.resolve("outF")));
    }

    private static void callProcess(final GenerateDocMojo mojo, final Path source, final Path output) throws Exception {
        final var variables = new Variables();
        variables.setParentVariables(Variables.getADefaultVariableSpace());
        final var provider = new MultiMetadataProvider(
                Encr.getEncoder(),
                List.of(new JsonMetadataProvider(
                        Encr.getEncoder(), tmpMetadata().toString(), variables)),
                variables);
        final List<Path> files;
        try (var stream = Files.walk(source)) {
            files = stream.filter(p -> {
                        final var n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".hpl") || n.endsWith(".hwf");
                    })
                    .toList();
        }
        mojo.process(files, source, provider, variables);
    }

    private static Path tmpMetadata() {
        try {
            return Files.createTempDirectory("hop-md");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
