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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocFileLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void parsesAsciiDocAttributesAndBodyIntoEnrichment() throws Exception {
        final var adoc = tmp.resolve("T_SYNC_LOAD.adoc");
        Files.writeString(adoc, """
                = T_SYNC_LOAD
                :hop-index-role: Phase 2 : extraction partitionnee source.
                :hop-index-order: 7
                :hop-diagram-reference: diagrams/utilities/oracle_sync/T_SYNC_LOAD.svg

                `T_SYNC_LOAD` est la phase 2 de l'archivage (en francais : la copie des donnees de la partition).

                Il extrait la partition source et la charge cote cible.

                == Script de lancement

                [source,bash]
                ----
                hop-run.sh -f utilities/oracle_sync/T_SYNC_LOAD.hpl \\
                  -r local -p ORA_SYNC_HOP -e local
                ----

                == Voir aussi

                * link:../design.html[Design]
                """);

        final var subject = new DocSubject(
                "T_SYNC_LOAD",
                DocSubject.Kind.PIPELINE,
                "utilities/oracle_sync/T_SYNC_LOAD.hpl",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
        final var enrichment = new DocFileLoader(tmp.toAbsolutePath().normalize()).load(subject);

        assertEquals("Phase 2 : extraction partitionnee source.", enrichment.getIndexRole());
        assertEquals(7, enrichment.getIndexOrder());
        assertEquals("diagrams/utilities/oracle_sync/T_SYNC_LOAD.svg", enrichment.getDiagramReference());
        assertTrue(
                enrichment.getOverview().contains("`T_SYNC_LOAD` est la phase 2 de l'archivage"),
                "overview must contain the prose, got: " + enrichment.getOverview());
        assertTrue(
                enrichment.getOverview().contains("Il extrait la partition source et la charge cote cible."),
                "overview must keep multiple paragraphs");
        assertTrue(
                enrichment.getScript().contains("hop-run.sh -f utilities/oracle_sync/T_SYNC_LOAD.hpl"),
                "script must carry the bash block, got: " + enrichment.getScript());
        assertTrue(
                enrichment.getScript().contains("-r local -p ORA_SYNC_HOP -e local"),
                "script must keep continuation lines");
        final var extra = enrichment.getExtraSections().get(0);
        assertEquals("Voir aussi", extra.getTitle());
        assertTrue(extra.getContent().contains("link:../design.html[Design]"), "extra section content expected");
    }

    @Test
    void returnsNullWhenFileMissing() {
        final var subject = new DocSubject(
                "NOPE",
                DocSubject.Kind.PIPELINE,
                "NOPE.hpl",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
        assertNull(new DocFileLoader(tmp.toAbsolutePath().normalize()).load(subject));
    }

    @Test
    void preservesSourceOrderAndNestedHeadings() throws Exception {
        final var adoc = tmp.resolve("TRACED.adoc");
        Files.writeString(adoc, """
                = TRACED
                :hop-index-order: 1

                The leading role.

                == :configuration

                (the generated configuration table lands here)

                == Premiere section

                Premiere prose.

                === Sous titre

                prose du sous titre.

                == Script de lancement

                [source,bash]
                ----
                hop-run.sh -f TRACED.hpl
                ----

                Note apres le script.

                == Derniere section

                Derniere prose.
                """);

        final var subject = new DocSubject(
                "TRACED",
                DocSubject.Kind.PIPELINE,
                "TRACED.hpl",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
        final var enrichment = new DocFileLoader(tmp.toAbsolutePath().normalize()).load(subject);

        assertEquals("The leading role.", enrichment.getOverview());
        final var blocks = enrichment.getBlocks();
        assertEquals(4, blocks.size(), "blocks must preserve the body order, got: " + blocks);
        // generated anchor then two free sections then script (with trailing prose) then last free section
        assertEquals(new Enrichment.Block.Generated("configuration"), blocks.get(0));
        assertEquals(Enrichment.Block.Section.class, blocks.get(1).getClass());
        assertEquals("Premiere section", ((Enrichment.Block.Section) blocks.get(1)).title());
        assertEquals(Enrichment.Block.Script.class, blocks.get(2).getClass());
        final var script = (Enrichment.Block.Script) blocks.get(2);
        assertTrue(script.script().contains("hop-run.sh -f TRACED.hpl"), "script code expected");
        assertEquals("Note apres le script.", script.trailingProse(), "prose under the script section must be kept");
        assertEquals(Enrichment.Block.Section.class, blocks.get(3).getClass());
        assertEquals("Derniere section", ((Enrichment.Block.Section) blocks.get(3)).title());
        // nested === heading must be preserved inside the "Premiere section" extra content
        final var first = (Enrichment.Block.Section) blocks.get(1);
        assertTrue(
                first.content().contains("=== Sous titre"),
                "nested === heading must be preserved, got: " + first.content());
        assertTrue(first.content().contains("prose du sous titre."), "prose under the === must be kept");
        // overview must NOT contain the trailing prose (bug 1 regression guard)
        assertFalse(
                enrichment.getOverview().contains("apres le script"),
                "trailing prose must not be hoisted into overview");
    }

    @Test
    void buildsBackwardCompatibleViewsFromBlocks() throws Exception {
        final var adoc = tmp.resolve("BACKCOMPAT.adoc");
        Files.writeString(adoc, """
                = BACKCOMPAT
                :hop-index-role: Role X.

                Overview prose.

                == Script de lancement

                [source,bash]
                ----
                hop-run.sh
                ----

                == Extra section

                Extra prose.
                """);
        final var subject = new DocSubject(
                "BACKCOMPAT",
                DocSubject.Kind.PIPELINE,
                "BACKCOMPAT.hpl",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
        final var enrichment = new DocFileLoader(tmp.toAbsolutePath().normalize()).load(subject);

        assertTrue(enrichment.getScript().contains("hop-run.sh"), "script view must be derived from the block");
        assertEquals(1, enrichment.getExtraSections().size(), "one extra section expected");
        assertEquals("Extra section", enrichment.getExtraSections().get(0).getTitle());
        assertTrue(enrichment.getExtraSections().get(0).getContent().contains("Extra prose."));
    }
}
