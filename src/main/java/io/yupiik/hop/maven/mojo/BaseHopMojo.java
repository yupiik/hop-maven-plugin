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

import com.google.gson.JsonParser;
import io.yupiik.hop.maven.distribution.AntGlob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.encryption.Encr;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.metadata.serializer.multi.MultiMetadataProvider;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.ConfigUtils;

/**
 * Base class for mojos that need to find Apache Hop pipelines ({@code .hpl}) and workflows
 * ({@code .hwf}) and load them in a headless build environment.
 * <p>
 * It centralises the shared concerns: the Hop file discovery ({@link #sourceFolder}, recursive,
 * {@link #includes}/{@link #excludes}) honoring Ant globs, the project variables and config
 * injection, the canonical Hop metadata provider, the {@link HopEnvironment} initialisation
 * (serialized on the class lock so several mojos can run concurrently) and the optional
 * auto-resolution of missing Hop plugin artifacts with the Maven resolver.
 * <p>
 * Subclasses implement {@link #process(List, Path, IHopMetadataProvider, Variables)} to do the
 * actual per-file work after the environment is ready. They only need to depend on the Maven API
 * ({@code org.apache.maven.*}); the lower-level service classes are Maven-free.
 */
public abstract class BaseHopMojo extends AbstractMojo {

    /**
     * Root folder where HOP diagram files ({@code .hpl}, {@code .hwf}) are searched. Defaults to
     * the project base dir, following the Apache Hop convention where pipelines and workflows are
     * stored directly in the project folder.
     */
    @Parameter(property = "hop.sourceFolder")
    protected java.io.File sourceFolder;

    /**
     * The metadata base folder holding the HOP metadata ({@code rdbms}, run configurations, ...).
     * Defaults to {@code <project.basedir>/metadata}, matching the canonical Hop
     * {@code metadataBaseFolder={PROJECT_HOME}/metadata}.
     */
    @Parameter(property = "hop.metadataFolder")
    protected java.io.File metadataFolder;

    /**
     * JSON file declaring the Hop variables to inject before diagrams are resolved and rendered.
     * The file may be a canonical Hop {@code project-config.json} (reading {@code config.variables})
     * or any JSON exposing a top-level {@code variables} array, each entry being
     * {@code {name, value, description}}.
     */
    @Parameter(property = "hop.configFile")
    protected java.io.File configFile;

    /**
     * Ant-style glob patterns selecting files to process (applied to the source-relative path).
     */
    @Parameter(property = "hop.includes")
    protected List<String> includes = emptyList();

    /**
     * Ant-style glob patterns excluding files from processing.
     */
    @Parameter(property = "hop.excludes")
    protected List<String> excludes = emptyList();

    /**
     * Recurse into sub-folders of {@link #sourceFolder}.
     */
    @Parameter(property = "hop.recursive", defaultValue = "true")
    protected boolean recursive;

    /**
     * Resolve the Hop plugin artifacts (transforms and actions) declared by the rendered diagrams
     * with the Maven resolver, stage them and register them into the Hop registry
     * programmatically. This removes the need to add {@code hop-transform-*} / {@code hop-action-*}
     * modules as dependencies of this plugin for icon rendering. No JVM-global property is set; the
     * resolution/staging phase runs outside any lock and the registration is serialized on
     * {@code HopEnvironment.class} so several mojos can run concurrently in the same build.
     */
    @Parameter(property = "hop.autoResolve", defaultValue = "false")
    protected boolean autoResolve;

    /**
     * Override mappings from transform plugin id to the artifact (artifactId or full
     * {@code groupId:artifactId:version} coordinates) shipping it. Entries take precedence over the
     * built-in table; the default rule is {@code org.apache.hop:hop-transform-<id>}.
     */
    @Parameter
    protected java.util.Map<String, String> transform2ArtifactMapping;

    /**
     * Override mappings from action plugin id to the artifact (artifactId or full
     * {@code groupId:artifactId:version} coordinates) shipping it. Entries take precedence over the
     * built-in table; the default rule is {@code org.apache.hop:hop-action-<id>}.
     */
    @Parameter
    protected java.util.Map<String, String> action2ArtifactMapping;

