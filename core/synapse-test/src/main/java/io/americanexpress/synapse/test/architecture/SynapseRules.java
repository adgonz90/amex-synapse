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

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.Priority;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import io.americanexpress.synapse.test.architecture.SynapseArchitecture.Roots;

/**
 * An immutable set of Synapse architecture rules for an application's root packages, from
 * {@link SynapseArchitecture#forApplication} or {@link SynapseArchitecture#forRoots}.
 */
public final class SynapseRules {

    private final Roots roots;

    private final List<SynapseRule> rules;

    SynapseRules(Roots roots, List<SynapseRule> rules) {
        this.roots = roots;
        this.rules = List.copyOf(rules.stream().flatMap(rule -> rule.leaves().stream()).toList());
    }

    /**
     * Returns this set without {@code rule}. A rule can be left out only with a written reason, which documents the
     * exception where it is made. Leaving out a rule made of other rules, such as
     * {@link SynapseArchitecture#CALL_DIRECTIONS}, leaves out all of its parts.
     *
     * @param rule one of the {@link SynapseArchitecture} constants
     * @param reason why the application doesn't follow the rule
     * @return a new set without the rule
     * @throws IllegalArgumentException if the reason is blank, or the rule (or one of its parts) was already left out
     */
    public SynapseRules except(SynapseRule rule, String reason) {
        if (reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to leave out the architecture rule " + rule);
        }
        List<SynapseRule> excepted = rule.leaves();
        if (!rules.containsAll(excepted)) {
            throw new IllegalArgumentException("The rule " + rule + " is not in this set: each rule can be left out "
                    + "only once, and a rule made of other rules only while all of its parts are in the set");
        }
        return new SynapseRules(roots, rules.stream().filter(candidate -> !excepted.contains(candidate)).toList());
    }

    /**
     * Imports the classes under the root packages from the class path, without test classes, and checks them against
     * every rule in this set. Fails also when no class under one of the roots was imported, which usually means a root
     * is mistyped.
     *
     * @throws AssertionError listing every violation, if any rule is violated or no class under one of the roots was
     *     imported
     */
    public void check() {
        check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(roots.packages()));
    }

    /**
     * Checks classes imported by the caller against every rule in this set. Only the classes under the root packages
     * are checked. Fails also when no class under one of the roots was imported, which usually means the importer was
     * given the wrong package.
     *
     * @param classes the application's classes
     * @throws AssertionError listing every violation, if any rule is violated or no class under one of the roots was
     *     imported
     */
    public void check(JavaClasses classes) {
        asArchRule().check(classes);
    }

    /**
     * Returns the rules in this set as one rule, for example for an {@code @ArchTest} field. Like {@link #check}, it
     * also fails when no class under one of the roots was imported.
     *
     * @return a composite of the rules in this set
     */
    public ArchRule asArchRule() {
        List<ArchRule> all = new ArrayList<>();
        all.add(applicationClassesImported(roots));
        rules.forEach(rule -> all.add(rule.toArchRule(roots)));
        return CompositeArchRule.of(all);
    }

    /**
     * Returns the rules in this set. A rule made of other rules, such as {@link SynapseArchitecture#CALL_DIRECTIONS},
     * is listed as its parts.
     *
     * @return an unmodifiable list of the rules
     */
    public List<SynapseRule> rules() {
        return rules;
    }

    /**
     * Returns the root packages the rules check.
     */
    List<String> roots() {
        return roots.packages();
    }

    /**
     * Returns a rule that fails when the classes contain no class under one of {@code roots}.
     */
    static ArchRule applicationClassesImported(Roots roots) {
        return new ApplicationClassesImported(roots,
                "application classes (under " + String.join(", ", roots.packages()) + ") should have been imported");
    }

    /**
     * Fails when, for any root, the checked classes contain no class under it, so that a mistyped root (or an import of
     * other packages) can't make the rules pass without checking anything. Stateless.
     */
    private record ApplicationClassesImported(Roots roots, String description) implements ArchRule {

        @Override
        public void check(JavaClasses classes) {
            Assertions.assertNoViolation(evaluate(classes));
        }

        @Override
        public ArchRule because(String reason) {
            return new ApplicationClassesImported(roots, description + ", because " + reason);
        }

        @Override
        public ArchRule allowEmptyShould(boolean allowEmptyShould) {
            return this;
        }

        @Override
        public EvaluationResult evaluate(JavaClasses classes) {
            ConditionEvents events = ConditionEvents.Factory.create();
            List<String> missing = roots
                    .packages()
                    .stream()
                    .filter(root -> classes
                            .stream()
                            .noneMatch(javaClass -> roots.isApplicationClass(javaClass)
                                    && SynapseArchitecture.isInPackage(javaClass.getPackageName(), root)))
                    .toList();
            if (!missing.isEmpty()) {
                events
                        .add(SimpleConditionEvent
                                .violated(classes, "No application classes were imported: no imported class is under "
                                        + String.join(", ", missing)
                                        + ". Check the root packages, and the packages passed to the importer"));
            }
            return new EvaluationResult(this, events, Priority.MEDIUM);
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public ArchRule as(String newDescription) {
            return new ApplicationClassesImported(roots, newDescription);
        }
    }
}
