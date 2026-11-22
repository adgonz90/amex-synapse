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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.QueryOperation;

/** Reads a book title by id and normalises it with {@link TitleRule}. */
@Component
class GetBookOperation implements QueryOperation<String, String> {

    private final BookRepositoryPort repository;

    private final TitleRule titleRule;

    GetBookOperation(BookRepositoryPort repository, TitleRule titleRule) {
        this.repository = repository;
        this.titleRule = titleRule;
    }

    @Override
    public String execute(String id) {
        String title = repository.findTitle(id).orElseThrow(() -> new SynapseException(SynapseErrors.NOT_FOUND));
        return titleRule.execute(title);
    }
}
