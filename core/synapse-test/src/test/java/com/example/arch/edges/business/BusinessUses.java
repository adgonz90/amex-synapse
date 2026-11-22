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
package com.example.arch.edges.business;

import com.example.arch.edges.client.ClientUses;
import com.example.arch.edges.contract.ContractUses;
import com.example.arch.edges.data.DataUses;
import com.example.arch.edges.model.event.EdgeEvent;
import com.example.arch.edges.publisher.PublisherUses;

/**
 * Uses other business code, the client, data, publisher and contract layers, and the service's events.
 */
public class BusinessUses {

    public void use(BusinessHelper p0, ClientUses p1, DataUses p2, PublisherUses p3, ContractUses p4, EdgeEvent p5) {
    }
}
