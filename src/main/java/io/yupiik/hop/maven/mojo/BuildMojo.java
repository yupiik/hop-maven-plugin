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

import io.yupiik.hop.maven.distribution.DistributionBuilder;
import io.yupiik.hop.maven.distribution.DistributionResolver;
import io.yupiik.hop.maven.distribution.SecureUnzip;
import io.yupiik.hop.maven.distribution.ZipPackager;
import io.yupiik.hop.maven.resolve.ArtifactResolver;
import io.yupiik.hop.maven.resolve.Coordinates;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.settings.Settings;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Assembles a custom Apache Hop distribution.
 * <p>
 * It downloads (or resolves through Maven) the configured Hop client zip, unzips it safely into a
 * {@code hop/} folder under {@link #workdir}, enriches it with extra JDBC drivers, libs, javaagent
 * jars, custom transforms, marketplace plugins and in-place jar overrides, then merges the
 * {@link #baseDirectory} content on top (so user files win). Finally it packages the result as a zip
 * and optionally attaches it to the build.
 * <p>
 * This mojo is also the base for the {@code run}/{@code encrypt}/{@code gui}/{@code server} goals,
 * which reuse the very same distribution assembly and only differ in the script they execute.
 */
@Mojo(name = "build", requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.RUNTIME,
        threadSafe = true)
public class BuildMojo extends BaseHopMojo {

    /**
     * Version of the Hop distribution targeted by {@link #versionUrl} ({@code ${version}} placeholder).
     */
    @Parameter(property = "hop.build.version")
    protected String hopVersion;

    /**
     * Location of the Hop distribution to enrich. When it contains a {@code /} it is an HTTP(S) URL
     * with an optional {@code ${hop.version}} placeholder (a {@code ${version}} placeholder is also
     * accepted when the value is passed raw via {@code -D} on the CLI); otherwise it is a Maven
     * coordinate ({@code groupId:artifactId:version[:type[:classifier]]}) resolved through the Maven
     * resolver. The placeholders are replaced with {@link #hopVersion}.
     */
    @Parameter(property = "hop.build.versionUrl",
            defaultValue = "https://downloads.apache.org/hop/${hop.version}/apache-hop-client-${hop.version}.zip")
    protected String versionUrl;

    /**
     * Folder whose content is copied relatively on top of the assembled {@code hop/} distribution
     * (injecting or overriding configuration). Defaults to {@code src/main/hop/distribution}.
     */
    @Parameter(property = "hop.build.baseDirectory",
            defaultValue = "${project.basedir}/src/main/hop/distribution")
    protected File baseDirectory;

    /**
     * Working directory where the distribution zip is downloaded and unzipped.
     */
    @Parameter(property = "hop.build.workdir")
    protected File workdir;

    /**
     * Folder where the assembled distribution is written before the enrichment (the {@code hop/}
     * folder lives underneath {@link #workdir}).
     */
    @Parameter(property = "hop.build.hopFolder", defaultValue = "${project.build.directory}/hop-build/hop")
    protected File hopFolder;

    /**
     * Strip the leading root folder when unzipping the distribution (canonical Hop layout). The
     * upstream zip has a {@code hop/} root so this defaults to {@code true}.
     */
    @Parameter(property = "hop.build.skipRootFolderOnUnzip", defaultValue = "true")
    protected boolean skipRootFolderOnUnzip;

    /**
     * Output zip of the assembled distribution.
     */
    @Parameter(property = "hop.build.zipFile",
            defaultValue = "${project.build.directory}/${project.build.finalName}.zip")
    protected File zipFile;

    /**
     * Whether to attach the packaged distribution to the build.
     */
    @Parameter(property = "hop.build.attach", defaultValue = "true")
    protected boolean attach;

    /**
     * Classifier used when attaching the packaged distribution.
     */
    @Parameter(property = "hop.build.classifier")
    protected String classifier;

    /**
     * Whether to package the assembled distribution as a zip.
     */
    @Parameter(property = "hop.build.package", defaultValue = "true")
    protected boolean packageDistribution;

    /**
     * Extra JDBC driver coordinates copied into {@code hop/lib/jdbc}.
     */
    @Parameter
    protected List<String> drivers = emptyList();

    /**
     * Extra library coordinates copied into {@code hop/lib}.
     */
    @Parameter
    protected List<String> libs = emptyList();

    /**
     * Extra {@code -javaagent} coordinates copied into {@code hop/lib} and injected into the scripts.
     * Each may carry {@code =args} appended to the javaagent.
     */
    @Parameter
    protected List<String> javaagents = emptyList();

    /**
     * Custom transform coordinates resolved from Maven and installed into {@code hop/plugins/transforms}.
     */
    @Parameter
    protected List<String> transforms = emptyList();

    /**
     * Marketplace/plugin coordinates resolved from Maven and installed into the distribution
     * {@code plugins} tree, with the category derived from the artifactId.
     */
    @Parameter
    protected List<String> plugins = emptyList();

    /**
     * In-place jar overrides: a relative path inside the distribution replaced by a resolved
     * dependency coordinate.
     */
    @Parameter
    protected List<Override> overrides = emptyList();

    /**
     * Map of additional headers to send on the download request; the {@code username}/{@code password}
     * credentials (or the matching settings.xml server) take precedence for Authorization.
     */
    @Parameter
    protected Map<String, String> headers = emptyMap();

    /**
     * Explicit username for the download (basic auth, or the header name for a bearer token).
     */
    @Parameter(property = "hop.build.username")
    protected String username;

    /**
     * Explicit password for the download. When it starts with {@code Bearer} it is used as a bearer
     * token (the username becomes the header name, default {@code Authorization}).
     */
    @Parameter(property = "hop.build.password")
    protected String password;

    /**
     * The current Maven settings (used to find a matching {@code server} for the download host).
     */
    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    /**
     * Whether a distribution downloaded from a URL is cached into the Maven local repository so later
     * runs resolve it without re-downloading. The cache uses the synthetic coordinates
     * {@code io.yupiik.hop.cache:<filename>:zip:<url-hash>:<version>}.
     */
    @Parameter(property = "hop.build.cacheDistribution", defaultValue = "true")
    protected boolean cacheDistribution;

    /**
     * Timeout in minutes for downloading a distribution from a URL. Large distributions on slow links
     * may need more than the default.
     */
    @Parameter(property = "hop.build.downloadTimeoutMinutes", defaultValue = "15")
    protected long downloadTimeoutMinutes;

    @Inject
    protected MavenProjectHelper projectHelper;

    /**
     * Assemble the distribution and return the {@code hop/} root. Shared by {@code build} and the
     * {@code run}/{@code encrypt}/{@code gui}/{@code server} goals.
     */
    protected Path assembleDistribution() throws MojoExecutionException {
        final var baseDir = baseDirectory != null
                ? baseDirectory.toPath().toAbsolutePath().normalize()
                : null;
        final var wd = workdirPath();
        final var hop = hopFolder != null
                ? hopFolder.toPath().toAbsolutePath().normalize()
                : wd.resolve("hop");
        try {
            final var zip = resolveDistributionZip(wd);
            SecureUnzip.unzip(zip, hop, skipRootFolderOnUnzip);
            getLog().info("Unzipped " + zip + " into " + hop);

            final var resolver = newResolver();
            final var builder = new DistributionBuilder(resolver, hop, getLog()::debug);
            builder.enrich(new DistributionBuilder.EnrichmentSpec(
                    drivers, libs, javaagents, transforms, plugins,
                    toOverrides(), baseDir));
            return hop;
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to assemble the Hop distribution", e);
        }
    }

    /**
     * @return the resolved working directory, defaulting to {@code <project.build.directory>/<name>}
     * where {@link #workdirName()} defaults to {@code hop-build}. Subclasses override {@link #workdirName()}.
     */
    protected Path workdirPath() throws MojoExecutionException {
        if (workdir != null) {
            return toPath(workdir, "workdir");
        }
        return new File(project.getBuild().getDirectory(), workdirName()).toPath().toAbsolutePath().normalize();
    }

    /**
     * @return the default working directory folder name under {@code target/}.
     */
    protected String workdirName() {
        return "hop-build";
    }

    /**
     * Resolve the distribution zip file (HTTP download or Maven coordinate) into {@code workdir}.
     * Subclasses override it to point at a locally built/computed distribution artifact.
     */
    protected Path resolveDistributionZip(final Path workdir) throws IOException {
        final var resolver = newResolverForDownload(workdir);
        return resolver.resolve(versionUrl);
    }

    private DistributionResolver newResolverForDownload(final Path workdir) {
        return new DistributionResolver(newResolver(), settings,
                effectiveVersion(), username, password, authorizationHeaderName(), workdir,
                getLog()::info, null, cacheDistribution, Duration.ofMinutes(downloadTimeoutMinutes));
    }

    private String authorizationHeaderName() {
        return headers != null && headers.containsKey("Authorization")
                ? "Authorization"
                : (headers != null && headers.containsKey("authorization") ? "Authorization" : null);
    }

    protected ArtifactResolver newResolver() {
        final var repos = project != null && project.getRemoteProjectRepositories() != null
                ? project.getRemoteProjectRepositories()
                : List.<org.eclipse.aether.repository.RemoteRepository>of();
        return new ArtifactResolver(repositorySystem, repositorySystemSession, repos);
    }

    private String effectiveVersion() {
        if (hopVersion != null && !hopVersion.isBlank()) {
            return hopVersion.trim();
        }
        return "2.19.0"; // fallback applied only for ${version} interpolation
    }

    private List<DistributionBuilder.OverrideEntry> toOverrides() {
        if (overrides == null || overrides.isEmpty()) {
            return List.of();
        }
        final var result = new ArrayList<DistributionBuilder.OverrideEntry>();
        for (final var override : overrides) {
            if (override == null || override.dependency == null) {
                continue;
            }
            result.add(new DistributionBuilder.OverrideEntry(
                    override.path,
                    Coordinates.of(overrideCoordinates(override.dependency)),
                    emptyMap()));
        }
        return result;
    }

    private static String overrideCoordinates(final Dependency dependency) {
        final var builder = new StringBuilder()
                .append(valueOr(dependency.groupId, "undefined"))
                .append(':').append(valueOr(dependency.artifactId, "undefined"))
                .append(':').append(valueOr(dependency.type, "jar"));
        if (dependency.classifier != null && !dependency.classifier.isBlank()) {
            builder.append(':').append(dependency.classifier);
        }
        builder.append(':').append(valueOr(dependency.version, "undefined"));
        return builder.toString();
    }

    private static String valueOr(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @java.lang.Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var hop = assembleDistribution();
        if (packageDistribution) {
            try {
                final var zip = zipFile.toPath().toAbsolutePath().normalize();
                ZipPackager.zip(hop, zip, hop.getFileName().toString());
                getLog().info("Packaged the Hop distribution into " + zip);
                if (attach) {
                    attach(zip);
                }
            } catch (final IOException e) {
                throw new MojoExecutionException("Unable to package the Hop distribution", e);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The distribution mojos do not process Hop diagram files; this no-op satisfies the
     * {@link BaseHopMojo} contract for the {@code build}/{@code run}/... goals which override
     * {@link #execute()} entirely.
     */
    @java.lang.Override
    protected void process(final List<Path> files, final Path source,
                           final org.apache.hop.metadata.api.IHopMetadataProvider provider,
                           final org.apache.hop.core.variables.Variables variables) {
        // no-op
    }

    private void attach(final Path zip) throws MojoExecutionException {
        if (!attach) {
            return;
        }
        getLog().info("Attaching the Hop distribution (" + zip + ")");
        try {
            if (classifier != null && !classifier.isBlank()) {
                projectHelper.attachArtifact(project, "zip", classifier, zip.toFile());
            } else {
                projectHelper.attachArtifact(project, "zip", zip.toFile());
            }
        } catch (final RuntimeException e) {
            throw new MojoExecutionException("Unable to attach the Hop distribution artifact", e);
        }
    }

    /**
     * An in-place override: a relative {@code path} inside the distribution replaced by a resolved
     * {@code dependency}.
     */
    public static class Override {
        @Parameter
        protected String path;

        @Parameter
        protected Dependency dependency;
    }

    /**
     * Coordinates of the overriding dependency.
     */
    public static class Dependency {
        @Parameter
        protected String groupId;

        @Parameter
        protected String artifactId;

        @Parameter
        protected String version;

        @Parameter(defaultValue = "jar")
        protected String type;

        @Parameter
        protected String classifier;
    }
}