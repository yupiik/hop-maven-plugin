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

import java.util.List;

/**
 * Manual documentation content attached to a pipeline/workflow, provided in the mojo configuration.
 * <p>
 * An enrichment is matched to a diagram by {@link #name} (the diagram base name, e.g.
 * {@code T_SYNC_LOAD}) and optionally {@link #kind} (PIPELINE / WORKFLOW). It supplies the human
 * prose that cannot be derived from the HOP file: the {@link #overview} ("role/objectif"), the
 * {@link #script} (launch/run snippet), extra {@link #extraSections}, the one-line {@link #indexRole}
 * shown in the generated index, the {@link #indexOrder} and an optional {@link #diagramReference}
 * override.
 */
public class Enrichment {

    private String name;
    private String kind;
    private String overview;
    private String script;
    private Integer indexOrder;
    private String indexRole;
    private String diagramReference;
    private List<ExtraSection> extraSections = List.of();

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(final String kind) {
        this.kind = kind;
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(final String overview) {
        this.overview = overview;
    }

    public String getScript() {
        return script;
    }

    public void setScript(final String script) {
        this.script = script;
    }

    public Integer getIndexOrder() {
        return indexOrder;
    }

    public void setIndexOrder(final Integer indexOrder) {
        this.indexOrder = indexOrder;
    }

    public String getIndexRole() {
        return indexRole;
    }

    public void setIndexRole(final String indexRole) {
        this.indexRole = indexRole;
    }

    public String getDiagramReference() {
        return diagramReference;
    }

    public void setDiagramReference(final String diagramReference) {
        this.diagramReference = diagramReference;
    }

    public List<ExtraSection> getExtraSections() {
        return extraSections;
    }

    public void setExtraSections(final List<ExtraSection> extraSections) {
        this.extraSections = extraSections == null ? List.of() : extraSections;
    }

    /** A user-supplied free-form section appended to the generated page. */
    public static class ExtraSection {
        private String title;
        private String content;

        public String getTitle() {
            return title;
        }

        public void setTitle(final String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(final String content) {
            this.content = content;
        }
    }
}