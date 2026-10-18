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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.hop.base.AbstractMeta;
import org.apache.hop.core.exception.HopXmlException;
import org.apache.hop.core.xml.XmlHandler;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

/**
 * Builds a {@link DocSubject} for a pipeline or workflow file.
 * <p>
 * The top-level metadata (name, description, extended description) and the step list come from the
 * already-resolved Hop object model ({@code AbstractMeta}); the structured parameter table and the
 * hop flow are read from the raw XML via the Hop {@link XmlHandler} facade, because they are not
 * consistently exposed by the in-memory objects. The parsing matches what Hop itself loads, and the
 * class is Maven-free (only Hop + JDK).
 */
public class DocSubjectFactory {

    /** @param subject the loaded {@link PipelineMeta} or {@link WorkflowMeta} (may be null). */
    public DocSubject create(final Path file, final String relativePath, final Object subject) {
        final var document = parse(file);
        final var kind = kindOf(file);

        final var name = subject instanceof AbstractMeta meta && notBlank(meta.getName())
                ? meta.getName()
                : xmlTag(document, "name", fileNameWithoutExtension(file));
        final var description = subject instanceof AbstractMeta meta
                ? valueOrBlank(meta.getDescription())
                : xmlTag(document, "description", "");
        final var extended = subject instanceof AbstractMeta meta
                ? valueOrBlank(meta.getExtendedDescription())
                : xmlTag(document, "extended_description", "");

        final var parameters = extractParameters(document);
        final var steps = subject == null || !stepsAvailable(subject)
                ? extractSteps(document, kind)
                : (kind == DocSubject.Kind.PIPELINE ? pipelineSteps(subject) : workflowSteps(subject));
        final var hops = extractHops(document);

        return new DocSubject(name, kind, relativePath, description, extended, parameters, steps, hops);
    }

    private static boolean stepsAvailable(final Object subject) {
        if (subject instanceof PipelineMeta pipelineMeta) {
            return pipelineMeta.getTransformsArray() != null && pipelineMeta.getTransformsArray().length > 0;
        }
        return subject instanceof WorkflowMeta workflowMeta && workflowMeta.getActions() != null
                && !workflowMeta.getActions().isEmpty();
    }

    private List<Step> extractSteps(final Document document, final DocSubject.Kind kind) {
        final var steps = new ArrayList<Step>();
        final var tag = kind == DocSubject.Kind.PIPELINE ? "transform" : "action";
        final var nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            final var node = nodes.item(i);
            final var name = XmlHandler.getTagValue(node, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            steps.add(new Step(name.trim(), XmlHandler.getTagValue(node, "type"),
                    valueOrBlank(XmlHandler.getTagValue(node, "description")), 1));
        }
        return steps;
    }

    private List<Parameter> extractParameters(final Document document) {
        final var parameters = new ArrayList<Parameter>();
        final var seen = new java.util.HashSet<String>();
        // Canonical parameter definitions serialize a <default_value>; per-action/transform <parameter>
        // entries (the tranche bounds of a CHECK action, ...) serialize a <value> to bind a sub-pipeline
        // call and must not surface as config parameters. Skip any <parameter> carrying a <value> child.
        final var parameterNodes = document.getElementsByTagName("parameter");
        for (int i = 0; i < parameterNodes.getLength(); i++) {
            final var node = parameterNodes.item(i);
            if (hasDirectChild(node, "value")) {
                // per-call binding (action/transform parameters), not a config declaration
                continue;
            }
            final var pName = XmlHandler.getTagValue(node, "name");
            if (pName == null || pName.isBlank()) {
                continue;
            }
            final var key = pName.trim();
            if (!seen.add(key)) {
                continue;
            }
            parameters.add(new Parameter(key,
                    valueOrBlank(XmlHandler.getTagValue(node, "default_value")),
                    valueOrBlank(XmlHandler.getTagValue(node, "description"))));
        }
        return parameters;
    }

    private static boolean hasDirectChild(final Node parent, final String tag) {
        final var childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            if (tag.equals(childNodes.item(i).getNodeName())) {
                return true;
            }
        }
        return false;
    }

    private List<Step> pipelineSteps(final Object subject) {
        final var steps = new ArrayList<Step>();
        if (subject instanceof PipelineMeta pipelineMeta) {
            for (final var transform : pipelineMeta.getTransformsArray()) {
                steps.add(new Step(transform.getName(), transform.getTransformPluginId(),
                        valueOrBlank(transform.getDescription()), 1));
            }
        }
        return steps;
    }

    private List<Step> workflowSteps(final Object subject) {
        final var steps = new ArrayList<Step>();
        if (subject instanceof WorkflowMeta workflowMeta) {
            for (final var action : workflowMeta.getActions()) {
                final var type = action.getAction() == null ? null : action.getAction().getPluginId();
                steps.add(new Step(action.getName(), type, valueOrBlank(action.getDescription()), 1));
            }
        }
        return steps;
    }

    private List<Hop> extractHops(final Document document) {
        final var hops = new ArrayList<Hop>();
        // <hop> elements appear under <order>/<workflow_hops> for both pipelines and workflows
        final var hopNodes = document.getElementsByTagName("hop");
        for (int i = 0; i < hopNodes.getLength(); i++) {
            final var hop = hopNodes.item(i);
            final var from = XmlHandler.getTagValue(hop, "from");
            final var to = XmlHandler.getTagValue(hop, "to");
            if (from != null && to != null) {
                hops.add(new Hop(from.trim(), to.trim(), !"N".equals(XmlHandler.getTagValue(hop, "enabled"))));
            }
        }
        return hops;
    }

    private Document parse(final Path file) {
        try {
            return XmlHandler.loadXmlFile(file.toFile());
        } catch (final HopXmlException e) {
            throw new IllegalArgumentException("Unable to parse HOP file " + file, e);
        }
    }

    private DocSubject.Kind kindOf(final Path file) {
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".hpl")) {
            return DocSubject.Kind.PIPELINE;
        }
        return DocSubject.Kind.WORKFLOW;
    }

    private String xmlTag(final Document document, final String tag, final String fallback) {
        final var elements = document.getElementsByTagName(tag);
        if (elements.getLength() > 0) {
            final var value = elements.item(0).getTextContent();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private String fileNameWithoutExtension(final Path file) {
        final var name = file.getFileName().toString();
        final var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String valueOrBlank(final String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean notBlank(final String value) {
        return value != null && !value.isBlank();
    }
}