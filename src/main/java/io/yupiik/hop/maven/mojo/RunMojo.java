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

import static java.util.Collections.emptyMap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Resolves a Hop distribution (either the one built by the {@code build} goal and attached as a zip,
 * or a distribution coordinate), unpacks it in the working directory, merges the configured
 * {@link #repository} on top (so {@code project-config.json}, {@code repositories/} and
 * {@code metadata/} land where Hop expects them) and then runs a pipeline or workflow via
 * {@code hop-run.sh} / {@code hop-run.bat}.
 */
@Mojo(name = "run", requiresProject = true, threadSafe = false)
public class RunMojo extends BaseDistributionExecuteCommand {

    /**
     * Path to the pipeline ({@code .hpl}) to run, relative to the merged {@link #repository}.
     */
    @Parameter(property = "hop.run.pipeline")
    protected String pipeline;

    /**
     * Path to the workflow ({@code .hwf}) to run, relative to the merged {@link #repository}.
     */
    @Parameter(property = "hop.run.workflow")
    protected String workflow;

    /**
     * Run configuration name to use.
     */
    @Parameter(property = "hop.run.runConfig")
    protected String runConfig;

    /**
     * Pipeline/workflow parameters ({@code name -> value}), passed as {@code -p name=value}.
     */
    @Parameter
    protected Map<String, String> parameters = emptyMap();

    @java.lang.Override
    protected String scriptBaseName() {
        return "hop-run";
    }

    @java.lang.Override
    protected List<String> runArguments(final Path explodedRoot) {
        final var result = new ArrayList<String>();
        final var file = fileToRun();
        if (file == null) {
            throw new IllegalStateException("Set the 'pipeline' or 'workflow' parameter to run");
        }
        result.add("-f");
        result.add(file);
        if (runConfig != null && !runConfig.isBlank()) {
            result.add("-r");
            result.add(runConfig.trim());
        }
        if (parameters != null) {
            for (final var entry : parameters.entrySet()) {
                result.add("-p");
                result.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        result.addAll(super.runArguments(explodedRoot));
        return result;
    }

    private String fileToRun() {
        if (pipeline != null && !pipeline.isBlank()) {
            return pipeline.trim();
        }
        if (workflow != null && !workflow.isBlank()) {
            return workflow.trim();
        }
        return null;
    }
}
