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
 * <p>In addition the whole body is preserved verbatim, in source order, in
 * {@link Enrichment#setBlocks(List)} so the generated page keeps the narrative thread of the
 * documentation file:</p>
 *
 * <ul>
 *   <li>sections and their prose are kept in the order they were written (prose that follows a
 *       heading stays under it, nested {@code ===} headings are kept),</li>
 *   <li>the built-in generated sections can be placed exactly where needed by writing a reserved
 *       heading {@code == :configuration}, {@code == :steps}, {@code == :flow} or
 *       {@code == :diagram} at that spot,</li>
 *   <li>the renderer ({@code DocVisitor}) follows {@link Enrichment#getBlocks()} when present
 *       instead of the fixed Overview/Configuration/Steps/Flow/Diagram/Script/Extras order.</li>
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
        final Map<String, String> attrs =
                document.header() == null || document.header().attributes() == null
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

        // Walk the body top to bottom and rebuild the narrative order into an ordered block list.
        // The parser absorbs trailing prose into the preceding element, so each section keeps its
        // own children (incl. nested === headings, code blocks and trailing paragraphs).
        final var blocks = new ArrayList<Enrichment.Block>();
        final StringBuilder overview = new StringBuilder();
        var seenSection = false;
        for (final var element : children) {
            if (element.type() != ElementType.SECTION) {
                // top-level prose: only meaningful before the first section (the overview); any
                // trailing top-level block is kept right before the sections to not lose content.
                final var text = toAsciiDoc(element).trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (!seenSection) {
                    overview.append(text).append("\n\n");
                } else {
                    blocks.add(new Enrichment.Block.Overview(text));
                }
                continue;
            }
            seenSection = true;
            final var section = (Section) element;
            final var title = toAsciiDoc(section.title()).trim();
            final var anchor = anchorOf(title);
            if (anchor != null) {
                blocks.add(new Enrichment.Block.Generated(anchor.name().toLowerCase(Locale.ROOT)));
                continue;
            }
            if (isScriptTitle(title) || isScriptSection(section)) {
                final var script = findScript(section);
                final var trailing = trailingProse(section);
                if (script != null) {
                    blocks.add(new Enrichment.Block.Script(script, trailing));
                } else if (!trailing.isEmpty()) {
                    blocks.add(new Enrichment.Block.Overview(trailing));
                }
            } else {
                final var content = new StringBuilder();
                for (final var child : section.children()) {
                    content.append(toAsciiDoc(child));
                    content.append('\n');
                }
                blocks.add(
                        new Enrichment.Block.Section(title, content.toString().trim()));
            }
        }
        final var overviewText = overview.toString().trim();
        enrichment.setBlocks(blocks);
        enrichment.setOverview(overviewText.isEmpty() ? null : overviewText);
        // backward-compatible derived views (used when renderer ignores blocks or for index checks)
        enrichment.setScript(blocks.stream()
                .filter(Enrichment.Block.Script.class::isInstance)
                .map(Enrichment.Block.Script.class::cast)
                .map(Enrichment.Block.Script::script)
                .findFirst()
                .orElse(null));
        final var extras = new ArrayList<Enrichment.ExtraSection>();
        for (final var block : blocks) {
            if (block instanceof Enrichment.Block.Section extra) {
                final var item = new Enrichment.ExtraSection();
                item.setTitle(extra.title());
                item.setContent(extra.content());
                extras.add(item);
            }
        }
        enrichment.setExtraSections(extras);
    }

    /**
     * Maps a section title to the built-in generated section it anchors, or {@code null}. The reserved
     * titles ({@code :configuration}, {@code :steps}, {@code :flow}, {@code :diagram}) let a doc file
     * interleave the generated sections exactly where the narrative needs them.
     */
    private static GeneratedAnchor anchorOf(final String title) {
        final var lower = title.toLowerCase(Locale.ROOT);
        for (final var anchor : GeneratedAnchor.values()) {
            if (lower.equals(":" + anchor.name().toLowerCase(Locale.ROOT))) {
                return anchor;
            }
        }
        return null;
    }

    /** The generated sections that can be anchored with a {@code :name} section title. */
    private enum GeneratedAnchor {
        CONFIGURATION,
        STEPS,
        FLOW,
        DIAGRAM
    }

    private boolean containsScriptBlock(final Section section) {
        return findScript(section) != null;
    }

    /**
     * A section is a launch-script section when it carries a non-inline source block: a section whose
     * children (e.g. a paragraph introducing the snippet or a trailing note) include a
     * {@code [source,...]} block. Matched by {@code findScript}.
     */
    private boolean isScriptSection(final Section section) {
        return containsScriptBlock(section);
    }

    private String trailingProse(final Section section) {
        if (section.children() == null || section.children().isEmpty()) {
            return "";
        }
        // trailing non-code children of a script section (paragraphs after the source block)
        final var sb = new StringBuilder();
        var afterCode = false;
        for (final var child : section.children()) {
            if (child instanceof Code code && !code.inline()) {
                afterCode = true;
                continue;
            }
            if (afterCode) {
                final var text = toAsciiDoc(child).trim();
                if (!text.isEmpty()) {
                    sb.append(text).append("\n\n");
                }
            }
        }
        return sb.toString().trim();
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
                serializedSection(section, sb);
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

    /**
     * Serializes a section into its heading (with the right {@code =} depth, from the parser level)
     * followed by its children, so nested {@code ===} subsections keep their title when the section is
     * carried as a free-form {@code ==} block.
     */
    private void serializedSection(final Section section, final StringBuilder sb) {
        final var title = toAsciiDoc(section.title()).trim();
        if (!title.isEmpty()) {
            sb.append("=".repeat(Math.max(2, section.level())))
                    .append(' ')
                    .append(title)
                    .append('\n');
        }
        if (section.children() != null) {
            for (final var child : section.children()) {
                sb.append(toAsciiDoc(child));
                sb.append('\n');
            }
        }
    }

    private String serializeList(final String bullet, final List<Element> items) {
        final var sb = new StringBuilder();
        for (final var item : items) {
            sb.append(bullet).append(toAsciiDoc(item)).append('\n');
        }
        return sb.toString();
    }
}
