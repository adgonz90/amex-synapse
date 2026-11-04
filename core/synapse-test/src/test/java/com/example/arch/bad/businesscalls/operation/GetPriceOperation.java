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
package com.example.arch.bad.businesscalls.operation;

import com.example.arch.bad.businesscalls.business.PricingPolicy;
import com.example.arch.bad.businesscalls.business.PricingService;

/**
 * Uses a business bean and an abstract business class that aren't BusinessOperations.
 */
public class GetPriceOperation {

    private final PricingService pricing;

    private final PricingPolicy policy;

    public GetPriceOperation(PricingService pricing, PricingPolicy policy) {
        this.pricing = pricing;
        this.policy = policy;
    }

    public long price(String isbn) {
        return policy.cap(pricing.price(isbn));
    }
}
