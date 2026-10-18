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
package io.yupiik.hop.maven.mojo;

import io.yupiik.hop.maven.render.DiagramExporter;
import io.yupiik.hop.maven.render.Format;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import static java.util.Optional.ofNullable;
import static java.util.Collections.emptyList;

/**
 * Renders Apache Hop pipelines ({@code .hpl}) and workflows ({@code .hwf}) as diagrams.
 * <p>
 * Diagram files are discovered from {@link BaseHopMojo#sourceFolder} (recursively, honoring
 * {@link BaseHopMojo#includes} and {@link BaseHopMojo#excludes}), the project variables and
 * metadata are resolved, then one diagram per requested {@link #formats} is written under
 * {@link #targetFolder} preserving the source relative path. HOP files load headlessly so the goal
 * is well suited to CI builds.
 */
@Mojo(name = "render", requiresProject = false, threadSafe = true)
public class RenderMojo extends BaseHopMojo {

    /**
     * Folder where the generated diagrams are written, preserving the source relative path.
     */
    @Parameter(property = "hop.render.targetFolder", defaultValue = "${project.build.directory}/hop-diagrams")
    protected java.io.File targetFolder;

    /**
     * The diagram formats to generate. Each format maps to one exporter per subject type.
     */
    @Parameter(property = "hop.render.formats", defaultValue = "SVG")
    protected List<Format> formats;

    /**
     * Diagram magnification factor.
     */
    @Parameter(property = "hop.render.magnification", defaultValue = "1.0")
    protected float magnification;

    /**
     * Include Hop notes in the rendered diagram.
     */
    @Parameter(property = "hop.render.includeNotes", defaultValue = "true")
    protected boolean includeNotes;

    /**
     * Fail the build if a transform or action declared by a rendered {@code .hpl}/{@code .hwf} is
     * not resolvable (its {@code hop-transform-*} or {@code hop-action-*} module is missing from the
     * plugin classpath). When {@code false} unresolved steps are rendered as-is (box and label).
     * Enable it in the consuming pom together with the Hop plugin modules as plugin dependencies.
     */
    @Parameter(property = "hop.render.failOnMissing", defaultValue = "false")
    protected boolean failOnMissing;

    private final List<Format> effectiveFormats = new ArrayList<>();

    @Override
    protected void process(final List<Path> files, final Path source,
                           final IHopMetadataProvider provider, final Variables variables)
            throws MojoExecutionException, MojoFailureException {
        final var target = toPath(targetFolder, "targetFolder");

        effectiveFormats.addAll(ofNullable(formats).filter(list -> !list.isEmpty()).orElse(List.of(Format.SVG)));

        final var exporter = new DiagramExporter(failOnMissing);
        final int[] rendered = {0};
        final int[] failed = {0};
        for (final var file : files) {
            final var rel = source.relativize(file);
            try {
                final var subject = exporter.loadSubject(file, provider, variables);
                exporter.checkMissingPlugins(file, subject);
                for (final var format : effectiveFormats) {
                    exporter.renderOne(file, rel, subject, format, target, variables, provider,
                            magnification, includeNotes, getLog()::info);
                }
                rendered[0]++;
            } catch (final Throwable t) {
                failed[0]++;
                getLog().error("Failed to render " + file, t);
            }
        }

        if (failed[0] > 0) {
            throw new MojoFailureException("Failed to render " + failed[0] + " HOP file(s), see logs above");
        }
        getLog().info("Rendered " + rendered[0] + " HOP file(s) to " + target + " in "
                + effectiveFormats.size() + " format(s)");
    }
}