    /**
     * Staging folder where auto-resolved plugin jars are copied (plugin jar at the root,
     * dependencies in a {@code lib/} sub-folder). Only used when {@link #autoResolve} is enabled.
     */
    @Parameter(property = "hop.autoResolvePluginsFolder")
    protected java.io.File autoResolvePluginsFolder;

    /**
     * Version used when a mapped artifact is given as a bare {@code hop-transform-<id>} artifactId
     * (no explicit version). Defaults to the Hop version this plugin runs against when it can be
     * detected, otherwise Apache Hop dependency version.
     */
    @Parameter(property = "hop.autoResolveVersion")
    protected String autoResolveVersion;

    /**
     * The current Maven project (used to resolve defaults and provide the build output directory).
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The current Maven session, used to reach the resolver session and the remote repositories.
     */
    @Parameter(defaultValue = "${session}", readonly = true)
    protected MavenSession session;

    /**
     * The repository system session configured by Maven.
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    protected RepositorySystemSession repositorySystemSession;

    /**
     * The resolver entry point, injected by Maven.
     */
    @Inject
    protected RepositorySystem repositorySystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // resolve defaults that depend on the project (project.basedir) at execution time
        final var source = toPath(
                sourceFolder != null ? sourceFolder : new java.io.File(project.getBasedir(), ""), "sourceFolder");
        final var metadata = toPath(
                metadataFolder != null ? metadataFolder : new java.io.File(project.getBasedir(), "metadata"),
                "metadataFolder");

        // PAS 1 - collect only (no shared state mutation): reusable file list + plugin ids.
        final var files = collectDiagramFiles(source);

        final var transformIds = new LinkedHashSet<String>();
        final var actionIds = new LinkedHashSet<String>();
        if (autoResolve) {
            final var diagramPluginIds = new io.yupiik.hop.maven.plugin.HopDiagramPluginIds();
            for (final var file : files) {
                final var fileTransforms = diagramPluginIds.extractTransforms(file);
                final var fileActions = diagramPluginIds.extractActions(file);
                getLog().debug("Auto-resolve: '" + source.relativize(file) + "' declares transforms=" + fileTransforms
                        + ", actions=" + fileActions);
                transformIds.addAll(fileTransforms);
                actionIds.addAll(fileActions);
            }
        }

        // PAS 2 - critical section (serialized between mojos): init + registration.
        synchronized (HopEnvironment.class) {
            try {
                HopEnvironment.init();
            } catch (final Exception e) {
                throw new MojoExecutionException("Can't initialize the Hop environment", e);
            }
        }

        final var autoResolveResult = autoResolve ? autoResolveMissingPlugins(transformIds, actionIds) : null;
        if (autoResolveResult != null && !autoResolveResult.staged().getStaged().isEmpty()) {
            synchronized (HopEnvironment.class) {
                autoResolveResult.registrar().register(autoResolveResult.staged());
            }
        }
        if (autoResolveResult != null && autoResolveResult.staged().hasMissing()) {
            getLog().warn("Unable to auto-resolve plugin artifact(s) for transforms "
                    + autoResolveResult.staged().getMissingTransformIds() + " and actions "
                    + autoResolveResult.staged().getMissingActionIds()
                    + "; add the matching hop-transform-*/hop-action-* module(s) as dependencies of the plugin.");
        }

        // PAS 3 - per-mojo processing (outside the lock, concurrent between mojos).
        final var variables = new Variables();
        variables.setParentVariables(Variables.getADefaultVariableSpace());
        injectMavenProjectProperties(variables);
        injectConfigVariables(variables);

        final var provider = hopMetadataProvider(metadata, variables);

