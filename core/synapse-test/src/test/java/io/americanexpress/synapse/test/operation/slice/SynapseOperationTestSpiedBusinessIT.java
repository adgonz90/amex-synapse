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
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/** A business bean wrapped with {@code @MockitoSpyBean} keeps its real behavior and the pipeline. */
@SynapseOperationTest
class SynapseOperationTestSpiedBusinessIT {

    @Autowired
    GetBookOperation getBook;

    @MockitoSpyBean
    TitleRule titleRule;

    @MockitoBean
    BookRepositoryPort repository;

    @Test
    void runsTheRealBusinessBeanThroughTheSpy() {
        given(repository.findTitle("1")).willReturn(Optional.of(" dune "));

        assertThat(AopUtils.isCglibProxy(titleRule)).isTrue();
        assertThat(getBook.execute("1")).isEqualTo("DUNE");
        then(titleRule).should().execute(" dune ");
    }
}
