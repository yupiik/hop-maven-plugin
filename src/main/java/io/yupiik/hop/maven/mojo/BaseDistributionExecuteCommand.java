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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.yupiik.hop.maven.distribution.SecureUnzip;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Base for the goals that assemble a Hop distribution in a working directory and then execute one of
 * its {@code hop-*.sh}/.bat launchers. Reuses the whole distribution-assembly base of
 * {@link BuildMojo} ({@link #resolveDistributionZip(Path)}, {@link #newResolver()}) and only adds
 * the "unpack, merge the Hop project home (default {@code src/main/repositories}), run a script"
 * concern.
 * <p>
 * Subclasses implement {@link #scriptBaseName()} ({@code hop-run}, {@code hop-encrypt}, ...) and may
 * override {@link #runArguments(Path)} to append goal-specific arguments.
 */
public abstract class BaseDistributionExecuteCommand extends BuildMojo {

    /**
     * Command-line options that select a Hop project, as recognized by the Hop launchers
     * ({@code -j}, {@code --project}, {@code -project} for the GUI; {@code --project} for the
     * picocli-based run/server commands). An option can be passed as {@code -j name},
     * {@code --project name} or {@code --project=name}.
     */
    protected static final List<String> PROJECT_OPTIONS = List.of("-j", "--project", "-project");

    /**
     * Command-line options that select a pipeline/workflow file ({@code -f} / {@code --file} /
     * {@code -file}), as used by {@code hop-run}.
     */
    protected static final List<String> FILE_OPTIONS = List.of("-f", "--file", "-file");

    /**
     * Command-line options that select a run configuration ({@code -r} / {@code --runconfig} /
     * {@code -runconfig}).
     */
    protected static final List<String> RUN_CONFIG_OPTIONS = List.of("-r", "--runconfig", "-runconfig");

    /**
     * Current mojo execution, used to tag the process output-drainer thread names with the execution
     * id (e.g. {@code hop-maven-plugin-[run-hop]-hop-run-stdout}) when available.
     */
    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    protected org.apache.maven.plugin.MojoExecution mojoExecution;

    /**
     * Coordinates of the distribution to run, {@code groupId:artifactId:version[:type[:classifier]]},
     * resolved through the Maven resolver. When blank the plugin falls back to the current project
     * artifact if its packaging is a zip.
     */
    @Parameter(property = "hop.run.distribution")
    protected String distribution;

    /**
     * Folder whose content is merged onto the exploded distribution before executing. When unset it
     * defaults to the conventional Hop project home {@code src/main/repositories} (skipped when that
     * folder does not exist). With the default home the merged content lands in
     * {@code config/projects/<name>} (canonical Hop project layout) so the launcher sees the project
     * home; with an explicit {@code repository} it lands at the exploded distribution root (legacy
     * layout, useful for overriding distribution files). An explicit {@code repository} whose content
     * contains a root {@code project-config.json} is registered and activated like the default home.
     */
    @Parameter(property = "hop.run.repository")
    protected File repository;

    /**
     * Name of the Hop project to activate. When set (and no {@code -j}/{@code --project} is present
     * in {@link #args}) it is appended to the launcher command line so the project defined by the
     * merged repository (or by any project registered in the distribution
     * {@code config/hop-config.json}) is active on startup. When unset the project registered from
     * the merged repository is used. The {@code encrypt} goal accepts the parameter but does not
     * append it: its launcher has no project option.
     */
    @Parameter(property = "hop.run.project")
    protected String projectName;

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
    protected boolean failOnError;

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
            if (zip == null) {
                // scratch/fake distribution: no base to unzip; warn and assemble only the custom
                // content (drivers, agents, plugins, baseDirectory) so a user can provide a launcher
                // e.g. via src/main/hop/distribution and have it made executable, just like build.
                getLog().warn("Scratch distribution: no base was unzipped; make sure a launcher such as "
                        + scriptBaseName() + (isWindows() ? ".bat" : ".sh")
                        + " is provided (via src/main/hop/distribution or the repository merge) for this goal to run.");
                Files.createDirectories(exploded);
                enrich(exploded);
            } else {
                SecureUnzip.unzip(zip, exploded, true);
                getLog().info("Unpacked " + zip + " into " + exploded);
            }
            final var merged = mergeRepository(workdir, exploded);
            afterMerge(exploded, merged);
            runScript(exploded);
        } catch (final IOException e) {
            throw new MojoExecutionException("Unable to prepare or run the Hop distribution: " + e.getMessage(), e);
        }
    }

    /**
     * @return the default workdir folder name under {@code target/}: {@code hop/<goal>} where the
     *         goal is the launcher name without the {@code hop-} prefix ({@code hop-run} ->
     *         {@code hop/run}). All Hop writes (config, audit, logs, UI state) stay inside this
     *         directory, never in the source project.
     */
    @java.lang.Override
    protected String workdirName() {
        final var script = scriptBaseName();
        return "hop/" + (script.startsWith("hop-") ? script.substring("hop-".length()) : script);
    }

    /**
     * @return the launcher base name without extension, e.g. {@code hop-run}.
     */
    protected abstract String scriptBaseName();

    /**
     * @return goal-specific arguments for the launcher: the configured {@link #args} plus the
     *         automatic project activation (unless the user already selected a project or file).
     */
    protected List<String> runArguments(final Path explodedRoot) {
        final var result = new ArrayList<String>(args == null ? List.of() : args);
        appendProjectActivation(result);
        return result;
    }

    /**
     * Appends the project activation option ({@link #projectOption()}) with the effective project
     * name ({@link #project} when set, else the project registered from the merged repository),
     * unless the user already passed a project or file option in {@link #args}: in that case the
     * user choice wins and nothing is appended.
     *
     * @param target command line being built (the {@link #args} are already in it).
     */
    protected void appendProjectActivation(final List<String> target) {
        final var name = effectiveProjectName();
        if (name == null
                || !supportsProjectOption()
                || hasOption(target, PROJECT_OPTIONS)
                || hasOption(target, FILE_OPTIONS)) {
            return; // user already selects the project (or runs a manual file): respect it
        }
        target.add(projectOption());
        target.add(name);
        if (getLog() != null) {
            getLog().info("Activating Hop project '" + name + "' (" + projectOption() + " " + name + ")");
        }
    }

    /**
     * @return the option used to activate a project: {@code -j} for the GUI, {@code --project} for
     *         the picocli-based run/server commands.
     */
    protected String projectOption() {
        return "--project";
    }

    /**
     * @return whether this goal's launcher accepts a project activation option. {@code encrypt} does
     *         not (its launcher has no project option), everything else does.
     */
    protected boolean supportsProjectOption() {
        return true;
    }

    /**
     * @return the project to activate: the explicit {@link #projectName} parameter when set, else
     *         the project registered from the merged repository, else {@code null}.
     */
    protected String effectiveProjectName() {
        if (projectName != null && !projectName.isBlank()) {
            return projectName.trim();
        }
        return registeredProjectName;
    }

    /**
     * @return whether {@code args} contains one of {@code options}, passed either as a single
     *         argument ({@code -j}) or with an inline value ({@code -j=name}).
     */
    protected static boolean hasOption(final List<String> args, final List<String> options) {
        if (args == null) {
            return false;
        }
        for (final var arg : args) {
            if (arg == null) {
                continue;
            }
            final var lower = arg.toLowerCase(Locale.ROOT);
            for (final var option : options) {
                if (lower.equals(option) || lower.startsWith(option + "=")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Name of the project registered from the merged repository ({@code null} until known or when
     * no project home was merged).
     */
    protected String registeredProjectName;

    /**
     * Hook invoked after the repository merge and before the launcher starts, with the exploded
     * distribution and the folder that was actually merged into it (the canonical
     * {@code config/projects/<name>} home for the default repository, the exploded root for an
     * explicit one; {@code null} when the merge was skipped). The base implementation registers the
     * project in the distribution {@code config/hop-config.json} when the merged folder contains a
     * {@code project-config.json} so the launcher can activate it; goals may override to add more
     * wiring (they should call {@code super.afterMerge} or re-implement the registration).
     */
    protected void afterMerge(final Path exploded, final Path mergedRepository) throws IOException {
        if (mergedRepository == null) {
            return;
        }
        final var projectConfig = mergedRepository.resolve("project-config.json");
        if (!Files.isRegularFile(projectConfig)) {
            if (getLog() != null) {
                getLog().debug("No project-config.json at " + mergedRepository + ", skipping the project registration");
            }
            return;
        }
        try {
            final var name = projectName(projectConfig, mergedRepository);
            registerProject(exploded, name, mergedRepository.toAbsolutePath().normalize());
            registeredProjectName = name;
        } catch (final RuntimeException e) {
            // never prevent the launcher from starting because the config could not be amended
            if (getLog() != null) {
                getLog().warn("Unable to register the Hop project: " + e.getMessage()
                        + "; the launcher will use its default project.");
            }
        }
    }

    /**
     * @return the project name from the {@code name} field of a merged {@code project-config.json},
     *         or the parent folder name when the field is missing or unreadable.
     */
    private String projectName(final Path projectConfig, final Path merged) throws IOException {
        try {
            final var root = JsonParser.parseString(Files.readString(projectConfig, UTF_8))
                    .getAsJsonObject();
            if (root.has("name") && !root.get("name").getAsString().isBlank()) {
                return root.get("name").getAsString().trim();
            }
        } catch (final RuntimeException e) {
            if (getLog() != null) {
                getLog().warn("Unable to read the project name from " + projectConfig
                        + ", falling back to the repository folder name: " + e.getMessage());
            }
        }
        final var fileName =
                merged.getFileName() == null ? "" : merged.getFileName().toString();
        return fileName.isBlank() ? "project" : fileName;
    }

    /**
     * Register {@code name} in the exploded distribution {@code config/hop-config.json}: the
     * {@code projectsConfig} entry is created/enabled, {@code defaultProject} is set and a
     * {@code projectConfigurations} entry ({@code projectHome} = the merged project home) is added
     * or updated. Existing keys are preserved. Only the workdir copy is written.
     */
    private void registerProject(final Path exploded, final String name, final Path home) throws IOException {
        final var configFile = exploded.resolve("config/hop-config.json");
        final var root = Files.isRegularFile(configFile)
                ? JsonParser.parseString(Files.readString(configFile, UTF_8)).getAsJsonObject()
                : new JsonObject();
        final var projectsConfig =
                root.has("projectsConfig") && root.get("projectsConfig").isJsonObject()
                        ? root.getAsJsonObject("projectsConfig")
                        : new JsonObject();
        if (!root.has("projectsConfig")) {
            root.add("projectsConfig", projectsConfig);
        }
        if (!projectsConfig.has("enabled")) {
            projectsConfig.addProperty("enabled", true);
        }
        projectsConfig.addProperty("defaultProject", name);
        final var configs = projectsConfig.has("projectConfigurations")
                        && projectsConfig.get("projectConfigurations").isJsonArray()
                ? projectsConfig.getAsJsonArray("projectConfigurations")
                : new JsonArray();
        var found = false;
        for (final var element : configs) {
            if (element != null && element.isJsonObject()) {
                final var entry = element.getAsJsonObject();
                if (entry.has("projectName")
                        && name.equals(entry.get("projectName").getAsString())) {
                    entry.addProperty("projectHome", home.toString());
                    entry.addProperty("configFilename", "project-config.json");
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            final var entry = new JsonObject();
            entry.addProperty("projectName", name);
            entry.addProperty("projectHome", home.toString());
            entry.addProperty("configFilename", "project-config.json");
            configs.add(entry);
        }
        projectsConfig.add("projectConfigurations", configs);
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, root.toString(), UTF_8);
        if (getLog() != null) {
            getLog().info("Registered project '" + name + "' in " + configFile + " (defaultProject=" + name + ", home="
                    + home + ")");
        }
    }

    /**
     * Resolve the distribution zip from the {@link #distribution} coordinate, the current project zip
     * artifact when blank, or the remote {@code versionUrl} when neither is available.
     */
    @java.lang.Override
    protected Path resolveDistributionZip(final Path workdir) throws IOException {
        final var coordinate = effectiveDistribution();
        if (coordinate == null || coordinate.isBlank()) {
            final var projectZip = new File(
                            project.getBuild().getDirectory(),
                            project.getBuild().getFinalName() + ".zip")
                    .toPath();
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

    /**
     * Merge the repository content onto the exploded distribution. The repository is the explicit
     * {@link #repository} parameter or, when unset, the conventional {@code src/main/repositories}
     * Hop project home; the merge is skipped (with a log) when that folder does not exist. VCS
     * folders (git/hg/svn), the Maven build directory and the run work directory are never copied.
     * <p>
     * With the default home the merged content lands in {@code config/projects/<name>} of the
     * exploded distribution - the canonical Hop project layout - where {@code <name>} is the
     * {@code name} field of the source {@code project-config.json} (sanitized) or the repository
     * folder name. With an explicit {@code repository} the content lands at the exploded
     * distribution root (legacy layout, useful to override distribution files).
     *
     * @return the exploded folder the repository was merged into (the canonical project home for
     *         the default repository), or {@code null} when the merge was skipped.
     */
    private Path mergeRepository(final Path workdir, final Path exploded) throws IOException {
        final var repo = effectiveRepository();
        if (!Files.isDirectory(repo)) {
            getLog().info("Repository '" + repo + "' does not exist, skipping the merge");
            return null;
        }
        final Path target;
        if (repository == null) {
            // default home: merge into the canonical Hop project layout so the launcher sees the
            // project home instead of the whole distribution
            final var sourceConfig = repo.resolve("project-config.json");
            final var name = sanitize(
                    Files.isRegularFile(sourceConfig)
                            ? projectName(sourceConfig, repo)
                            : repo.getFileName() == null
                                    ? "project"
                                    : repo.getFileName().toString());
            target = exploded.resolve("config/projects").resolve(name).normalize();
        } else {
            target = exploded; // legacy: merge at the distribution root
        }
        final var buildDir = project != null
                        && project.getBuild() != null
                        && project.getBuild().getDirectory() != null
                ? Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize()
                : null;
        final var workdirNorm = workdir.toAbsolutePath().normalize();
        final var explodedNorm = exploded.toAbsolutePath().normalize();
        final var targetNorm = target.toAbsolutePath().normalize();
        if (!targetNorm.startsWith(explodedNorm)) {
            throw new IOException("Merge target escapes the distribution: " + targetNorm);
        }
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {
            @java.lang.Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                if (!dir.equals(repo) && isExcluded(dir, buildDir, workdirNorm, explodedNorm)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @java.lang.Override
            public FileVisitResult visitFile(final Path source, final BasicFileAttributes attrs) throws IOException {
                final var rel = repo.relativize(source).toString().replace('\\', '/');
                final var dest = targetNorm.resolve(rel).normalize();
                if (!dest.startsWith(explodedNorm)) {
                    throw new IOException("Repository entry escapes the distribution: " + rel);
                }
                Files.createDirectories(dest.getParent());
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
                if (rel.endsWith(".sh")) {
                    dest.toFile().setExecutable(true, false);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        getLog().info("Merged repository " + repo + " into " + targetNorm);
        return targetNorm;
    }

    /**
     * @return a filesystem-safe project folder name derived from a project name.
     */
    private static String sanitize(final String name) {
        final var cleaned = name.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "project" : cleaned;
    }

    private Path effectiveRepository() {
        if (repository != null) {
            return repository.toPath().toAbsolutePath().normalize();
        }
        final var base = project != null && project.getBasedir() != null
                ? project.getBasedir().toPath()
                : Path.of("");
        return base.resolve("src/main/repositories").toAbsolutePath().normalize();
    }

    private boolean isExcluded(final Path dir, final Path buildDir, final Path workdir, final Path exploded) {
        final var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (".git".equals(name) || ".hg".equals(name) || ".svn".equals(name)) {
            return true;
        }
        final var normalized = dir.toAbsolutePath().normalize();
        return normalized.equals(buildDir) || normalized.equals(workdir) || normalized.equals(exploded);
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
        if (!wait) {
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
            final var stdout = streamLogger(scriptName, "stdout", process.getInputStream(), getLog()::info);
            final var stderr = streamLogger(scriptName, "stderr", process.getErrorStream(), getLog()::warn);
            try {
                exit = process.waitFor();
            } finally {
                for (final var drainer : new Thread[] {stdout, stderr}) {
                    try {
                        drainer.join();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for " + scriptName, e);
        }
        if (exit != 0 && failOnError) {
            throw new MojoExecutionException(scriptName + " exited with code " + exit);
        }
        getLog().info(scriptName + " exited with code " + exit);
    }

    /**
     * Starts a named virtual thread that reads {@code stream} line by line and forwards every line
     * to {@code log} prefixed with {@code [hop] }. The thread inherits the caller's context class
     * loader (the plugin realm) so any class loading performed while draining stays in the right
     * loader. Both stdout and stderr are drained concurrently to avoid the classic pipe-buffer
     * deadlock when the child writes more than the OS pipe capacity to one of them.
     */
    private Thread streamLogger(
            final String script, final String name, final InputStream stream, final Consumer<String> log) {
        final var executionId = mojoExecution == null ? null : mojoExecution.getExecutionId();
        final var threadName = "hop-maven-plugin-"
                + (executionId == null || executionId.isBlank() ? "" : "[" + executionId + "]-")
                + script + "-" + name;
        final var loader = Thread.currentThread().getContextClassLoader();
        return Thread.ofVirtual().name(threadName).start(() -> {
            final var previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                try (final var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.accept("[hop] " + line);
                    }
                } catch (final IOException e) {
                    // process ended: nothing left to read
                }
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        });
    }

    private static boolean isWindows() {
        final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
