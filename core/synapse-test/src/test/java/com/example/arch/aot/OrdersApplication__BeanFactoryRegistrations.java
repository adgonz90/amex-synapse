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
package com.example.arch.aot;

import com.example.arch.aot.api.rest.OrderBinding;
import org.springframework.aot.generate.Generated;

/**
 * Shaped like the class Spring AOT generates in the application's root package: it registers every bean, api beans
 * included.
 */
@Generated
public class OrdersApplication__BeanFactoryRegistrations {

    public String register() {
        return new OrderBinding().path();
    }

    /**
     * Nested in a generated class, and not annotated itself.
     */
    public static class Nested {

        public String register(OrderBinding binding) {
            return binding.path();
        }
    }
}
