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
}
