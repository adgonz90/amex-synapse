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
package io.americanexpress.synapse.test.architecture;

import java.util.List;
import java.util.function.Function;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.test.architecture.SynapseArchitecture.Roots;

/**
 * One of the Synapse architecture rules, as a {@link SynapseArchitecture} constant. A rule names what is checked; the
 * check itself is built for the application's root packages by {@link SynapseArchitecture#forRoots} or
 * {@link SynapseArchitecture#forApplication}. Pass the constant to {@link SynapseRules#except} to leave the rule out.
 * Some rules, such as {@link SynapseArchitecture#CALL_DIRECTIONS}, are made of other rules: leaving one out leaves
 * out all of its parts.
 */
public final class SynapseRule {

    private final String name;

    private final @Nullable Function<Roots, ArchRule> factory;

    private final List<SynapseRule> parts;

    private SynapseRule(String name, @Nullable Function<Roots, ArchRule> factory, List<SynapseRule> parts) {
        this.name = name;
        this.factory = factory;
        this.parts = List.copyOf(parts);
    }

    /**
     * Returns a rule built by {@code factory} for the roots it is checked against.
     */
    static SynapseRule of(String name, Function<Roots, ArchRule> factory) {
        return new SynapseRule(name, factory, List.of());
    }

    /**
     * Returns a rule made of {@code parts}.
     */
    static SynapseRule composite(String name, SynapseRule... parts) {
        return new SynapseRule(name, null, List.of(parts));
    }

    /**
     * Returns the name of the {@link SynapseArchitecture} constant for this rule.
     *
     * @return the name, for example {@code PROTOCOL_FREE}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the rules this rule is made of: the rule itself, or every part of a composite rule.
     */
    List<SynapseRule> leaves() {
        return parts.isEmpty() ? List.of(this) : parts.stream().flatMap(part -> part.leaves().stream()).toList();
    }

    /**
     * Returns the ArchUnit rule that checks this rule for classes under {@code roots}.
     */
    ArchRule toArchRule(Roots roots) {
        if (factory != null) {
            return factory.apply(roots);
        }
        return CompositeArchRule.of(leaves().stream().map(leaf -> leaf.toArchRule(roots)).toList());
    }

    @Override
    public String toString() {
        return name;
    }
}
