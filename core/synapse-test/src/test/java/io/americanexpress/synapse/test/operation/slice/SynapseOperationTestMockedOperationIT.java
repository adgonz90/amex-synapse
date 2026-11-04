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

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** An operation replaced with {@code @MockitoBean} is a test double: the slice starts and the mock answers. */
@SynapseOperationTest
class SynapseOperationTestMockedOperationIT {

    @MockitoBean
    GetBookOperation getBook;

    @Autowired
    TitleRule titleRule;

    @Test
    void answersWithTheMockedOperation() {
        given(getBook.execute("1")).willReturn("mocked");

        assertThat(getBook.execute("1")).isEqualTo("mocked");
        assertThat(AopUtils.isCglibProxy(titleRule)).isTrue();
    }
}
