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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocSubjectFactoryTest {

    @TempDir
    Path tmp;

    private final DocSubjectFactory factory = new DocSubjectFactory();

    @Test
    void extractsPipelineMetadataParametersStepsAndHopsFromXml() throws Exception {
        final var hpl = tmp.resolve("T_SYNC_LOAD.hpl");
        Files.writeString(hpl, """
                <pipeline>
                  <info>
                    <name>T_SYNC_LOAD</name>
                    <description>Bulk extract and parallel load of one partition.</description>
                    <extended_description>Straight extract/load flow.</extended_description>
                    <parameters>
                      <parameter>
                        <name>sync.table</name>
                        <description>Table name</description>
                        <default_value/>
                      </parameter>
                      <parameter>
                        <name>sync.commit.size</name>
                        <description>Target commit size</description>
                        <default_value>20000</default_value>
                      </parameter>
                    </parameters>
                  </info>
                  <order>
                    <hop><from>EXTRACT</from><to>LOAD</to><enabled>Y</enabled></hop>
                  </order>
                  <transform>
                    <name>EXTRACT</name>
                    <type>TableInput</type>
                    <description>Partition-pruned extract.</description>
                  </transform>
                  <transform>
                    <name>LOAD</name>
                    <type>TableOutput</type>
                    <description>Batched load.</description>
                  </transform>
                </pipeline>
                """);
        final var subject = factory.create(hpl, "oracle_sync/T_SYNC_LOAD.hpl", null);

        assertEquals("T_SYNC_LOAD", subject.name());
        assertEquals(DocSubject.Kind.PIPELINE, subject.kind());
        assertEquals("oracle_sync/T_SYNC_LOAD.hpl", subject.relativePath());
        assertEquals("Bulk extract and parallel load of one partition.", subject.description());

        assertEquals(2, subject.parameters().size());
        assertEquals("sync.table", subject.parameters().get(0).name());
        assertEquals("", subject.parameters().get(0).defaultValue());
        assertEquals("sync.commit.size", subject.parameters().get(1).name());
        assertEquals("20000", subject.parameters().get(1).defaultValue());

        assertEquals(2, subject.steps().size());
        assertEquals("EXTRACT", subject.steps().get(0).name());
        assertEquals("TableInput", subject.steps().get(0).type());
        assertEquals("TableOutput", subject.steps().get(1).type());

        assertEquals(1, subject.hops().size());
        assertEquals("EXTRACT", subject.hops().get(0).from());
        assertEquals("LOAD", subject.hops().get(0).to());
        assertTrue(subject.hops().get(0).enabled());
    }

    @Test
    void extractsWorkflowMetadataAndActionsFromXml() throws Exception {
        final var hwf = tmp.resolve("J_SYNC.hwf");
        Files.writeString(hwf, """
                <workflow>
                  <info>
                    <name>J_SYNC</name>
                    <description>Orchestrator.</description>
                    <extended_description>Loops over partitions.</extended_description>
                    <parameters>
                      <parameter>
                        <name>sync.partition</name>
                        <description>Single partition</description>
                        <default_value/>
                      </parameter>
                    </parameters>
                  </info>
                  <actions>
                    <action><name>START</name><description/> <type>SPECIAL</type></action>
                    <action><name>PREP</name><type>WORKFLOW</type></action>
                  </actions>
                </workflow>
                """);
        final var subject = factory.create(hwf, "J_SYNC.hwf", null);

        assertEquals("J_SYNC", subject.name());
        assertEquals(DocSubject.Kind.WORKFLOW, subject.kind());
        assertEquals("Orchestrator.", subject.description());
        assertEquals(1, subject.parameters().size());
        assertEquals("sync.partition", subject.parameters().get(0).name());
        assertEquals(2, subject.steps().size());
        assertEquals("START", subject.steps().get(0).name());
        assertEquals("SPECIAL", subject.steps().get(0).type());
        assertEquals("WORKFLOW", subject.steps().get(1).type());
    }

    @Test
    void firstParameterValueReturnsDefault() throws Exception {
        final var hpl = tmp.resolve("t.hpl");
        Files.writeString(hpl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <pipeline><info>
                  <name>t</name>
                  <parameters><parameter><name>size</name><default_value>42</default_value><description>x</description></parameter></parameters>
                </info></pipeline>
                """);
        final var subject = factory.create(hpl, "t.hpl", null);
        assertTrue(subject.firstParameterValue("size").isPresent());
        assertEquals("42", subject.firstParameterValue("size").get());
        assertTrue(subject.firstParameterValue("missing").isEmpty());
    }

    @Test
    void excludesPerCallParameterBindingsAndKeepsCanonicalOnes() throws Exception {
        final var hpl = tmp.resolve("dup.hpl");
        Files.writeString(hpl, """
                <pipeline>
                  <info>
                    <name>dup</name>
                    <parameters>
                      <parameter><name>sync.table</name><default_value/><description>Table name</description></parameter>
                      <parameter><name>sync.tranche.lo</name><default_value/><description>The low bound.</description></parameter>
                      <parameter><name>sync.tranche.hi</name><default_value/><description>The high bound.</description></parameter>
                    </parameters>
                  </info>
                  <action><name>CHECK_T0</name><type>WORKFLOW</type>
                    <parameters>
                      <parameter><name>sync.tranche.lo</name><value>0</value></parameter>
                      <parameter><name>sync.tranche.hi</name><value>3</value></parameter>
                    </parameters>
                  </action>
                  <action><name>CHECK_T1</name><type>WORKFLOW</type>
                    <parameters>
                      <parameter><name>sync.tranche.lo</name><value>4</value></parameter>
                      <parameter><name>sync.tranche.hi</name><value>7</value></parameter>
                    </parameters>
                  </action>
                </pipeline>
                """);
        final var subject = factory.create(hpl, "dup.hpl", null);
        // only the canonical (default_value-bearing, deduplicated) declarations must be surfaced;
        // the per-call <value> bindings of the actions must be ignored
        assertEquals(
                3,
                subject.parameters().size(),
                "only canonical parameters must be extracted, got: " + subject.parameters());
        assertEquals("sync.table", subject.parameters().get(0).name());
        assertEquals("sync.tranche.lo", subject.parameters().get(1).name());
        assertEquals("sync.tranche.hi", subject.parameters().get(2).name());
        assertTrue(
                subject.parameters().get(1).description().contains("low bound"),
                "description must be read from the canonical declaration");
    }
}
