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

/**
 * The headings emitted by the AsciiDoc renderer for each generated section, so the output can match
 * an existing documentation convention (e.g. French "Description / Vue d'ensemble / Objectif").
 * A mutable bean so it can be populated from the Maven {@code <configuration>}.
 */
public class DocTitles {

    private String overview;
    private String configuration;
    private String steps;
    private String actions;
    private String flow;
    private String diagram;
    private String script;

    public DocTitles() {
        this("Overview", "Configuration", "Steps", "Actions", "Flow", "Diagram", "Launch");
    }

    public DocTitles(
            final String overview,
            final String configuration,
            final String steps,
            final String actions,
            final String flow,
            final String diagram,
            final String script) {
        this.overview = overview;
        this.configuration = configuration;
        this.steps = steps;
        this.actions = actions;
        this.flow = flow;
        this.diagram = diagram;
        this.script = script;
    }

    public static DocTitles defaults() {
        return new DocTitles();
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(final String overview) {
        this.overview = overview;
    }

    public String getConfiguration() {
        return configuration;
    }

    public void setConfiguration(final String configuration) {
        this.configuration = configuration;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(final String steps) {
        this.steps = steps;
    }

    public String getActions() {
        return actions;
    }

    public void setActions(final String actions) {
        this.actions = actions;
    }

    public String getFlow() {
        return flow;
    }

    public void setFlow(final String flow) {
        this.flow = flow;
    }

    public String getDiagram() {
        return diagram;
    }

    public void setDiagram(final String diagram) {
        this.diagram = diagram;
    }

    public String getScript() {
        return script;
    }

    public void setScript(final String script) {
        this.script = script;
    }
}
