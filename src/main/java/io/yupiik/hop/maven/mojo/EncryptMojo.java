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
 * Runs the {@code hop-encrypt.sh} / {@code hop-encrypt.bat} launcher of a Hop distribution to
 * (de)crypt a password string. Reuses the whole distribution assembly + repository merge inherited
 * from {@link BaseDistributionExecuteCommand}; the strings to (de)crypt are passed through
 * {@link #args}. The project registration still applies (so the merged project is available) but no
 * project activation argument is appended: the {@code hop-encrypt} launcher has no project option.
 */
@Mojo(name = "encrypt", requiresProject = true, threadSafe = false)
public class EncryptMojo extends BaseDistributionExecuteCommand {

    @java.lang.Override
    protected String scriptBaseName() {
        return "hop-encrypt";
    }

    @java.lang.Override
    protected boolean supportsProjectOption() {
        return false; // hop-encrypt does not accept a project option
    }
}