        process(files, source, provider, variables);
    }

    /**
     * Per-file processing after the Hop environment is ready. Subclasses implement this to render
     * a diagram, generate documentation, etc. The {@code files} are the collected {@code .hpl}
     * {@code .hwf} under {@code source}; each file's relative path is {@code source.relativize(file)}.
     */
    protected abstract void process(
            final List<Path> files, final Path source, final IHopMetadataProvider provider, final Variables variables)
            throws MojoExecutionException, MojoFailureException;

    /**
     * Resolve the artifacts for the plugin ids missing from the registry and stage them under
     * {@link #autoResolvePluginsFolder}. Runs outside any lock (network + disk only); the actual
     * registration happens later under the {@code HopEnvironment} lock.
     */
    private AutoResolve autoResolveMissingPlugins(final Set<String> transformIds, final Set<String> actionIds) {
        final var registry = PluginRegistry.getInstance();
        final var knownTransforms = knownIds(registry, TransformPluginType.class);
        final var knownActions = knownIds(registry, ActionPluginType.class);

        final var toResolveTransforms = new LinkedHashSet<String>(transformIds);
        toResolveTransforms.removeAll(knownTransforms);
        final var toResolveActions = new LinkedHashSet<String>(actionIds);
        toResolveActions.removeAll(knownActions);

        if (toResolveTransforms.isEmpty() && toResolveActions.isEmpty()) {
            return null;
        }

        final var version = autoResolveVersion != null && !autoResolveVersion.isBlank()
                ? autoResolveVersion.trim()
                : Const.getHopVersion();
        if (version == null || version.isBlank()) {
            getLog().warn("Unable to detect the Hop version to resolve the plugin artifacts against;"
                    + " set the autoResolveVersion parameter.");
            return null;
        }

        final var base = pluginsFolder();
        final List<RemoteRepository> repositories = project != null && project.getRemoteProjectRepositories() != null
                ? project.getRemoteProjectRepositories()
                : List.of();
        // aligned on Maven's own resolver parallelism (maven.artifact.threads);
        // ConfigUtils is the non-deprecated equivalent of ExecutorUtils.threadCount (deprecated in resolver 2.x)
        // and behaves identically on resolver 1.9.x (Maven 3.9) and 2.x (Maven 3.10/4);
        // Math.max(1,..) preserves ExecutorUtils' validation that the result is at least 1.
        final var resolutionThreads = repositorySystemSession == null
                ? 1
                : Math.max(
                        1,
                        ConfigUtils.getInteger(
                                repositorySystemSession,
                                1,
                                "aether.dependencyCollector.bf.threads",
                                "maven.artifact.threads"));
        getLog().debug("Auto-resolve: version='" + version + "', threads=" + resolutionThreads + ", staging base='"
                + base + "', repositories=" + repositories.size());
        getLog().debug("Auto-resolve: " + toResolveTransforms.size() + " transform id(s) to resolve: "
                + toResolveTransforms + ", " + toResolveActions.size() + " action id(s) to resolve: "
                + toResolveActions);
        final var registrar = new io.yupiik.hop.maven.plugin.HopPluginRegistrar(
                repositorySystem,
                repositorySystemSession,
                repositories,
                base,
                registry,
                new io.yupiik.hop.maven.plugin.HopPluginArtifactMapper(
                        transform2ArtifactMapping, action2ArtifactMapping, getLog()::debug),
                version,
                resolutionThreads,
                null,
                getLog()::debug);
        try {
            final var staged = registrar.resolveAndStage(toResolveTransforms, toResolveActions, Set.of(), Set.of());
            if (!toResolveTransforms.isEmpty() || !toResolveActions.isEmpty()) {
                getLog().info("Auto-resolved "
                        + (toResolveTransforms.size()
                                - staged.getMissingTransformIds().size())
                        + " transform(s) and "
                        + (toResolveActions.size()
                                - staged.getMissingActionIds().size())
                        + " action(s) into " + base);
            }
            return new AutoResolve(registrar, staged);
        } catch (final RuntimeException e) {
            getLog().warn(
                            "Unable to auto-resolve the Hop plugin artifacts: " + e.getMessage()
                                    + "; add the matching hop-transform-*/hop-action-* module(s) as dependencies of the plugin.",
                            e);
            return null;
        }
    }

    private static Set<String> knownIds(final PluginRegistry registry, final Class<? extends IPluginType> pluginType) {
        final var ids = new HashSet<String>();
        for (final var plugin : registry.getPlugins(pluginType)) {
            for (final var id : plugin.getIds()) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private Path pluginsFolder() {
        final var fallback = Path.of(System.getProperty("java.io.tmpdir"), "hop-maven-plugin", "hop-plugins");
        if (autoResolvePluginsFolder == null) {
            getLog().warn("autoResolvePluginsFolder is not set, falling back to " + fallback);
            return fallback;
        }
        return autoResolvePluginsFolder.toPath().toAbsolutePath().normalize();
    }

    /**
     * Collect the {@code .hpl}/{@code .hwf} files under {@code source} honoring {@link #recursive},
     * {@link #includes} and {@link #excludes}.
     */
    protected List<Path> collectDiagramFiles(final Path source) throws MojoExecutionException {
        final var files = new ArrayList<Path>();
        final var includesPatterns = AntGlob.compile(includes);
        final var excludesPatterns = AntGlob.compile(excludes);
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    return !recursive && !dir.equals(source) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (isSupported(file)
                            && matchesIncludesExcludes(source, file, includesPatterns, excludesPatterns)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            throw new MojoExecutionException("Error scanning " + source, e);
        }
        return files;
    }

    protected boolean isSupported(final Path file) {
        final var lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".hpl") || lower.endsWith(".hwf");
    }

    private boolean matchesIncludesExcludes(
            final Path root,
            final Path file,
            final AntGlob.Compiled includesPatterns,
            final AntGlob.Compiled excludesPatterns) {
        final var rel = root.relativize(file).toString().replace('\\', '/');
        if (includesPatterns.isEmpty()) {
            return excludesPatterns.isEmpty() || !excludesPatterns.matches(rel);
        }
        if (!includesPatterns.matches(rel)) {
            return false;
        }
        return excludesPatterns.isEmpty() || !excludesPatterns.matches(rel);
    }

    protected IHopMetadataProvider hopMetadataProvider(final Path metadata, final Variables variables) {
        final var jsonProvider = new JsonMetadataProvider(Encr.getEncoder(), metadata.toString(), variables);
        return new MultiMetadataProvider(Encr.getEncoder(), List.of(jsonProvider), variables);
    }

    protected void injectMavenProjectProperties(final Variables variables) {
        if (project != null && project.getProperties() != null) {
            project.getProperties().forEach((key, value) -> {
                if (key != null && value != null) {
                    variables.setVariable(String.valueOf(key), String.valueOf(value));
                }
            });
        }
    }

    protected void injectConfigVariables(final Variables variables) {
        if (configFile == null || !configFile.isFile()) {
            getLog().debug("No config file, skipping variable injection");
            return;
        }
        try {
            final var content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            final var root = JsonParser.parseString(content).getAsJsonObject();
            final var varsArray =
                    root.has("config") && root.getAsJsonObject("config").has("variables")
                            ? root.getAsJsonObject("config").getAsJsonArray("variables")
                            : root.getAsJsonArray("variables");
            if (varsArray == null) {
                return;
            }
            var count = 0;
            for (final var element : varsArray) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                final var entry = element.getAsJsonObject();
                final var name = entry.has("name") ? entry.get("name").getAsString() : null;
                if (name != null && entry.has("value")) {
                    variables.setVariable(name, entry.get("value").getAsString());
                    count++;
                }
            }
            getLog().info("Injected " + count + " variable(s) from " + configFile);
        } catch (final Exception e) {
            throw new IllegalStateException("Can't read variables from " + configFile, e);
        }
    }

    protected Path toPath(final java.io.File file, final String name) throws MojoExecutionException {
        if (file == null) {
            throw new MojoExecutionException("Parameter '" + name + "' must be set");
        }
        return file.toPath().toAbsolutePath().normalize();
    }

    protected record AutoResolve(
            io.yupiik.hop.maven.plugin.HopPluginRegistrar registrar,
            io.yupiik.hop.maven.plugin.HopPluginRegistrar.Staged staged) {}
}
