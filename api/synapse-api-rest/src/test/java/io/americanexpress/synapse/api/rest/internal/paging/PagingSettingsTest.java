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

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class PagingSettingsTest {

    @Test
    void defaultsAreTheStandardNamesAndCaps() {
        PagingSettings defaults = PagingSettings.defaults();

        assertThat(defaults).isEqualTo(new PagingSettings("cursor", "offset", "size", "sort", 1000, 20, 100));
        assertThat(defaults.names()).containsExactly("cursor", "offset", "size", "sort");
    }

    @Test
    void ofTakesTheRestNamesAndTheCoreOffsetCapAndSizes() {
        SynapseRestProperties.Paging paging = new SynapseRestProperties.Paging(
                new SynapseRestProperties.Paging.Parameters("after", "skip", "page_size", "order"));

        PagingSettings settings = PagingSettings
                .of(paging, new SynapsePagingProperties(10, 50, 500,
                        new SynapsePagingProperties.Cursor(null, List.of(), null)));

        assertThat(settings).isEqualTo(new PagingSettings("after", "skip", "page_size", "order", 500, 10, 50));
        assertThat(settings.names()).containsExactly("after", "skip", "page_size", "order");
    }
}
