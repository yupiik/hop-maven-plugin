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
package io.yupiik.hop.maven;

import io.yupiik.hop.maven.plugin.HopPluginArtifactMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopPluginArtifactMapperTest {

    private final HopPluginArtifactMapper mapper = new HopPluginArtifactMapper(Map.of(), Map.of());

    @Test
    void transformIdFollowingConventionMapsToLowercaseArtifact() {
        assertEquals("hop-transform-datagrid", mapper.transformArtifactId("DataGrid"),
                "a transform id matching the artifact name must map to hop-transform-<lowercase id>");
    }

    @Test
    void transformIdWithDifferentNameUsesBuiltInTable() {
        assertEquals("hop-transform-addsequence", mapper.transformArtifactId("Sequence"),
                "Sequence lives in hop-transform-addsequence");
        assertEquals("hop-transform-textfile", mapper.transformArtifactId("CSVInput"),
                "CSVInput lives in the multi-transform hop-transform-textfile artifact");
        assertEquals("hop-transform-textfile", mapper.transformArtifactId("TextFileInput2"),
                "TextFileInput2 lives in hop-transform-textfile");
        assertEquals("hop-transform-excel", mapper.transformArtifactId("ExcelInput"),
                "ExcelInput lives in hop-transform-excel");
    }

    @Test
    void actionIdFollowingConventionMapsToLowercaseArtifact() {
        assertEquals("hop-action-success", mapper.actionArtifactId("SUCCESS"),
                "an action id matching the artifact name must map to hop-action-<lowercase id>");
    }

    @Test
    void actionIdWithDifferentNameUsesBuiltInTable() {
        assertEquals("hop-action-xml", mapper.actionArtifactId("XSD_VALIDATOR"),
                "XSD_VALIDATOR lives in hop-action-xml");
        assertEquals("hop-action-writetolog", mapper.actionArtifactId("WRITE_TO_LOG"),
                "WRITE_TO_LOG lives in hop-action-writetolog");
        assertEquals("hop-action-pgpfiles", mapper.actionArtifactId("PGP_ENCRYPT_FILES"),
                "PGP_ENCRYPT_FILES lives in the multi-action hop-action-pgpfiles artifact");
    }

    @Test
    void userTransformOverrideWinsOverBuiltInTableAndConvention() {
        final var overridden =
                new HopPluginArtifactMapper(Map.of("DataGrid", "com.acme:custom-datagrid"), Map.of());
        assertEquals("com.acme:custom-datagrid", overridden.transformArtifactId("DataGrid"),
                "an explicit transform2ArtifactMapping entry must replace the default mapping");
    }

    @Test
    void userActionOverrideWinsOverBuiltInTableAndConvention() {
        final var overridden =
                new HopPluginArtifactMapper(Map.of(), Map.of("WRITE_TO_LOG", "com.acme:log-action"));
        assertEquals("com.acme:log-action", overridden.actionArtifactId("WRITE_TO_LOG"),
                "an explicit action2ArtifactMapping entry must replace the default mapping");
    }

    @Test
    void overridesDoNotLeakToOtherPlugins() {
        final var overridden =
                new HopPluginArtifactMapper(Map.of("DataGrid", "com.acme:custom-datagrid"), Map.of());
        assertNotEquals("com.acme:custom-datagrid", overridden.transformArtifactId("CSVInput"),
                "an override for one plugin id must not affect the others");
        assertEquals("hop-transform-textfile", overridden.transformArtifactId("CSVInput"),
                "the built-in table must still apply to plugins without an override");
    }

    @Test
    void debugLogReportsMappingSource() {
        final List<String> logs = new ArrayList<>();
        final var logging = new HopPluginArtifactMapper(
                Map.of("DataGrid", "com.acme:custom-datagrid"),
                Map.of(),
                logs::add);
        logging.transformArtifactId("DataGrid");
        logging.transformArtifactId("CSVInput");
        logging.transformArtifactId("TableInput");
        logging.actionArtifactId("WRITE_TO_LOG");
        assertEquals(4, logs.size());
        assertTrue(logs.get(0).contains("Mapping transform 'DataGrid' -> 'com.acme:custom-datagrid' (user override)"),
                "override mapping must be logged with its source, got: " + logs.get(0));
        assertTrue(logs.get(1).contains("Mapping transform 'CSVInput' -> 'hop-transform-textfile' (built-in table)"),
                "built-in mapping must be logged with its source, got: " + logs.get(1));
        assertTrue(logs.get(2).contains("Mapping transform 'TableInput' -> 'hop-transform-tableinput' (default convention)"),
                "convention mapping must be logged with its source, got: " + logs.get(2));
        assertTrue(logs.get(3).contains("Mapping action 'WRITE_TO_LOG' -> 'hop-action-writetolog' (built-in table)"),
                "action mapping must be logged with its source, got: " + logs.get(3));
    }
}