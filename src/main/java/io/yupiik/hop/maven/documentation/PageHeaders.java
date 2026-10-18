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

/**
 * The page-level (yupiik minisite) header attributes emitted at the top of a generated page. These
 * come from the index ordering (prev/next), the index title (breadcrumb) and the page itself, so a
 * generated documentation folder behaves like a hand-written one for minisite navigation.
 */
public record PageHeaders(
        String indexTitle,
        String indexIcon,
        String breadcrumb,
        String prevLabel,
        String prevLink,
        String nextLabel,
        String nextLink) {

    public static PageHeaders empty() {
        return new PageHeaders(null, null, null, null, null, null, null);
    }
}