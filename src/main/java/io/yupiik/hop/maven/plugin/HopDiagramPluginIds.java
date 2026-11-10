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
package io.yupiik.hop.maven.plugin;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.hop.core.exception.HopXmlException;
import org.apache.hop.core.xml.XmlHandler;
import org.w3c.dom.Document;

/**
 * Extracts the Hop plugin ids declared by pipeline ({@code .hpl}) and workflow ({@code .hwf})
 * files. A pipeline stores each transform under a {@code <transform>} element with a {@code <type>}
 * child; a workflow stores each action under an {@code <action>} element with a {@code <type>}
 * child. The extracted ids feed the {@link HopPluginArtifactMapper} when {@code autoResolve} is
 * enabled.
 * <p>
 * The parsing delegates to the Hop {@link XmlHandler} facade (the same hardened parser the Hop
 * metadata uses), so the behaviour matches what Hop itself would load and no extra XML dependency
 * is needed.
 */
public class HopDiagramPluginIds {

    private static final String TRANSFORM_TAG = "transform";
    private static final String ACTION_TAG = "action";
    private static final String TYPE_TAG = "type";

    /**
     * @param file a {@code .hpl} (pipeline) file.
     * @return the ordered set of transform plugin ids declared by the file.
     */
    public Set<String> extractTransforms(final Path file) {
        return extract(file, TRANSFORM_TAG);
    }

    /**
     * @param file a {@code .hwf} (workflow) file.
     * @return the ordered set of action plugin ids declared by the file.
     */
    public Set<String> extractActions(final Path file) {
        return extract(file, ACTION_TAG);
    }

    private Set<String> extract(final Path file, final String tag) {
        final var ids = new LinkedHashSet<String>();
        final Document document;
        try {
            document = XmlHandler.loadXmlFile(file.toFile());
        } catch (final HopXmlException e) {
            throw new IllegalArgumentException("Unable to parse HOP file " + file, e);
        }
        // getElementsByTagName walks the whole tree: transforms are direct children of the
        // <pipeline> root while actions are nested under <actions>, this covers both layouts.
        final var nodes = document.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            final var node = nodes.item(i);
            final var type = XmlHandler.getTagValue(node, TYPE_TAG);
            if (type != null && !type.isBlank()) {
                ids.add(type.trim());
            }
        }
        return ids;
    }
}
