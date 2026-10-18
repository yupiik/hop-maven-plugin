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
package io.yupiik.hop.maven.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the connection details of a running Hop server from its {@code hop-server.xml} config file,
 * so the {@code server-stop} goal targets the actual listening endpoint instead of a hardcoded port.
 */
public record HopServerConfig(String hostname, int port, boolean enabledAuth) {

    private static final String DEFAULT_HOST = "localhost";

    /**
     * Parse a {@code hop-server.xml}; returns {@code null} when the file is missing.
     *
     * @throws IllegalArgumentException when the file exists but cannot be parsed to a usable config.
     */
    public static HopServerConfig read(final Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (final InputStream in = Files.newInputStream(file)) {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (final Exception ignored) {
                // not all JDKs expose it; keep external entities off as a best effort
            }
            final var document = factory.newDocumentBuilder().parse(in);
            final var root = document.getDocumentElement();
            final var servers = root.getElementsByTagName("hop-server");
            if (servers.getLength() == 0) {
                throw new IllegalArgumentException("No <hop-server> element in " + file);
            }
            final var server = (Element) servers.item(0);
            final var host = childText(server, "hostname");
            final var port = childText(server, "port");
            final var auth = childText(server, "enable_auth");
            return new HopServerConfig(
                    host == null || host.isBlank() ? DEFAULT_HOST : host,
                    port == null || port.isBlank() ? -1 : Integer.parseInt(port.trim()),
                    Boolean.parseBoolean(auth == null ? "false" : auth.trim()));
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalArgumentException("Unable to parse " + file + ": " + e.getMessage(), e);
        }
    }

    private static String childText(final Element parent, final String tag) {
        final var list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        final var node = list.item(0);
        return node.getTextContent() == null ? null : node.getTextContent().trim();
    }
}