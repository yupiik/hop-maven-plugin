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
package io.yupiik.hop.maven.mojo;

import static java.util.Collections.emptyList;

import io.yupiik.hop.maven.documentation.AsciidocDocVisitor;
import io.yupiik.hop.maven.documentation.DocFileLoader;
import io.yupiik.hop.maven.documentation.DocSections;
import io.yupiik.hop.maven.documentation.DocSubject;
import io.yupiik.hop.maven.documentation.DocSubjectFactory;
import io.yupiik.hop.maven.documentation.DocTitles;
import io.yupiik.hop.maven.documentation.DocVisitor;
import io.yupiik.hop.maven.documentation.Enrichment;
import io.yupiik.hop.maven.documentation.IndexModel;
import io.yupiik.hop.maven.documentation.LlmsTxtRenderer;
import io.yupiik.hop.maven.documentation.MarkdownDocVisitor;
import io.yupiik.hop.maven.documentation.PageHeaders;
import io.yupiik.hop.maven.render.DiagramExporter;
import io.yupiik.hop.maven.render.Format;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generates documentation pages for Apache Hop pipelines ({@code .hpl}) and workflows
 * ({@code .hwf}) from the same discovery logic as the {@code render} goal (see
 * {@link BaseHopMojo}).
 * <p>
 * For each Hop file it produces a documentation page (AsciiDoc or Markdown) with an overview, the
 * declared parameters, the steps (transforms/actions) and the hop flow, a reference to the rendered
 * diagram, and an optional launch script. The {@code index} page is generated from the Hop files
 * themselves (no configuration required), with optional {@link Enrichment} overrides for the
 * human-written prose and ordering. When {@link #includeImage} is enabled the {@code render} diagram
 * logic is reused to also emit the image files next to the docs; when {@link #includeMd} is enabled
 * the pages are mirrored as Markdown plus an {@code llms.txt} file (see https://llmstxt.org).
 */
@Mojo(name = "generate-doc", requiresProject = false, threadSafe = true)
public class GenerateDocMojo extends BaseHopMojo {

    /**
     * Folder where the generated documentation is written. The {@code index} page and one page per
     * Hop file land here (optionally preserving the source sub-folders, see {@link #preserveStructure}).
     */
    @Parameter(property = "hop.doc.outputDirectory", defaultValue = "${project.basedir}/src/main/minisite/content/hop")
    protected java.io.File outputDirectory;

    /**
     * The documentation format. Each page is written with the matching renderer.
     */
    @Parameter(property = "hop.doc.format", defaultValue = "ADOC")
    protected DocFormat format;

    /**
     * Whether to also render the diagram images (reusing the {@code render} goal logic) alongside
     * the documentation. The images are written to {@link #imageFolder} preserving the source
     * relative path, and the pages reference them through {@link #diagramReferencePrefix}.
     */
    @Parameter(property = "hop.doc.includeImage", defaultValue = "true")
    protected boolean includeImage;

    /**
     * The diagram format used when {@link #includeImage} is enabled.
     */
    @Parameter(property = "hop.doc.imageFormat", defaultValue = "SVG")
    protected Format imageFormat;

    /**
     * Folder where the diagram images are written when {@link #includeImage} is enabled and reused
     * by {@link #diagramReferencePrefix}. Defaults to {@code src/main/minisite/images/diagrams}, the
     * conventional yupiik minisite images location.
     */
    @Parameter(property = "hop.doc.imageFolder", defaultValue = "${project.basedir}/src/main/minisite/images/diagrams")
    protected java.io.File imageFolder;

    /**
     * Prefix of the {@code image::} references in the generated AsciiDoc pages. The source-relative
     * Hop path is appended, so the default {@code diagrams} matches the yupiik minisite layout where
     * images live under {@code images/diagrams}.
     */
    @Parameter(property = "hop.doc.diagramReferencePrefix", defaultValue = "diagrams")
    protected String diagramReferencePrefix;

    /**
     * Whether to generate the {@code index} page listing all documented Hop files. The entries are
     * derived from the Hop files themselves (name, kind, description); only the one-line role and the
     * ordering can be supplied via {@link #enrichments}.
     */
    @Parameter(property = "hop.doc.generateIndex", defaultValue = "true")
    protected boolean generateIndex;

    /**
     * Whether to preserve the source-relative sub-folders when writing the generated pages, or to
     * flatten them all in {@link #outputDirectory} (the {@code workflows} style).
     */
    @Parameter(property = "hop.doc.preserveStructure", defaultValue = "false")
    protected boolean preserveStructure;

    /**
     * Whether to additionally emit a Markdown mirror of every page plus an {@code llms.txt} file in
     * {@link #outputDirectory}. Useful to expose LLM-friendly content (see https://llmstxt.org),
     * independent of the primary {@link #format}.
     */
    @Parameter(property = "hop.doc.includeMd", defaultValue = "false")
    protected boolean includeMd;

    /** Title used in the generated {@code llms.txt} file (see {@link #includeMd}). */
    @Parameter(property = "hop.doc.llmsProjectTitle", defaultValue = "Hop pipelines and workflows")
    protected String llmsProjectTitle;

    /** Summary blockquote emitted at the top of the {@code llms.txt} file. */
    @Parameter(property = "hop.doc.llmsSummary")
    protected String llmsSummary;

    /** Title of the generated {@code index} page. */
    @Parameter(property = "hop.doc.indexTitle")
    protected String indexTitle;

    /** Description of the generated {@code index} page. */
    @Parameter(property = "hop.doc.indexDescription")
    protected String indexDescription;

    /** Index icon used by the yupiik minisite template. */
    @Parameter(property = "hop.doc.indexIcon")
    protected String indexIcon;

    /** Ordering number of the {@code index} page in the yupiik minisite navigation. */
    @Parameter(property = "hop.doc.indexOrder")
    protected Integer indexOrder;

    /** Extra AsciiDoc attribute lines emitted right after each page title (site breadcrumb/nav). */
    @Parameter(name = "adocHeader")
    protected String adocHeader;

    /** Section headings used by the AsciiDoc renderer. */
    @Parameter
    protected DocTitles titles;

    /** Emit the yupiik minisite page header attributes (index title/icon, breadcrumb, prev/next nav). */
    @Parameter(property = "hop.doc.minisiteHeaders", defaultValue = "true")
    protected boolean minisiteHeaders;

    /** Include the configuration (parameters) section. */
    @Parameter(property = "hop.doc.configuration", defaultValue = "true")
    protected boolean configuration;

    /** Include the steps (transforms/actions) section. */
    @Parameter(property = "hop.doc.steps", defaultValue = "true")
    protected boolean steps;

    /** Include the hop flow section. */
    @Parameter(property = "hop.doc.flow", defaultValue = "true")
    protected boolean flow;

    /** Include the launch script section (requires an enrichment with a {@code script}). */
    @Parameter(property = "hop.doc.script", defaultValue = "true")
    protected boolean script;

    /** Manual documentation content per Hop file (overview, script, index role/order, extras...). */
    @Parameter
    protected List<Enrichment> enrichments = emptyList();

    /** Folder where a per-artefact documentation file ({@code <pipelineOrWorkflowName>.adoc}) is read
     * by convention, supplying the enrichments (overview, script, index role/order, ...) for each
     * pipeline/workflow. Falls back to inline {@link #enrichments} when a file is absent. */
    @Parameter(property = "hop.doc.enrichmentLocation", defaultValue = "${project.basedir}/src/main/hop/documentation")
    protected java.io.File enrichmentLocation;

    /** Fail the build when a discovered pipeline/workflow has neither a documentation file in
     * {@link #enrichmentLocation} nor an inline {@link #enrichments} override. */
    @Parameter(property = "hop.doc.failIfMissing", defaultValue = "true")
    protected boolean failIfMissing;

    /** AsciiDoc configuration table column layout. */
    @Parameter(property = "hop.doc.configCols", defaultValue = "2,2,5")
    protected String configCols;

    /** AsciiDoc configuration table header row (pipe-separated: parameter|default|description). */
    @Parameter(property = "hop.doc.configHeaders", defaultValue = "Parameter|Default|Description")
    protected String configHeaders;

    /** Text shown for an empty parameter default value in the AsciiDoc configuration table. */
    @Parameter(property = "hop.doc.emptyDefaultText", defaultValue = "empty")
    protected String emptyDefaultText;

    /** Extra paragraph emitted after the index title, before the workflow/pipeline tables. */
    @Parameter(property = "hop.doc.indexIntro")
    protected String indexIntro;

    /** Arbitrary AsciiDoc appended at the end of the index page. */
    @Parameter(property = "hop.doc.indexFooter")
    protected String indexFooter;

    /** Index page previous-navigation label/link (e.g. the previous sibling section). */
    @Parameter(property = "hop.doc.indexPrevLabel")
    protected String indexPrevLabel;

    @Parameter(property = "hop.doc.indexPrevLink")
    protected String indexPrevLink;

    /** Index page next-navigation label/link (e.g. the next sibling section). */
    @Parameter(property = "hop.doc.indexNextLabel")
    protected String indexNextLabel;

    @Parameter(property = "hop.doc.indexNextLink")
    protected String indexNextLink;

    private final DiagramExporter exporter = new DiagramExporter(false);

    @Override
    protected void process(
            final List<Path> files, final Path source, final IHopMetadataProvider provider, final Variables variables)
            throws MojoExecutionException, MojoFailureException {
        final var out = toPath(outputDirectory, "outputDirectory");
        final var images = includeImage ? toPath(imageFolder, "imageFolder") : null;
        try {
            Files.createDirectories(out);
            if (images != null) {
                Files.createDirectories(images);
            }
        } catch (final IOException e) {
            throw new MojoExecutionException("Can't create output folder " + out, e);
        }

        final var factory = new DocSubjectFactory();
        final var fileLoader = documentationLoader();

        final var subjects = new ArrayList<IndexedSubject>();
        final var missing = new ArrayList<String>();
        for (final var file : files) {
            final var rel = source.relativize(file);
            try {
                final var loaded = exporter.loadSubject(file, provider, variables);
                final var subject = factory.create(file, rel.toString(), loaded);
                final var enrichment = resolveEnrichment(subject, fileLoader);
                if (enrichment == null && failIfMissing) {
                    missing.add(subject.name());
                }
                subjects.add(new IndexedSubject(subject, loaded, enrichment));
            } catch (final Exception e) {
                getLog().warn("Skipping " + file + ": " + e.getMessage() + " ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
        if (failIfMissing && !missing.isEmpty()) {
            throw new MojoFailureException(
                    "No documentation file found for " + missing.size() + " pipeline/workflow(s): "
                            + missing + ". Add a '"
                            + (enrichmentLocation == null ? "src/main/hop/documentation" : enrichmentLocation.toPath())
                            + "/<name>.adoc' file "
                            + "or provide an inline <enrichments> override, or set failIfMissing=false.");
        }

        // stable sort by enrichment order then by name
        subjects.sort(
                Comparator.comparing((IndexedSubject s) -> s.enrichment == null || s.enrichment.getIndexOrder() == null
                                ? Integer.MAX_VALUE
                                : s.enrichment.getIndexOrder())
                        .thenComparing(s -> s.subject.name()));

        // write the documentation pages
        final var visitor = newVisitor();
        for (int i = 0; i < subjects.size(); i++) {
            final var indexed = subjects.get(i);
            final var headers = pageHeaders(subjects, i, indexed.subject);
            writePage(indexed, visitor, headers, images, source, variables, provider, out);
        }

        if (generateIndex) {
            writeIndex(subjects, visitor, out);
        }
        if (includeMd) {
            writeMarkdownMirror(subjects, out);
        }
        getLog().info("Generated documentation for " + subjects.size() + " Hop file(s) in " + out
                + (format == DocFormat.ADOC ? " (AsciiDoc)" : " (Markdown)")
                + (includeImage ? " with diagrams in " + images : "")
                + (includeMd ? " + Markdown mirror/llms.txt" : ""));
    }

    private void writePage(
            final IndexedSubject indexed,
            final DocVisitor visitor,
            final PageHeaders headers,
            final Path images,
            final Path source,
            final Variables variables,
            final IHopMetadataProvider provider,
            final Path out)
            throws MojoExecutionException {
        final var subject = indexed.subject;
        if (includeImage && images != null) {
            final var file = source.resolve(subject.relativePath().replace('\\', '/'));
            try {
                exporter.renderOne(
                        file,
                        source.relativize(file),
                        indexed.loaded,
                        imageFormat,
                        images,
                        variables,
                        provider,
                        1.0f,
                        true,
                        getLog()::info);
            } catch (final Throwable t) {
                getLog().warn("Unable to render diagram for " + subject.name() + ": " + t.getMessage());
            }
        }

        final var target = pagePath(out, subject, format.extension());
        final var sections = visitor.extract(subject, indexed.enrichment);
        try (final Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            visitor.renderPage(subject, sections, headers, writer);
            writer.flush();
        } catch (final IOException e) {
            throw new MojoExecutionException("Can't write " + target, e);
        }
    }

    private String renderToString(final DocVisitor visitor, final DocSubject subject, final DocSections sections) {
        final var out = new StringWriter();
        try {
            visitor.renderPage(subject, sections, PageHeaders.empty(), out);
        } catch (final IOException e) {
            throw new IllegalStateException("Can't render " + subject.name() + " to text", e);
        }
        return out.toString();
    }

    private PageHeaders pageHeaders(final List<IndexedSubject> subjects, final int index, final DocSubject subject) {
        if (!minisiteHeaders) {
            return PageHeaders.empty();
        }
        final var home = "Home[#]";
        final var parent = indexTitle == null || indexTitle.isBlank() ? "Workflows" : indexTitle;
        final var breadcrumb = home + " > " + parent + " > " + subject.name();
        String prevLabel = null;
        String prevLink = null;
        String nextLabel = null;
        String nextLink = null;
        if (index > 0) {
            final var prev = subjects.get(index - 1);
            prevLabel = prev.subject.name();
            prevLink = prev.subject.name() + ".html";
        } else {
            prevLabel = parent;
            prevLink = "index.html";
        }
        if (index < subjects.size() - 1) {
            final var next = subjects.get(index + 1);
            nextLabel = next.subject.name();
            nextLink = next.subject.name() + ".html";
        } else {
            nextLabel = parent;
            nextLink = "index.html";
        }
        return new PageHeaders(subject.name(), indexIcon, breadcrumb, prevLabel, prevLink, nextLabel, nextLink);
    }

    private void writeIndex(final List<IndexedSubject> subjects, final DocVisitor visitor, final Path out)
            throws MojoExecutionException {
        final var entries = new ArrayList<IndexModel.Entry>();
        for (final var indexed : subjects) {
            entries.add(new IndexModel.Entry(
                    indexed.subject.name(),
                    indexed.subject.kind(),
                    indexed.subject.relativePath(),
                    indexed.subject.description(),
                    indexed.enrichment == null ? null : indexed.enrichment.getIndexRole(),
                    indexed.enrichment == null ? null : indexed.enrichment.getIndexOrder()));
        }
        final var index = new IndexModel(indexTitle, indexDescription, indexIcon, indexOrder, entries);
        final var indexFile = out.resolve("index." + format.extension());
        try (final Writer writer = Files.newBufferedWriter(indexFile, StandardCharsets.UTF_8)) {
            visitor.renderIndex(index, writer);
            writer.flush();
        } catch (final IOException e) {
            throw new MojoExecutionException("Can't write " + indexFile, e);
        }
    }

    private void writeMarkdownMirror(final List<IndexedSubject> subjects, final Path out)
            throws MojoExecutionException {
        if (format != DocFormat.MD) {
            // when the primary format is Markdown the .md pages are already there; only mirror when not
            final var adoc = new AsciidocDocVisitor(
                    configuration,
                    steps,
                    flow,
                    script,
                    true,
                    "",
                    "",
                    titles == null ? DocTitles.defaults() : titles,
                    "",
                    "",
                    "",
                    "",
                    null,
                    null,
                    null,
                    null);
            final var parser = new io.yupiik.asciidoc.parser.Parser();
            final var resolver = io.yupiik.asciidoc.parser.resolver.ContentResolver.of(out);
            for (final var indexed : subjects) {
                final var subject = indexed.subject;
                final var target = pagePath(out, subject, "md");
                final var sections = adoc.extract(subject, indexed.enrichment);
                final var adocText = renderToString(adoc, subject, sections);
                final var document =
                        parser.parse(adocText, new io.yupiik.asciidoc.parser.Parser.ParserContext(resolver));
                final var mdRenderer = new io.yupiik.asciidoc.renderer.markdown.GithubFlavoredMarkdownRenderer();
                mdRenderer.visit(document);
                final var md = mdRenderer.result();
                try (final Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                    writer.write(md);
                    writer.flush();
                } catch (final IOException e) {
                    throw new MojoExecutionException("Can't write " + target, e);
                }
            }
        }
        final var entries = new ArrayList<IndexModel.Entry>();
        for (final var indexed : subjects) {
            entries.add(new IndexModel.Entry(
                    indexed.subject.name(),
                    indexed.subject.kind(),
                    indexed.subject.relativePath(),
                    indexed.subject.description(),
                    indexed.enrichment == null ? null : indexed.enrichment.getIndexRole(),
                    indexed.enrichment == null ? null : indexed.enrichment.getIndexOrder()));
        }
        final var index = new IndexModel(null, null, null, null, entries);
        final var llms = out.resolve("llms.txt");
        try (final Writer writer = Files.newBufferedWriter(llms, StandardCharsets.UTF_8)) {
            new LlmsTxtRenderer(llmsProjectTitle, llmsSummary).render(index, writer);
            writer.flush();
        } catch (final IOException e) {
            throw new MojoExecutionException("Can't write " + llms, e);
        }
    }

    private DocVisitor newVisitor() {
        final var diagram = true;
        if (format == DocFormat.MD) {
            return new MarkdownDocVisitor(configuration, steps, flow, script, diagram);
        }
        return new AsciidocDocVisitor(
                configuration,
                steps,
                flow,
                script,
                diagram,
                adocHeader,
                configCols,
                titles == null ? DocTitles.defaults() : titles,
                configHeaders,
                emptyDefaultText,
                indexIntro,
                indexFooter,
                indexPrevLabel,
                indexPrevLink,
                indexNextLabel,
                indexNextLink);
    }

    private Path pagePath(final Path out, final DocSubject subject, final String extension) {
        if (preserveStructure) {
            final var rel = Path.of(subject.relativePath().replace('\\', '/')); // may include sub-folders
            final var parent = rel.getParent();
            final var base = subject.name();
            return parent == null
                    ? out.resolve(base + "." + extension)
                    : out.resolve(parent).resolve(base + "." + extension);
        }
        return out.resolve(subject.name() + "." + extension);
    }

    private DocFileLoader documentationLoader() {
        if (enrichmentLocation == null) {
            return new DocFileLoader(null);
        }
        return new DocFileLoader(enrichmentLocation.toPath().toAbsolutePath().normalize());
    }

    /**
     * Resolve the enrichment for a subject: the by-convention {@code <name>.adoc} file in
     * {@link #enrichmentLocation} first, then overridden by an explicit inline
     * {@link #enrichments} entry (explicit wins).
     */
    private Enrichment resolveEnrichment(final DocSubject subject, final DocFileLoader loader) {
        var enrichment = loader.load(subject);
        final var inline = matchEnrichment(subject);
        return inline == null ? enrichment : inline;
    }

    private Enrichment matchEnrichment(final DocSubject subject) {
        if (enrichments == null) {
            return null;
        }
        for (final var enrichment : enrichments) {
            if (enrichment == null) {
                continue;
            }
            final var name = enrichment.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (name.equals(subject.name())
                    || (containsWildcard(name) && matchesGlob(name.replace('\\', '/'), subject.relativePath()))) {
                final var kind = enrichment.getKind();
                if (kind == null
                        || kind.isBlank()
                        || kind.equalsIgnoreCase(subject.kind().name())) {
                    return enrichment;
                }
            }
        }
        return null;
    }

    private static boolean containsWildcard(final String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    private static boolean matchesGlob(final String pattern, final String relativePath) {
        final var regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            final var c = pattern.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(relativePath).matches();
    }

    private record IndexedSubject(DocSubject subject, Object loaded, Enrichment enrichment) {}

    /** Supported documentation formats. */
    public enum DocFormat {
        ADOC("adoc"),
        MD("md");

        private final String extension;

        DocFormat(final String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }
}
