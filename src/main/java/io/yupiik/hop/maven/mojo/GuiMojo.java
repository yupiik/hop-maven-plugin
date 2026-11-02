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

import org.apache.maven.plugins.annotations.Mojo;

/**
 * Launches the Hop GUI ({@code hop-gui.sh} / {@code hop-gui.bat}) of a resolved distribution. By
 * default the GUI is started in the background ({@code waitFor=false}) and the user closes the
 * window to stop it.
 * <p>
 * When a Hop project home is merged (the default {@code src/main/repositories} or an explicit
 * {@code repository} containing a root {@code project-config.json}), the project is registered in
 * the exploded distribution {@code config/hop-config.json} (project name from the JSON {@code name}
 * field, else the repository folder name; home = the canonical {@code config/projects/<name>}
 * folder) and made the {@code defaultProject}, then the GUI is started with {@code -j <name>} so
 * the project and its pipelines/actions are active on startup. Only the workdir copy is written,
 * never the source project.
 */
@Mojo(name = "gui", requiresProject = true, threadSafe = true)
public class GuiMojo extends BaseDistributionExecuteCommand {

    @java.lang.Override
    protected boolean defaultWaitFor() {
        return false; // a GUI is stopped by closing its window
    }

    @java.lang.Override
    protected String scriptBaseName() {
        return "hop-gui";
    }

    /**
     * The GUI recognizes {@code -j} as the project option (the picocli run/server commands use
     * {@code --project}).
     */
    @java.lang.Override
    protected String projectOption() {
        return "-j";
    }
}
