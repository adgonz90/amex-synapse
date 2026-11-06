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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.util.List;

import org.springframework.http.ETag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.ServerRequest;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.ConditionalRules;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * The preconditions of a write (RFC 9110 §13.2): what the edge checks itself before the operation runs, and how a
 * version conflict the operation reports is answered.
 *
 * <p>
 * The version check itself is the operation's: it compares the version bound from {@code If-Match} with the one it
 * reads, and writes with a compare-and-set, throwing {@link SynapseErrors#STALE_VERSION} when either fails. The edge
 * only refuses what it can decide without the resource:
 * <ul>
 * <li>an {@code If-Match} or {@code If-None-Match} the route doesn't bind, which it can't evaluate, so ignoring it would
 * turn a conditional write into an unconditional one: 412 {@code PRECONDITION_FAILED};</li>
 * <li>a weak tag in a bound {@code If-Match}, which never matches under strong comparison (§13.1.1), or a bound
 * {@code If-Match} that doesn't parse (binding answers that 400 first; this fails closed regardless): 412;</li>
 * <li>no {@code If-Match} on a route that declares {@code .requireIfMatch()}: 428 {@code PRECONDITION_REQUIRED}, with
 * a violation that says how to retry (RFC 6585 §3), so responses still carry it when detail is hidden.</li>
 * </ul>
 * Reads ({@code GET}, {@code HEAD}) are not checked: there {@code If-None-Match} decides a 304, and an unbound
 * {@code If-Match} is ignored. {@code If-Unmodified-Since} is ignored everywhere, as §13.1.4 allows a server that
 * doesn't expose a modification date.
 */
public final class Preconditions {

    private static final Violation IF_MATCH_REQUIRED = new Violation(HttpHeaders.IF_MATCH,
            "is required; send the ETag from a GET of this resource");

    private Preconditions() {
    }

    /**
     * Checks the request's preconditions, after binding and before the operation runs. Does nothing on reads.
     *
     * @param route the resolved route
     * @param request the request, already bound
     * @throws SynapseException {@link SynapseErrors#PRECONDITION_FAILED} for an unbound {@code If-Match} or
     *         {@code If-None-Match}, or a bound {@code If-Match} with a weak tag or that doesn't parse; then
     *         {@link RestErrors#PRECONDITION_REQUIRED} when the route requires {@code If-Match} and the request has
     *         none
     */
    public static void check(RouteDefinition route, ServerRequest request) {
        if (isRead(route.method())) {
            return;
        }
        ConditionalRules rules = route.conditional();
        List<String> ifMatch = request.headers().header(HttpHeaders.IF_MATCH);
        boolean unboundIfMatch = rules.ifMatch() == null && !ifMatch.isEmpty();
        boolean unboundIfNoneMatch = rules.ifNoneMatch() == null
                && !request.headers().header(HttpHeaders.IF_NONE_MATCH).isEmpty();
        if (unboundIfMatch || unboundIfNoneMatch || cannotMatch(ifMatch)) {
            throw new SynapseException(SynapseErrors.PRECONDITION_FAILED);
        }
        if (rules.requireIfMatch() && ifMatch.isEmpty()) {
            throw SynapseException.ofViolations(RestErrors.PRECONDITION_REQUIRED, List.of(IF_MATCH_REQUIRED));
        }
    }

    /**
     * Answers a failure of the operation. A {@link SynapseErrors#STALE_VERSION} on a request that sent a bound
     * {@code If-Match} (including {@code *}) means the precondition failed: it becomes
     * {@link SynapseErrors#PRECONDITION_FAILED}, with the failure as its cause. Without {@code If-Match} the write was
     * unconditional, so a lost race stays {@code STALE_VERSION} (409 by category). Every other failure, including other
     * conflicts, is returned as is.
     *
     * @param route the resolved route
     * @param request the request
     * @param failure what the operation threw
     * @return the exception to throw
     */
    public static SynapseException translate(RouteDefinition route, ServerRequest request, SynapseException failure) {
        if (failure.details() == SynapseErrors.STALE_VERSION && route.conditional().ifMatch() != null
                && !request.headers().header(HttpHeaders.IF_MATCH).isEmpty()) {
            return new SynapseException(SynapseErrors.PRECONDITION_FAILED, failure);
        }
        return failure;
    }

    /**
     * Whether the method is a read, the only kind not checked. Every other method is checked, so a method added later
     * (such as {@code PATCH}) is checked without a change here.
     */
    private static boolean isRead(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }

    /**
     * Whether a present {@code If-Match} can never match: it holds a weak tag, or it doesn't parse. Binding answers a
     * malformed header or a list 400 first; this check fails closed regardless, so a write never runs on a header
     * the edge couldn't read.
     */
    private static boolean cannotMatch(List<String> ifMatch) {
        if (ifMatch.isEmpty()) {
            return false;
        }
        EntityTags.Parsed parsed;
        try {
            parsed = EntityTags.parse(ifMatch);
        } catch (IllegalArgumentException e) {
            return true;
        }
        return parsed.tags().stream().anyMatch(ETag::weak);
    }
}
