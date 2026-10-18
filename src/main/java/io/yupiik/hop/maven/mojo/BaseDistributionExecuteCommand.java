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

import io.yupiik.hop.maven.distribution.SecureUnzip;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Base for the goals that assemble a Hop distribution in a working directory and then execute one of
 * its {@code hop-*.sh}/.bat launchers. Reuses the whole distribution-assembly base of
 * {@link BuildMojo} ({@link #resolveDistributionZip(Path)}, {@link #newResolver()}) and only adds
 * the "unpack, merge a repository, run a script" concern.
 * <p>
 * Subclasses implement {@link #scriptBaseName()} ({@code hop-run}, {@code hop-encrypt}, ...) and may
 * override {@link #runArguments(Path)} to append goal-specific arguments.
 */
public abstract class BaseDistributionExecuteCommand extends BuildMojo {

    /**
     * Coordinates of the distribution to run, {@code groupId:artifactId:version[:type[:classifier]]},
     * resolved through the Maven resolver. When blank the plugin falls back to the current project
     * artifact if its packaging is a zip.
     */
    @Parameter(property = "hop.run.distribution")
    protected String distribution;

    /**
     * Folder whose content is merged onto the exploded distribution before executing (typically the
     * Hop project home with {@code project-config.json}, {@code repositories/} and {@code metadata/}).
     */
    @Parameter(property = "hop.run.repository")
    protected File repository;

    /**
     * Extra environment variables set for the launched process.
     */
    @Parameter
    protected Map<String, String> environmentVariables = Map.of();

    /**
     * Additional arguments appended to the launcher command line.
     */
    @Parameter
    protected List<String> args = List.of();

    /**
     * Whether to wait for the launched process to exit before returning. When left unset the
     * goal-specific {@link #defaultWaitFor()} applies ({@code false} for {@code gui}/{@code server}).
     */
    @Parameter(property = "hop.run.waitFor")
    protected Boolean waitFor;

    /**
     * Fail the build when the launched process exits with a non-zero code (only when waiting).
     */
    @Parameter(property = "hop.run.failOnError", defaultValue = "true")
    protected boolean failOnError = true;

    /**
     * @return the default wait behaviour for this goal when {@link #waitFor} is not explicit.
     */
    protected boolean defaultWaitFor() {
        return true;
    }

    @java.lang.Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final var workdir = workdirPath();
        final var exploded = workdir.resolve("exploded");
        try {
            final var zip = resolveDistributionZip(workdir);
            SecureUnzip.unzip(zip, exploded, true);
            getLog().info("Unpacked " + zip + " into " + exploded);
            mergeRepository(exploded);
            runScript(exploded);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to prepare or run the Hop distribution: " + e.getMessage(), e);
        }
    }

    /**
     * @return the default workdir folder name under {@code target/} (e.g. {@code hop-run}).
     */
    @java.lang.Override
    protected String workdirName() {
        return "hop-run";
    }

    /**
     * @return the launcher base name without extension, e.g. {@code hop-run}.
     */
    protected abstract String scriptBaseName();

    /**
     * @return goal-specific arguments for the launcher (may be overridden by subclasses).
     */
    protected List<String> runArguments(final Path explodedRoot) {
        return args == null ? List.of() : args;
    }

    /**
     * Resolve the distribution zip from the {@link #distribution} coordinate, the current project zip
     * artifact when blank, or the remote {@code versionUrl} when neither is available.
     */
    @java.lang.Override
    protected Path resolveDistributionZip(final Path workdir) throws IOException {
        final var coordinate = effectiveDistribution();
        if (coordinate == null || coordinate.isBlank()) {
            final var projectZip = new File(project.getBuild().getDirectory(),
                    project.getBuild().getFinalName() + ".zip").toPath();
            if (Files.isRegularFile(projectZip)) {
                getLog().info("Using locally built distribution " + projectZip);
                return projectZip;
            }
            return super.resolveDistributionZip(workdir); // remote versionUrl, logs "Downloading ..."
        }
        getLog().info("Using distribution coordinate " + coordinate);
        return newResolver().resolve(io.yupiik.hop.maven.resolve.Coordinates.of(coordinate));
    }

    private String effectiveDistribution() {
        if (distribution != null && !distribution.isBlank()) {
            return distribution.trim();
        }
        return null;
    }

    private void mergeRepository(final Path exploded) throws IOException {
        final var repo = repository != null
                ? repository.toPath().toAbsolutePath().normalize()
                : null;
        if (repo == null || !Files.isDirectory(repo)) {
            getLog().debug("repository '" + repo + "' is not a folder, skipping the merge");
            return;
        }
        try (var stream = Files.walk(repo)) {
            for (final var source : stream.filter(Files::isRegularFile).toList()) {
                final var rel = repo.relativize(source).toString().replace('\\', '/');
                final var dest = exploded.resolve(rel).normalize();
                if (!dest.startsWith(exploded.normalize())) {
                    throw new IOException("Repository entry escapes the distribution: " + rel);
                }
                Files.createDirectories(dest.getParent());
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                if (rel.endsWith(".sh")) {
                    dest.toFile().setExecutable(true, false);
                }
            }
        }
        getLog().info("Merged repository " + repo + " into " + exploded);
    }

    private void runScript(final Path exploded) throws IOException, MojoExecutionException {
        final var windows = isWindows();
        final var scriptName = scriptBaseName() + (windows ? ".bat" : ".sh");
        final var script = exploded.resolve(scriptName);
        if (!Files.isRegularFile(script)) {
            throw new IOException("Cannot find the launcher " + script + " in the distribution");
        }
        if (!windows) {
            script.toFile().setExecutable(true, false);
        }
        final var command = new ArrayList<String>();
        command.add(script.toString());
        command.addAll(runArguments(exploded));

        final var env = new HashMap<String, String>();
        env.putAll(environmentVariables == null ? emptyMap() : environmentVariables);

        final var wait = waitFor != null ? waitFor : defaultWaitFor();
        getLog().info("Executing " + String.join(" ", command) + " in " + exploded);
        final var builder = new ProcessBuilder(command);
        builder.directory(exploded.toFile());
        builder.environment().putAll(env);
        if (wait) {
            // when waiting, inherit the console so the launcher output is visible and searchable
            builder.inheritIO();
        } else {
            // when launched in background the child must NOT keep the parent's stdout/stderr pipes open,
            // otherwise Maven would never exit; redirect to a dedicated log file instead.
            final var log = exploded.getParent().resolve(scriptBaseName() + ".log");
            builder.redirectOutput(log.toFile());
            builder.redirectError(log.toFile());
            getLog().info("Redirecting background output to " + log);
        }
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException e) {
            throw new IOException("Unable to start " + scriptName + ": " + e.getMessage(), e);
        }
        if (!wait) {
            getLog().info("Launched " + scriptName + " in background (waitFor=false): " + process.pid());
            return;
        }
        final int exit;
        try {
            exit = process.waitFor();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for " + scriptName, e);
        }
        if (exit != 0 && failOnError) {
            throw new MojoExecutionException(scriptName + " exited with code " + exit);
        }
        getLog().info(scriptName + " exited with code " + exit);
    }

    private static boolean isWindows() {
        final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}