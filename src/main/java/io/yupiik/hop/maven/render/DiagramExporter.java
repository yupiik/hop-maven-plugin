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
package io.yupiik.hop.maven.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.apache.hop.core.diagram.DiagramExportOptions;
import org.apache.hop.core.diagram.DiagramExportResult;
import org.apache.hop.core.diagram.ExportContext;
import org.apache.hop.core.diagram.IExportContext;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.diagram.exporter.PipelineMermaidDiagramExporter;
import org.apache.hop.diagram.exporter.PipelineSvgDiagramExporter;
import org.apache.hop.diagram.exporter.WorkflowMermaidDiagramExporter;
import org.apache.hop.diagram.exporter.WorkflowSvgDiagramExporter;
import org.apache.hop.diagram.loader.PipelineDiagramSubjectLoader;
import org.apache.hop.diagram.loader.WorkflowDiagramSubjectLoader;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.workflow.WorkflowMeta;

/**
 * Renders a single Apache Hop pipeline ({@code .hpl}) or workflow ({@code .hwf}) into a diagram
 * file, reusing the Hop diagram exporters (SVG, Mermaid). This is the shared, Maven-free rendering
 * primitive used both by the {@code render} mojo and by {@code generate-doc} when image embedding is
 * enabled ({@code includeImage}).
 * <p>
 * The class loads the subject with the Hop diagram subject loaders, so the same resolved
 * {@link PipelineMeta}/{@link WorkflowMeta} is available to callers ({@link #loadSubject}).
 */
public class DiagramExporter {

    private final boolean failOnMissing;

    public DiagramExporter(final boolean failOnMissing) {
        this.failOnMissing = failOnMissing;
    }

    /**
     * Load a HOP diagram subject ({@link PipelineMeta} or {@link WorkflowMeta}) from a file.
     */
    public Object loadSubject(final Path file, final IHopMetadataProvider provider, final Variables variables)
            throws HopException {
        final var path = file.toAbsolutePath().toString();
        final var lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".hpl")) {
            return new PipelineDiagramSubjectLoader().loadSubject(path, provider, variables);
        }
        if (lower.endsWith(".hwf")) {
            return new WorkflowDiagramSubjectLoader().loadSubject(path, provider, variables);
        }
        throw new HopException("Unsupported HOP file type: " + file.getFileName());
    }

    /**
     * Check that every transform/action of a loaded subject is resolvable (not a {@code Missing}
     * placeholder). Throws when {@code failOnMissing} is enabled.
     */
    public void checkMissingPlugins(final Path file, final Object subject) throws HopException {
        if (!failOnMissing) {
            return;
        }
        final var missing = new ArrayList<String>();
        if (subject instanceof PipelineMeta pipelineMeta) {
            for (final var transform : pipelineMeta.getTransformsArray()) {
                if (transform.isMissing()) {
                    missing.add("transform '" + transform.getName() + "' (" + transform.getTransformPluginId() + ")");
                }
            }
        } else if (subject instanceof WorkflowMeta workflowMeta) {
            for (final var action : workflowMeta.getActions()) {
                if (action.isMissing()) {
                    missing.add("action '" + action.getName() + "' (" + action.getAction().getPluginId() + ")");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new HopException("Missing HOP plugin(s) in " + file + ": " + String.join(", ", missing)
                    + ". Add the matching hop-transform-*/hop-action-* module(s) as dependencies of the "
                    + "plugin, or enable the autoResolve parameter.");
        }
    }

    /**
     * Render one diagram from an already loaded subject into {@code target} preserving {@code rel}
     * (the source-relative path). Returns the output file.
     */
    public Path renderOne(final Path file, final Path rel, final Object subject, final Format format,
                          final Path target, final Variables variables, final IHopMetadataProvider provider,
                          final float magnification, final boolean includeNotes,
                          final Consumer<String> log) throws Exception {
        final var dir = target.resolve(rel).getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        final var fileName = file.getFileName().toString();
        final var dot = fileName.lastIndexOf('.');
        final var baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        final var output = dir.resolve(baseName + "." + format.getExtension());

        final var options = new DiagramExportOptions(output.toString(), format.name());
        options.setMagnification(magnification);
        options.setIncludeNotes(includeNotes);

        final var context =
                new ExportContext(variables, provider, new LogChannel("hop-maven-plugin"),
                        IExportContext.ExportEnvironment.BATCH);

        final var result = doExport(subject, format, options, context);
        if (result == null || !result.isSuccess()) {
            throw new HopException("Export failed for " + file + ": "
                    + (result == null ? "null result" : result.getErrorMessage()));
        }
        log.accept("Rendered " + file + " -> " + output);
        return output;
    }

    private DiagramExportResult doExport(final Object subject, final Format format,
                                         final DiagramExportOptions options, final IExportContext context)
            throws HopException {
        if (format == Format.SVG) {
            if (subject instanceof PipelineMeta pipelineMeta) {
                return new PipelineSvgDiagramExporter().export(pipelineMeta, options, context);
            }
            if (subject instanceof WorkflowMeta workflowMeta) {
                return new WorkflowSvgDiagramExporter().export(workflowMeta, options, context);
            }
        }
        if (format == Format.MERMAID) {
            if (subject instanceof PipelineMeta pipelineMeta) {
                return new PipelineMermaidDiagramExporter().export(pipelineMeta, options, context);
            }
            if (subject instanceof WorkflowMeta workflowMeta) {
                return new WorkflowMermaidDiagramExporter().export(workflowMeta, options, context);
            }
        }
        throw new HopException("No exporter for format " + format + " and subject " + subject.getClass().getName());
    }
}