/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.americanexpress.synapse.api.rest.internal.paging;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;

class PageLinksTest {

    private static final PagingSettings SETTINGS = PagingSettings.defaults();

    private static HttpServletRequest request(String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/books");
        request.setRequestURI("/api/books");
        if (!query.isEmpty()) {
            request.setQueryString(query);
        }
        return request;
    }

    private static PageResult<String> more(@Nullable String nextCursor) {
        return new PageResult<>(List.of("a"), nextCursor, true, null);
    }

    @Test
    void cursorLinkKeepsOtherParametersInOrder() {
        PageQuery query = PageQuery.ofCursor("old", 2, List.of());

        String link = PageLinks
                .next(request("author=le%20guin&size=2&cursor=old&sort=title"), SETTINGS, query, more("RAW"), "T");

        assertThat(link).isEqualTo("</api/books?author=le%20guin&size=2&sort=title&cursor=T>; rel=\"next\"");
    }

    @Test
    void offsetLinkAddsSizeToOffset() {
        PageQuery query = PageQuery.ofOffset(4, 2, List.of());

        String link = PageLinks.next(request("offset=4&size=2"), SETTINGS, query, more(null), null);

        assertThat(link).isEqualTo("</api/books?size=2&offset=6>; rel=\"next\"");
    }

    @Test
    void firstOffsetPageLinksToTheNextOffset() {
        PageQuery query = PageQuery.ofOffset(0, 20, List.of());

        String link = PageLinks.next(request(""), SETTINGS, query, more(null), null);

        assertThat(link).isEqualTo("</api/books?offset=20>; rel=\"next\"");
    }

    @Test
    void offsetLinkStopsAtMaxOffset() {
        assertThat(PageLinks
                .next(request("offset=990&size=20"), SETTINGS, PageQuery.ofOffset(990, 20, List.of()), more(null),
                        null))
                .isNull();
        assertThat(PageLinks
                .next(request("offset=980&size=20"), SETTINGS, PageQuery.ofOffset(980, 20, List.of()), more(null),
                        null))
                .isEqualTo("</api/books?size=20&offset=1000>; rel=\"next\"");
    }

    @Test
    void noLinkOnTheLastPage() {
        PageResult<String> last = new PageResult<>(List.of("a"), null, false, null);

        assertThat(PageLinks.next(request("size=2"), SETTINGS, PageQuery.ofOffset(0, 2, List.of()), last, null))
                .isNull();
    }

    @Test
    void noOffsetLinkForACursorRequest() {
        PageQuery query = PageQuery.ofCursor("old", 2, List.of());

        assertThat(PageLinks.next(request("cursor=old&size=2"), SETTINGS, query, more(null), null)).isNull();
    }

    @Test
    void usesConfiguredParameterNames() {
        PagingSettings renamed = new PagingSettings("page_token", "skip", "size", "sort", 1000, 20, 100);

        assertThat(PageLinks
                .next(request("page_token=old&cursor=keep&size=2"), renamed,
                        PageQuery.ofCursor("old", 2, List.of()), more("RAW"), "T"))
                .isEqualTo("</api/books?cursor=keep&size=2&page_token=T>; rel=\"next\"");
        assertThat(PageLinks
                .next(request("skip=4&offset=9&size=2"), renamed, PageQuery.ofOffset(4, 2, List.of()), more(null),
                        null))
                .isEqualTo("</api/books?offset=9&size=2&skip=6>; rel=\"next\"");
    }
}
