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
package io.yupiik.hop.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.maven.plugin.HopDiagramPluginIds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopDiagramPluginIdsTest {

    @TempDir
    Path tmp;

    private final HopDiagramPluginIds pluginIds = new HopDiagramPluginIds();

    @Test
    void extractsTransformTypesFromPipeline() throws Exception {
        final var hpl = tmp.resolve("simple.hpl");
        Files.writeString(hpl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <pipeline>
                  <info>
                    <name>simple</name>
                  </info>
                  <transform>
                    <name>Read</name>
                    <type>CSVInput</type>
                  </transform>
                  <transform>
                    <name>Data Grid</name>
                    <type>DataGrid</type>
                  </transform>
                </pipeline>
                """);
        assertEquals(Set.of("CSVInput", "DataGrid"), pluginIds.extractTransforms(hpl));
    }

    @Test
    void extractsActionTypesFromWorkflow() throws Exception {
        final var hwf = tmp.resolve("simple.hwf");
        Files.writeString(hwf, """
                <?xml version="1.0" encoding="UTF-8"?>
                <workflow>
                  <info>
                    <name>simple</name>
                  </info>
                  <actions>
                    <action>
                      <name>Start</name>
                      <type>SPECIAL</type>
                    </action>
                    <action>
                      <name>Write to log</name>
                      <type>WRITE_TO_LOG</type>
                    </action>
                  </actions>
                </workflow>
                """);
        assertEquals(Set.of("SPECIAL", "WRITE_TO_LOG"), pluginIds.extractActions(hwf));
    }

    @Test
    void ignoresEmptyTypesAndKeepsOrder() throws Exception {
        final var hpl = tmp.resolve("ordered.hpl");
        Files.writeString(hpl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <pipeline>
                  <transform>
                    <name>A</name>
                    <type></type>
                  </transform>
                  <transform>
                    <name>B</name>
                    <type>DataGrid</type>
                  </transform>
                  <transform>
                    <name>C</name>
                    <type>TableInput</type>
                  </transform>
                </pipeline>
                """);
        final var ids = pluginIds.extractTransforms(hpl);
        assertEquals(2, ids.size());
        assertTrue(ids.contains("DataGrid"));
        assertTrue(ids.contains("TableInput"));
    }
}
