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
package io.americanexpress.synapse.test.grpc.slice;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;

/** Reads an item. The slice leaves this class out: the tests mock it. */
@Component
class GetItemOperation implements QueryOperation<ItemKey, Optional<ItemView>> {

    @Override
    public Optional<ItemView> execute(ItemKey input) {
        return Optional.of(new ItemView(input.id(), "the real operation ran", 0));
    }
}
