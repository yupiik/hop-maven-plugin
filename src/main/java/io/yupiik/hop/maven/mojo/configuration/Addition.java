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
package io.yupiik.hop.maven.mojo.configuration;

import org.apache.maven.plugins.annotations.Parameter;

/**
 * A file added to the distribution: a Maven {@link #gav} resolved and copied at a relative
 * {@link #target} path. A target ending with {@code /} is a folder and the resolved file name is
 * appended inside it; any other target is the exact destination file path (for example
 * {@code lib/jdbc/foo.jar} writes the dependency as {@code lib/jdbc/foo.jar}).
 */
public class Addition {

    /**
     * The dependency to resolve, as {@code groupId:artifactId:type[:classifier]:version}.
     */
    @Parameter
    public String gav;

    /**
     * Relative destination path inside the distribution.
     */
    @Parameter
    public String target;
}
