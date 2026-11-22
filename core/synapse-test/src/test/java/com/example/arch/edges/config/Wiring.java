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
package com.example.arch.edges.config;

import com.example.arch.edges.business.BusinessUses;
import com.example.arch.edges.client.ClientUses;
import com.example.arch.edges.contract.ContractUses;
import com.example.arch.edges.data.DataUses;
import com.example.arch.edges.model.ModelUses;
import com.example.arch.edges.operation.OperationUses;
import com.example.arch.edges.publisher.PublisherUses;

/**
 * Outside every layer: uses every layer but api.
 */
public class Wiring {

    public void use(OperationUses p0, BusinessUses p1, ClientUses p2, DataUses p3, PublisherUses p4, ModelUses p5,
            ContractUses p6) {
    }
}
