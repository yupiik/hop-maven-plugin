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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the run-family goals ({@code run}, {@code gui}, {@code server}, {@code encrypt}):
 * launcher basics and the argument/option precedence rules (user-provided options always win over
 * the automatic project activation; manual {@code -f} mode; {@code -r} precedence).
 */
class DistributionExecuteCommandsTest {

    @Test
    void goalBasics() {
        assertEquals("hop-run", new RunMojo().scriptBaseName());
        assertEquals("hop-gui", new GuiMojo().scriptBaseName());
        assertEquals("hop-server", new ServerMojo().scriptBaseName());
        assertEquals("hop-encrypt", new EncryptMojo().scriptBaseName());
        assertEquals("hop/run", workdirName(new RunMojo()));
        assertEquals("hop/gui", workdirName(new GuiMojo()));
        assertEquals("hop/server", workdirName(new ServerMojo()));
        assertEquals("hop/encrypt", workdirName(new EncryptMojo()));
        assertTrue(new RunMojo().defaultWaitFor(), "run waits by default");
        assertFalse(new GuiMojo().defaultWaitFor(), "gui does not wait");
        assertFalse(new ServerMojo().defaultWaitFor(), "server does not wait");
        assertTrue(new EncryptMojo().defaultWaitFor(), "encrypt waits");
    }

    private static String workdirName(final BaseDistributionExecuteCommand mojo) {
        return mojo.workdirName();
    }

    @Test
    void runTypedPipelineAppendsFileRunConfigParametersAndAutoProject() {
        final var mojo = new RunMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.pipeline = "utilities/oracle_sync/my.hpl";
        mojo.runConfig = "local";
        mojo.parameters = java.util.Map.of("env", "dev");

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(
                List.of(
                        "-f", "utilities/oracle_sync/my.hpl",
                        "-r", "local",
                        "-p", "env=dev",
                        "--project", "ORA_SYNC_HOP"),
                args);
    }

    @Test
    void runManualFileInArgsSkipsOwnFileAndAutoProjectAndDoesNotThrow() {
        final var mojo = new RunMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.args = List.of("-f", "/tmp/pipeline.hpl"); // manual mode
        mojo.pipeline = null;
        mojo.workflow = null;

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("-f", "/tmp/pipeline.hpl"), args);
    }

    @Test
    void runWithoutFileThrows() {
        final var mojo = new RunMojo();
        assertThrows(IllegalStateException.class, () -> mojo.runArguments(Path.of("/x")));
    }

    @Test
    void runTypedWorkflowKeepsAutoProjectButArgsProjectWins() {
        final var mojo = new RunMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.workflow = "jobs/main.hwf";
        mojo.args = List.of("--project", "OTHER_PROJECT");

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("-f", "jobs/main.hwf", "--project", "OTHER_PROJECT"), args);
    }

    @Test
    void runProjectParameterOverridesRegisteredProject() {
        final var mojo = new RunMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.projectName = "MY_PROJECT";
        mojo.pipeline = "p.hpl";

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("-f", "p.hpl", "--project", "MY_PROJECT"), args);
    }

    @Test
    void runRunConfigArgumentWinsOverParameter() {
        final var mojo = new RunMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.pipeline = "p.hpl";
        mojo.runConfig = "fromParam";
        mojo.args = List.of("--runconfig", "fromArgs");

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("-f", "p.hpl", "--runconfig", "fromArgs", "--project", "ORA_SYNC_HOP"), args);
    }

    @Test
    void guiAppendsJProjectWhenRegistered() {
        final var mojo = new GuiMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("-j", "ORA_SYNC_HOP"), args);
    }

    @Test
    void guiDoesNotAppendProjectWhenUserProvidedOneOrFile() {
        final var withProject = new GuiMojo();
        setRegisteredProject(withProject, "ORA_SYNC_HOP");
        withProject.args = List.of("--project", "user-chosen");
        assertEquals(List.of("--project", "user-chosen"), withProject.runArguments(Path.of("/x")));

        final var withFile = new GuiMojo();
        setRegisteredProject(withFile, "ORA_SYNC_HOP");
        withFile.args = List.of("-f", "/tmp/x.hpl");
        assertEquals(List.of("-f", "/tmp/x.hpl"), withFile.runArguments(Path.of("/x")));
    }

    @Test
    void guiProjectParameterWinsAndUsesJOption() {
        final var mojo = new GuiMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.projectName = "MY_PROJECT";

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("-j", "MY_PROJECT"), args);
    }

    @Test
    void serverAppendsProject() {
        final var mojo = new ServerMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("--project", "ORA_SYNC_HOP"), args);
    }

    @Test
    void encryptNeverAppendsProject() {
        final var mojo = new EncryptMojo();
        setRegisteredProject(mojo, "ORA_SYNC_HOP");
        mojo.args = List.of("my-password");

        final var args = mojo.runArguments(Path.of("/x"));

        assertEquals(List.of("my-password"), args);
    }

    @Test
    void noProjectRegisteredMeansNoActivationArgument() {
        final var gui = new GuiMojo();
        assertEquals(List.of(), gui.runArguments(Path.of("/x")));
        final var run = new RunMojo();
        run.pipeline = "p.hpl";
        assertEquals(List.of("-f", "p.hpl"), run.runArguments(Path.of("/x")));
    }

    private static void setRegisteredProject(final BaseDistributionExecuteCommand mojo, final String name) {
        mojo.registeredProjectName = name;
    }
}
