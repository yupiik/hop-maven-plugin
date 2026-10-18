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

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Launches the Hop GUI ({@code hop-gui.sh} / {@code hop-gui.bat}) of a resolved distribution. By
 * default the GUI is started in the background ({@code waitFor=false}) and the user closes the
 * window to stop it.
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
}