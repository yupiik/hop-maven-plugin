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
package io.yupiik.hop.maven.documentation;

import java.io.IOException;
import java.io.Writer;

/**
 * Renders the {@code llms.txt} file following the llms.txt v2 proposal
 * (https://llmstxt.org): an H1 title, an optional blockquote summary, then {@code ##} file-list
 * sections with {@code - [name](url): notes} links pointing to the Markdown mirrors of each page.
 */
public final class LlmsTxtRenderer {

    private final String projectTitle;
    private final String summary;

    public LlmsTxtRenderer(final String projectTitle, final String summary) {
        this.projectTitle = projectTitle == null ? "Hop documentation" : projectTitle;
        this.summary = summary;
    }

    public void render(final IndexModel index, final Writer out) throws IOException {
        out.write("# " + projectTitle);
        out.write('\n');
        out.write('\n');
        if (summary != null && !summary.isBlank()) {
            out.write("> " + summary);
            out.write('\n');
            out.write('\n');
        }
        out.write("## Workflows (.hwf)\n");
        writeLinks(out, index.workflows());
        out.write('\n');
        out.write("## Pipelines (.hpl)\n");
        writeLinks(out, index.pipelines());
        out.write('\n');
    }

    private void writeLinks(final Writer out, final java.util.List<IndexModel.Entry> entries) throws IOException {
        for (final var entry : entries) {
            final var role = entry.role() == null || entry.role().isBlank() ? entry.description() : entry.role();
            out.write("- [" + entry.pageName() + "](" + entry.pageName() + ".md)");
            if (role != null && !role.isBlank()) {
                out.write(": " + singleLine(role));
            }
            out.write('\n');
        }
    }

    private static String singleLine(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
