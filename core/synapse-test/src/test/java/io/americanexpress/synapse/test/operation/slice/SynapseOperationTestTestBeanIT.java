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
package io.americanexpress.synapse.test.operation.slice;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** A business bean replaced with {@code @TestBean} is a test double: the slice starts and the operation uses it. */
@SynapseOperationTest
class SynapseOperationTestTestBeanIT {

    @Autowired
    GetBookOperation getBook;

    @TestBean
    @SuppressWarnings("NullAway.Init") // set by Spring's bean override support
    TitleRule titleRule;

    @MockitoBean
    BookRepositoryPort repository;

    static TitleRule titleRule() {
        return new QuotingTitleRule();
    }

    @Test
    void runsTheOperationWithTheTestBean() {
        given(repository.findTitle("1")).willReturn(Optional.of("dune"));

        assertThat(titleRule).isInstanceOf(QuotingTitleRule.class);
        assertThat(getBook.execute("1")).isEqualTo("'dune'");
    }

    static class QuotingTitleRule extends TitleRule {

        @Override
        public String execute(String title) {
            return "'" + title + "'";
        }
    }
}
