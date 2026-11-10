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
package io.yupiik.hop.maven.mojo.configuration;

import java.util.List;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Final customization block of the distribution assembly: {@link #deletions} (ant-style globs
 * removed recursively) run first, then {@link #additions} (resolved dependencies copied at a
 * relative target path) run last so they win over the rest of the assembly.
 */
public class Customizations {

    /**
     * Ant-style glob patterns relative to the distribution root, deleted recursively (a matched
     * directory is removed entirely). {@code *} matches any non-{@code /} sequence, {@code **} any
     * sequence and {@code ?} a single non-{@code /} character. Runs before {@link #additions}.
     */
    @Parameter
    public List<String> deletions;

    /**
     * Files copied into the distribution after the deletions, so they win over deleted paths.
     */
    @Parameter
    public List<Addition> additions;
}
