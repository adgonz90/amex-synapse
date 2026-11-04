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
package com.example.arch.bad.edges.model;

import com.example.arch.bad.edges.business.EdgeBusiness;
import com.example.arch.bad.edges.client.EdgeClient;
import com.example.arch.bad.edges.data.EdgeData;
import com.example.arch.bad.edges.operation.EdgeOperation;
import com.example.arch.bad.edges.publisher.EdgePublisher;

/**
 * A model that depends on code: models are leaves.
 */
public class ModelEdges {

    public void use(EdgeOperation p0, EdgeBusiness p1, EdgeClient p2, EdgeData p3, EdgePublisher p4) {
    }
}
