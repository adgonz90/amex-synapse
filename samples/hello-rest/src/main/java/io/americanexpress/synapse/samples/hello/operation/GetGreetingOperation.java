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
package io.americanexpress.synapse.samples.hello.operation;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.hello.model.GetGreetingRequest;
import io.americanexpress.synapse.samples.hello.model.Greeting;

/**
 * Greets someone by name. Synapse validates the request ({@code @NotBlank @Size(max = 40)} on the name) before this
 * method runs, so it only has to build the greeting.
 */
@Component
public class GetGreetingOperation implements QueryOperation<GetGreetingRequest, Greeting> {

    @Override
    public Greeting execute(GetGreetingRequest request) {
        return new Greeting("Hello, " + request.name() + "!");
    }
}
