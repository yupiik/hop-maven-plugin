/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
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
 * Launches the Hop server ({@code hop-server.sh} / {@code hop-server.bat}) of a resolved
 * distribution. By default it is started in the background ({@code waitFor=false}); stop it with the
 * {@code server-stop} goal which sends the authenticated {@code /shutdown} request.
 * <p>
 * When a Hop project home is merged (the default {@code src/main/repositories} or an explicit
 * {@code repository} containing a root {@code project-config.json}), the project is registered in
 * the exploded distribution {@code config/hop-config.json} and activated with
 * {@code --project <name>} (the server launcher parses it like {@code hop-run}).
 */
@Mojo(name = "server", requiresProject = true, threadSafe = false)
public class ServerMojo extends BaseDistributionExecuteCommand {

    @java.lang.Override
    protected boolean defaultWaitFor() {
        return false; // long-running server, stopped via server-stop
    }

    @java.lang.Override
    protected String scriptBaseName() {
        return "hop-server";
    }
}
