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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import java.util.List;

/**
 * A record and a message that can't be mapped to each other. The binding that asked for the mapping wraps the
 * problem in its startup failure, which names the method and the operation.
 */
public final class MappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What to do about required scalars bound to fields without presence. */
    static final String PRESENCE_ACTION = "Declare these fields optional (proto3 optional), use a wrapper type, or "
            + "make the components @Nullable.";

    private final String problem;

    private final String action;

    /** The problem's lines, one per component, when the only problem is presence; otherwise empty. */
    private final List<String> presence;

    /**
     * Creates the exception.
     *
     * @param problem what is wrong, without the method's name
     * @param action what to do about it
     */
    public MappingException(String problem, String action) {
        this(problem, action, List.of());
    }

    private MappingException(String problem, String action, List<String> presence) {
        super(problem);
        this.problem = problem;
        this.action = action;
        this.presence = presence;
    }

    /**
     * Creates the exception of a request whose only problem is required scalars bound to fields without presence: its
     * problem has one line per component.
     *
     * @param components one line per component, in mapping order; not empty
     * @return the exception
     */
    static MappingException presence(List<String> components) {
        return new MappingException(String.join("\n", components), PRESENCE_ACTION, List.copyOf(components));
    }

    /**
     * Returns what is wrong.
     *
     * @return the problem
     */
    public String problem() {
        return problem;
    }

    /**
     * Returns what to do about it.
     *
     * @return the action
     */
    public String action() {
        return action;
    }

    /**
     * Tells whether the only problem is required scalars bound to fields without presence. The binding then keeps
     * checking the service's other methods, so that one startup failure lists every such component.
     *
     * @return whether the problem is about presence only
     */
    public boolean presenceOnly() {
        return !presence.isEmpty();
    }

    /**
     * Returns the problem's lines when it is about presence only, one per component; otherwise the problem alone.
     *
     * @return the lines, immutable
     */
    public List<String> lines() {
        return presence.isEmpty() ? List.of(problem) : presence;
    }
}
