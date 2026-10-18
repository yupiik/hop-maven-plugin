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
package io.yupiik.hop.maven.documentation;

import io.yupiik.asciidoc.model.Body;
import io.yupiik.asciidoc.model.Code;
import io.yupiik.asciidoc.model.Element;
import io.yupiik.asciidoc.model.Element.ElementType;
import io.yupiik.asciidoc.model.Link;
import io.yupiik.asciidoc.model.OrderedList;
import io.yupiik.asciidoc.model.Paragraph;
import io.yupiik.asciidoc.model.Section;
import io.yupiik.asciidoc.model.Text;
import io.yupiik.asciidoc.model.UnOrderedList;
import io.yupiik.asciidoc.parser.Parser;
import io.yupiik.asciidoc.parser.resolver.ContentResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a per-artefact documentation file ({@code <name>.adoc} by convention in the
 * {@code enrichmentLocation} folder) and turns it into an {@link Enrichment}. The file uses AsciiDoc
 * document attributes for the index/optional metadata and its body for the human prose:
 *
 * <pre>
 * = T_SYNC_LOAD
 * :hop-index-role: ...
 * :hop-index-order: 7
 *
 * The overview prose (paragraphs, bullets, ...).
 *
 * == Script de lancement
 *
 * [source,bash]
 * ----
 * hop-run.sh -f ...
 * ----
 * </pre>
 *
 * <ul>
 *   <li>{@code :hop-index-role:} -&gt; {@link Enrichment#setIndexRole(String)}</li>
 *   <li>{@code :hop-index-order:} -&gt; {@link Enrichment#setIndexOrder(Integer)}</li>
 *   <li>{@code :hop-diagram-reference:} -&gt; {@link Enrichment#setDiagramReference(String)} (optional)</li>
 *   <li>the body before the first {@code ==} section -&gt; {@link Enrichment#setOverview(String)}</li>
 *   <li>the {@code [source,...]} (non-inline {@link Code}) block -&gt; {@link Enrichment#setScript(String)}</li>
 *   <li>the other {@code ==} sections -&gt; {@link Enrichment#setExtraSections(List)}</li>
 * </ul>
 *
 * The class is Maven-free (only asciidoc-java + JDK).
 */
public class DocFileLoader {

    private static final String ROLE_ATTR = "hop-index-role";
    private static final String ORDER_ATTR = "hop-index-order";
    private static final String DIAGRAM_ATTR = "hop-diagram-reference";

    private final Path folder;

    public DocFileLoader(final Path folder) {
        this.folder = folder == null ? null : folder.toAbsolutePath().normalize();
    }

    /**
     * @return the enrichment for the given subject, or {@code null} when no {@code &lt;name&gt;.adoc}
     * file exists in the folder.
     */
    public Enrichment load(final DocSubject subject) {
        final var file = resolve(subject.name());
        if (file == null) {
            return null;
        }
        final String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("Can't read documentation file " + file, e);
        }
        final var document = new Parser().parse(content, new Parser.ParserContext(ContentResolver.of(folder)));
        final Map<String, String> attrs = document.header() == null || document.header().attributes() == null
                ? Map.of()
                : document.header().attributes();

        final var enrichment = new Enrichment();
        enrichment.setName(subject.name());
        enrichment.setKind(subject.kind() == null ? null : subject.kind().name());
        enrichment.setIndexRole(attrs.get(ROLE_ATTR));
        final var order = attrs.get(ORDER_ATTR);
        if (order != null && !order.isBlank()) {
            enrichment.setIndexOrder(Integer.valueOf(order.trim()));
        }
        enrichment.setDiagramReference(attrs.get(DIAGRAM_ATTR));
        fillFromBody(document.body(), enrichment);
        return enrichment;
    }

    /**
     * @return the resolved {@code <baseName>.adoc} path in the folder or {@code null} if absent.
     */
    public Path resolve(final String baseName) {
        if (folder == null) {
            return null;
        }
        final var file = folder.resolve(baseName + ".adoc");
        return Files.isRegularFile(file) ? file : null;
    }

    private void fillFromBody(final Body body, final Enrichment enrichment) {
        if (body == null) {
            return;
        }
        final List<Element> children = body.children() == null ? List.of() : body.children();

        // overview: top-level elements until the first section; script/extra sections after.
        final var overview = new StringBuilder();
        final var extraSections = new ArrayList<Enrichment.ExtraSection>();
        var seenSection = false;
        for (final var element : children) {
            if (element.type() != ElementType.SECTION) {
                if (!seenSection) {
                    overview.append(toAsciiDoc(element));
                    overview.append("\n\n");
                } else {
                    // content after a section should belong to the previous section; for our
                    // convention the overview is before the script, so append trailing prose to overview.
                    overview.append(toAsciiDoc(element));
                    overview.append("\n\n");
                }
                continue;
            }
            seenSection = true;
            final var section = (Section) element;
            final var title = toAsciiDoc(section.title()).trim();
            if (isScriptTitle(title) || containsScriptBlock(section)) {
                final var script = findScript(section);
                if (script != null) {
                    enrichment.setScript(script);
                }
            } else {
                final var extra = new Enrichment.ExtraSection();
                extra.setTitle(title);
                final var content = new StringBuilder();
                for (final var child : section.children()) {
                    content.append(toAsciiDoc(child));
                    content.append("\n");
                }
                extra.setContent(content.toString());
                extraSections.add(extra);
            }
        }
        final var overviewText = overview.toString().trim();
        if (!overviewText.isEmpty()) {
            enrichment.setOverview(overviewText);
        }
        enrichment.setExtraSections(extraSections);
    }

    private boolean containsScriptBlock(final Section section) {
        return findScript(section) != null;
    }

    private String findScript(final Section section) {
        for (final var child : section.children()) {
            if (child instanceof Code code && !code.inline()) {
                return code.value();
            }
        }
        return null;
    }

    private boolean isScriptTitle(final String title) {
        final var lower = title.toLowerCase(Locale.ROOT);
        return lower.equals("script de lancement") || lower.equals("script") || lower.equals("launch");
    }

    private String toAsciiDoc(final Element element) {
        if (element == null) {
            return "";
        }
        return switch (element.type()) {
            case TEXT -> inlineText((Text) element);
            case CODE -> ((Code) element).inline() ? "`" + ((Code) element).value() + "`" : ((Code) element).value();
            case LINK -> {
                final var link = (Link) element;
                yield "link:" + link.url() + "[" + toAsciiDoc(link.label()) + "]";
            }
            case PARAGRAPH -> serializeChildren(((Paragraph) element).children());
            case UNORDERED_LIST -> serializeList("* ", ((UnOrderedList) element).children());
            case ORDERED_LIST -> serializeList(". ", ((OrderedList) element).children());
            case SECTION -> {
                final var section = (Section) element;
                final var sb = new StringBuilder();
                for (final var child : section.children()) {
                    sb.append(toAsciiDoc(child));
                }
                yield sb.toString();
            }
            default -> "";
        };
    }

    private String inlineText(final Text text) {
        final var value = text.value() == null ? "" : text.value();
        if (text.style() == null || text.style().isEmpty()) {
            return value;
        }
        var out = value;
        for (final var style : text.style()) {
            out = switch (style) {
                case BOLD -> "**" + out + "**";
                case ITALIC, EMPHASIS -> "_" + out + "_";
                case MARK -> "#" + out + "#";
                case STRIKETHROUGH -> "[line-through]#" + out + "#";
                case SUB -> "~" + out + "~";
                case SUP -> "^" + out + "^";
            };
        }
        return out;
    }

    private String serializeChildren(final List<Element> children) {
        final var sb = new StringBuilder();
        for (final var child : children) {
            sb.append(toAsciiDoc(child));
        }
        return sb.toString();
    }

    private String serializeList(final String bullet, final List<Element> items) {
        final var sb = new StringBuilder();
        for (final var item : items) {
            sb.append(bullet).append(toAsciiDoc(item)).append('\n');
        }
        return sb.toString();
    }
}