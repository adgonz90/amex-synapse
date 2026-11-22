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
package io.americanexpress.synapse.api.rest.internal.error;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.Violation;

/**
 * The body of a problem response (RFC 9457). It never has an {@code instance} member.
 *
 * @param type the problem type URI
 * @param title the short summary
 * @param status the HTTP status
 * @param detail the explanation for this occurrence, or {@code null} when it is withheld
 * @param code the Synapse error code
 * @param correlationId the correlation id of the request
 * @param violations the validation violations, empty when there are none
 */
public record ProblemBody(URI type, String title, int status, @Nullable String detail, String code,
        String correlationId, List<Violation> violations) {

    /**
     * Returns the body as an ordered map ready to serialize: {@code type, title, status, detail, code,
     * correlationId, violations}, without a null {@code detail} and without empty {@code violations}.
     *
     * @return the members of the body
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.toString());
        map.put("title", title);
        map.put("status", status);
        if (detail != null) {
            map.put("detail", detail);
        }
        map.put("code", code);
        map.put("correlationId", correlationId);
        if (!violations.isEmpty()) {
            List<Map<String, String>> items = new ArrayList<>(violations.size());
            for (Violation violation : violations) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("field", violation.field());
                item.put("message", violation.message());
                items.add(item);
            }
            map.put("violations", items);
        }
        return map;
    }
}
