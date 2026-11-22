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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.acme.data.orders.business.OrderPricing;
import com.acme.data.orders.data.OrderStore;
import com.acme.data.orders.operation.ProbeOrderOperation;
import com.example.arch.bad.boundarymodel.api.rest.model.BookResponse;
import com.example.arch.bad.boundarymodel.client.model.AuthorResponse;
import com.example.arch.bad.calls.business.BookBusiness;
import com.example.arch.bad.calls.config.BookConfig;
import com.example.arch.bad.calls.data.BookStore;
import com.example.arch.bad.entity.operation.ListBooksOperation;
import com.example.arch.bad.entity.operation.SaveBooksOperation;
import com.example.arch.bad.internal.config.ContextPeek;
import com.example.arch.bad.protocol.contract.HandWrittenMapper;
import com.example.arch.bad.protocol.operation.ProbeOperation;
import com.example.arch.businesscalls.business.BookMapper;
import com.example.arch.businesscalls.business.BookMapperImpl;
import com.example.arch.businesscalls.business.BookSoldListener;
import com.example.arch.businesscalls.business.CatalogService;
import com.example.arch.businesscalls.business.DiscountStrategy;
import com.example.arch.businesscalls.business.IsbnRules;
import com.example.arch.businesscalls.business.PriceBook;
import com.example.arch.businesscalls.business.PricingConfiguration;
import com.example.arch.businesscalls.business.PricingErrors;
import com.example.arch.businesscalls.business.model.Price;
import com.example.arch.businesscalls.business.model.PriceInput;
import com.example.arch.businesscalls.config.AppConfig;
import com.example.arch.businesscalls.operation.GetPriceOperation;
import com.example.arch.good.BooksApplication;
import com.example.arch.good.business.BookLookup;
import com.example.arch.good.business.model.BookDetails;
import com.example.arch.good.config.TracingConfig;
import com.example.arch.good.contract.proto.BookCatalogProto;
import com.example.arch.good.contract.proto.BookProto;
import com.example.arch.good.contract.proto.BookServiceGrpc;
import com.example.arch.good.contract.proto.BookStatus;
import com.example.arch.good.contract.proto.Catalog;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.x.api.gateway.business.RouteAudit;
import com.x.api.gateway.operation.GetRouteOperation;
import org.junit.jupiter.api.Test;
import org.thirdparty.api.Tracing;
import org.thirdparty.services.model.StoredObject;

import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.test.architecture.SynapseArchitecture.Layer;
import io.americanexpress.synapse.test.architecture.SynapseArchitecture.Placement;
import io.americanexpress.synapse.test.architecture.SynapseArchitecture.Roots;

import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.BUSINESS_DOES_NOT_USE_SERVICE_MODEL;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.CALL_DIRECTIONS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.NOTHING_CALLS_API;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.NO_ENTITIES_IN_OPERATION_SIGNATURES;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.NO_SYNAPSE_INTERNALS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.NULL_MARKED_MODELS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.ONLY_API_AND_OPERATIONS_CALL_OPERATIONS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT;
import static io.americanexpress.synapse.test.architecture.SynapseArchitecture.PROTOCOL_FREE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseArchitectureTest {

    private static final String GOOD = "com.example.arch.good";

    private static final String COMPONENT = "org.springframework.stereotype.Component";

    private static final List<SynapseRule> ALL_RULES = List
            .of(PROTOCOL_FREE, CALL_DIRECTIONS,
                    BUSINESS_DOES_NOT_USE_SERVICE_MODEL, OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT,
                    NO_ENTITIES_IN_OPERATION_SIGNATURES, NO_SYNAPSE_INTERNALS, NULL_MARKED_MODELS);

    private static final List<SynapseRule> DIRECTIONS = List
            .of(NOTHING_CALLS_API, ONLY_API_AND_OPERATIONS_CALL_OPERATIONS, ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS,
                    ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS, ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA,
                    ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS,
                    ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL);

    /** The rules a rule set lists: {@link #ALL_RULES} with CALL_DIRECTIONS as its parts. */
    private static final List<SynapseRule> LEAF_RULES = Stream
            .of(List.of(PROTOCOL_FREE), DIRECTIONS,
                    List
                            .of(BUSINESS_DOES_NOT_USE_SERVICE_MODEL, OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT,
                                    NO_ENTITIES_IN_OPERATION_SIGNATURES, NO_SYNAPSE_INTERNALS, NULL_MARKED_MODELS))
            .flatMap(List::stream)
            .toList();

    private static final List<SynapseRule> EVERY_RULE = Stream
            .concat(LEAF_RULES.stream(), Stream.of(CALL_DIRECTIONS))
            .toList();

    private static final String BAD_EDGES = "com.example.arch.bad.edges";

    /** Each origin class of the bad edges fixture, with the targets it may not depend on (all of its dependencies). */
    private static final Map<String, List<String>> FORBIDDEN_EDGES = Map
            .of("api.ApiEdges", List
                    .of("business.EdgeBusiness", "client.EdgeClient", "data.EdgeData",
                            "publisher.EdgePublisher"),
                    "model.ModelEdges", List
                            .of("operation.EdgeOperation", "business.EdgeBusiness",
                                    "client.EdgeClient", "data.EdgeData", "publisher.EdgePublisher"),
                    "contract.ContractEdges", List
                            .of("operation.EdgeOperation", "business.EdgeBusiness",
                                    "model.EdgeModel", "client.EdgeClient", "data.EdgeData", "publisher.EdgePublisher"),
                    "client.ClientEdges", List.of("data.EdgeData", "publisher.EdgePublisher", "model.EdgeModel"),
                    "data.DataEdges", List.of("client.EdgeClient", "publisher.EdgePublisher", "model.EdgeModel"),
                    "publisher.PublisherEdges", List.of("client.EdgeClient", "data.EdgeData", "model.EdgeModel"));

    @Test
    void allowedCallDirectionsPass() {
        String root = "com.example.arch.edges";
        JavaClasses classes = importPackages(root);
        assertThat(dependencyTargets(classes.get(root + ".api.ApiUses")))
                .contains(root + ".operation.OperationUses", root + ".model.ModelUses",
                        root + ".contract.ContractUses");
        assertThat(dependencyTargets(classes.get(root + ".operation.OperationUses")))
                .contains(root + ".operation.OtherOperation", root + ".business.BusinessUses",
                        root + ".client.ClientUses", root + ".data.DataUses", root + ".publisher.PublisherUses",
                        root + ".model.ModelUses", root + ".contract.ContractUses");
        assertThat(dependencyTargets(classes.get(root + ".business.BusinessUses")))
                .contains(root + ".business.BusinessHelper", root + ".client.ClientUses", root + ".data.DataUses",
                        root + ".publisher.PublisherUses", root + ".contract.ContractUses",
                        root + ".model.event.EdgeEvent");
        assertThat(dependencyTargets(classes.get(root + ".publisher.PublisherUses")))
                .contains(root + ".model.event.EdgeEvent", root + ".contract.ContractUses");
        assertThat(dependencyTargets(classes.get(root + ".config.Wiring")))
                .contains(root + ".operation.OperationUses", root + ".business.BusinessUses",
                        root + ".client.ClientUses", root + ".data.DataUses", root + ".publisher.PublisherUses",
                        root + ".model.ModelUses", root + ".contract.ContractUses");

        assertThat(bind(CALL_DIRECTIONS, root).evaluate(classes).getFailureReport().getDetails()).isEmpty();
    }

    @Test
    void callDirectionsOutsideTheAllowListFail() {
        List<String> violations = violations(CALL_DIRECTIONS, BAD_EDGES);

        int expected = FORBIDDEN_EDGES.values().stream().mapToInt(List::size).sum();
        assertThat(expected).isEqualTo(24);
        assertThat(violations).hasSize(expected);
        FORBIDDEN_EDGES
                .forEach((origin, targets) -> targets
                        .forEach(target -> assertThat(violations)
                                .as("%s -> %s", origin, target)
                                .anySatisfy(violation -> assertThat(violation)
                                        .contains(BAD_EDGES + "." + origin + ".use(")
                                        .contains("has parameter of type <" + BAD_EDGES + "." + target + ">"))));
        // a publisher may use the service's events
        assertThat(violations)
                .noneSatisfy(violation -> assertThat(violation)
                        .contains("of type <" + BAD_EDGES + ".model.event.EdgeEvent>"));
    }

    @Test
    void eachDirectionChecksTheDependenciesOnItsTargetLayer() {
        JavaClasses classes = importPackages(BAD_EDGES);
        Map<SynapseRule, String> targets = Map
                .of(ONLY_API_AND_OPERATIONS_CALL_OPERATIONS, "operation.EdgeOperation",
                        ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS, "business.EdgeBusiness",
                        ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS, "client.EdgeClient",
                        ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA, "data.EdgeData",
                        ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS, "publisher.EdgePublisher",
                        ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL, "model.EdgeModel");

        targets.forEach((direction, target) -> {
            long expected = FORBIDDEN_EDGES.values().stream().filter(edges -> edges.contains(target)).count();
            assertThat(bind(direction, BAD_EDGES).evaluate(classes).getFailureReport().getDetails())
                    .as(direction.name())
                    .hasSize((int) expected)
                    .allSatisfy(violation -> assertThat(violation)
                            .contains("has parameter of type <" + BAD_EDGES + "." + target + ">"));
        });
        assertThat(bind(NOTHING_CALLS_API, BAD_EDGES).evaluate(classes).hasViolation()).isFalse();
        assertThat(bind(CALL_DIRECTIONS, GOOD).getDescription())
                .contains("api layer")
                .contains("operation layer")
                .contains("business layer")
                .contains("client layer")
                .contains("data layer")
                .contains("publisher layer")
                .contains("model layer");
    }

    @Test
    void exceptLeavesOutOneDirection() {
        JavaClasses classes = importPackages(BAD_EDGES);
        SynapseRules rules = SynapseArchitecture
                .forRoots(BAD_EDGES)
                .except(ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA, "legacy data access, removed in 3.2");

        List<String> violations = rules.asArchRule().evaluate(classes).getFailureReport().getDetails();

        assertThat(violations)
                .hasSize(24 - 5)
                .noneSatisfy(violation -> assertThat(violation).contains("EdgeData>"))
                .anySatisfy(violation -> assertThat(violation).contains("EdgeClient>"));
        assertThat(rules.rules())
                .doesNotContain(ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA)
                .contains(ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rules.except(CALL_DIRECTIONS, "all directions"))
                .withMessageContaining("CALL_DIRECTIONS is not in this set");

        SynapseRules withoutDirections = SynapseArchitecture.forRoots(BAD_EDGES).except(CALL_DIRECTIONS, "all");
        assertThat(withoutDirections.rules()).doesNotContainAnyElementsOf(DIRECTIONS);
        assertThat(withoutDirections.asArchRule().evaluate(classes).hasViolation()).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withoutDirections.except(NOTHING_CALLS_API, "already left out"));
    }

    @Test
    void checkFailsWhenOneOfSeveralRootsHasNoImportedClass() {
        JavaClasses classes = importPackages(GOOD);
        String mistyped = "com.example.arch.goood";

        assertThatThrownBy(() -> SynapseArchitecture.forRoots(GOOD, mistyped).check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No application classes were imported")
                .hasMessageContaining("no imported class is under " + mistyped + ".")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("is under " + GOOD + "."));
        // a root that holds only classes of a nested root is not missing
        assertThatNoException().isThrownBy(() -> SynapseArchitecture.forRoots("com.example.arch", GOOD).check(classes));
    }

    @Test
    void springGeneratedClassesAreNotChecked() {
        String root = "com.example.arch.aot";
        String binding = root + ".api.rest.OrderBinding";
        JavaClasses classes = importPackages(root);
        assertThat(classes
                .get(root + ".OrdersApplication__BeanFactoryRegistrations")
                .isAnnotatedWith("org.springframework.aot.generate.Generated")).isTrue();
        assertThat(dependencyTargets(classes.get(root + ".OrdersApplication__BeanFactoryRegistrations")))
                .contains(binding);
        assertThat(dependencyTargets(classes.get(root + ".OrdersApplication__BeanFactoryRegistrations$Nested")))
                .contains(binding);
        JavaClass cglib = classes.get(root + ".config.AppConfig$$SpringCGLIB$$0");
        assertThat(cglib.getEnclosingClass()).isEmpty();
        assertThat(dependencyTargets(cglib)).contains(binding);

        assertThat(bind(NOTHING_CALLS_API, root).evaluate(classes).getFailureReport().getDetails()).isEmpty();
        assertThatNoException().isThrownBy(() -> SynapseArchitecture.forRoots(root).check(classes));
    }

    @Test
    void goodFixturePassesAllRules() {
        // Synapse's own classes are imported too: they live in ..operation.. packages and use ..internal.., which
        // would break the rules if they were checked like the application's classes.
        JavaClasses classes = importPackages(GOOD, "io.americanexpress.synapse.core");
        assertThat(dependencyTargets(classes.get(BookLookup.class)))
                .contains(BusinessOperation.class.getName())
                .contains("com.example.arch.good.contract.model.Book")
                .contains("com.example.arch.good.model.event.BookViewed");
        assertThat(classes
                .stream()
                .filter(javaClass -> javaClass
                        .getPackageName()
                        .startsWith("io.americanexpress.synapse.core.autoconfigure"))
                .flatMap(javaClass -> dependencyTargets(javaClass).stream()))
                .anyMatch(target -> target.contains(".internal."));

        assertThatNoException().isThrownBy(() -> SynapseArchitecture.forRoots(GOOD).check(classes));
        assertThat(ALL_RULES)
                .allSatisfy(rule -> assertThat(bind(rule, GOOD).evaluate(classes).hasViolation())
                        .as(rule.name())
                        .isFalse());
        assertThat(SynapseArchitecture.forRoots(GOOD).asArchRule().evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    void forApplicationUsesThePackageOfTheApplicationClass() {
        SynapseRules rules = SynapseArchitecture.forApplication(BooksApplication.class);

        assertThat(rules.roots()).containsExactly(GOOD);
        assertThatNoException().isThrownBy(() -> rules.check(importPackages(GOOD)));
    }

    @Test
    void classesOutsideTheRootsAreNotChecked() {
        // bad.protocol breaks PROTOCOL_FREE, but it isn't under the root
        JavaClasses classes = importPackages(GOOD, "com.example.arch.bad.protocol");
        assertThatThrownBy(() -> SynapseArchitecture.forRoots("com.example.arch.bad.protocol").check(classes))
                .isInstanceOf(AssertionError.class);

        assertThatNoException().isThrownBy(() -> SynapseArchitecture.forRoots(GOOD).check(classes));
    }

    @Test
    void dependencyTargetsOutsideTheRootsAreNeverLayers() {
        // with this root, BookBusiness is in no layer, and the operation it uses is outside the roots
        JavaClasses classes = importPackages("com.example.arch.bad.calls");
        assertThat(dependencyTargets(classes.get(BookBusiness.class)))
                .contains("com.example.arch.bad.calls.operation.GetBookOperation");

        assertThat(bind(CALL_DIRECTIONS, "com.example.arch.bad.calls.business").evaluate(classes).hasViolation())
                .isFalse();
    }

    @Test
    void rootContainingTheDataLayerNameKeepsItsLayers() {
        JavaClasses classes = importPackages("com.acme.data.orders");
        SynapseRules rules = SynapseArchitecture.forRoots("com.acme.data.orders");

        assertThat(bind(PROTOCOL_FREE, "com.acme.data.orders").evaluate(classes).getFailureReport().getDetails())
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation)
                        .contains(ProbeOrderOperation.class.getName())
                        .contains("org.springframework.web.util.UriComponents"));
        assertThat(bind(CALL_DIRECTIONS, "com.acme.data.orders").evaluate(classes).getFailureReport().getDetails())
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation)
                        .contains(OrderStore.class.getName())
                        .contains(OrderPricing.class.getName()));
        assertThatNoException()
                .isThrownBy(() -> rules
                        .except(PROTOCOL_FREE, "probe")
                        .except(CALL_DIRECTIONS, "store")
                        .check(classes));
    }

    @Test
    void rootContainingTheApiLayerNameKeepsItsLayers() {
        JavaClasses classes = importPackages("com.x.api.gateway");

        assertThat(bind(CALL_DIRECTIONS, "com.x.api.gateway").evaluate(classes).getFailureReport().getDetails())
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation)
                        .contains(RouteAudit.class.getName())
                        .contains(GetRouteOperation.class.getName()));
        assertThatNoException()
                .isThrownBy(() -> SynapseArchitecture
                        .forRoots("com.x.api.gateway")
                        .except(CALL_DIRECTIONS, "audit")
                        .check(classes));
    }

    @Test
    void layerIsTheFirstSegmentBelowTheMostSpecificRoot() {
        Roots roots = Roots.of("com.acme", "com.acme.books");

        assertThat(roots.placementOf("com.acme.books.operation"))
                .isEqualTo(new Placement("com.acme.books", List.of("operation")));
        assertThat(placement(roots, "com.acme.books.operation").layer()).isEqualTo(Layer.OPERATION);
        assertThat(placement(roots, "com.acme.data.orders").layer()).isEqualTo(Layer.DATA);
        assertThat(placement(roots, "com.acme.books.config.operation").layer()).isNull();
        assertThat(placement(roots, "com.acme.books").layer()).isNull();
        assertThat(placement(roots, "com.acme.books.business.model.event").isFollowedBy("model")).isTrue();
        assertThat(placement(roots, "com.acme.books.config.event").isFollowedBy("event")).isFalse();
        assertThat(roots.placementOf("com.acmex.books.operation")).isNull();
        assertThat(roots.placementOf("org.thirdparty.api")).isNull();
    }

    @Test
    void synapsePackagesAreCheckedOnlyUnderARootInsideThem() {
        Roots company = Roots.of("io.americanexpress");
        Roots sample = Roots.of("io.americanexpress.synapse.samples.books");

        assertThat(company.placementOf("io.americanexpress.synapse.core.operation")).isNull();
        assertThat(placement(company, "io.americanexpress.operation").layer()).isEqualTo(Layer.OPERATION);
        assertThat(placement(sample, "io.americanexpress.synapse.samples.books.operation").layer())
                .isEqualTo(Layer.OPERATION);
    }

    @Test
    void rootsMustBePackageNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(SynapseArchitecture::forRoots)
                .withMessageContaining("At least one root package");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SynapseArchitecture.forRoots(""))
                .withMessageContaining("unnamed package");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SynapseArchitecture.forRoots("com.acme", "com..acme"))
                .withMessageContaining("Not a package name: \"com..acme\"");
        assertThatIllegalArgumentException().isThrownBy(() -> SynapseArchitecture.forRoots("com.acme.*"));
        assertThatIllegalArgumentException().isThrownBy(() -> SynapseArchitecture.forRoots("1com.acme"));
    }

    @Test
    void checkImportsTheRootsFromTheClassPathWithoutTestClasses() {
        // the fixtures are test classes, so check() imports none of them
        assertThatThrownBy(() -> SynapseArchitecture.forRoots(GOOD).check())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No application classes were imported")
                .hasMessageContaining(GOOD);
        // classes in a jar on the class path are imported
        assertThatNoException().isThrownBy(() -> SynapseArchitecture.forRoots("org.jspecify.annotations").check());
    }

    @Test
    void operationImportingSpringWebFails() {
        List<String> violations = violations(PROTOCOL_FREE, "com.example.arch.bad.protocol");

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(ProbeOperation.class.getName())
                        .contains("org.springframework.web.util.UriComponentsBuilder"));
        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(HandWrittenMapper.class.getName())
                        .contains("com.google.protobuf.ByteString"));
        assertThatThrownBy(() -> SynapseArchitecture
                .forRoots("com.example.arch.bad.protocol")
                .check(importPackages("com.example.arch.bad.protocol")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ProbeOperation.class.getName())
                .hasMessageContaining("protocol-agnostic");
    }

    @Test
    void generatedProtobufIsExempt() {
        JavaClasses classes = importPackages("com.example.arch.good.contract.proto");
        assertThat(dependencyTargets(classes.get(BookProto.BookMessage.class)))
                .contains("com.google.protobuf.MessageLiteOrBuilder");
        assertThat(dependencyTargets(classes.get(BookProto.BookMessage.TitleCase.class)))
                .contains("com.google.protobuf.Internal$EnumLite");
        assertThat(dependencyTargets(classes.get(BookProto.class)))
                .contains("com.google.protobuf.ExtensionRegistryLite");
        assertThat(dependencyTargets(classes.get(BookStatus.class)))
                .contains("com.google.protobuf.ProtocolMessageEnum");
        assertThat(dependencyTargets(classes.get(BookServiceGrpc.BookServiceStub.class))).contains("io.grpc.Channel");
        // java_multiple_files=true: an outer class with no nested message, and a top-level message with a nested enum
        assertThat(dependencyTargets(classes.get(BookCatalogProto.class)))
                .contains("com.google.protobuf.GeneratedFile")
                .contains("com.google.protobuf.Descriptors$FileDescriptor");
        assertThat(dependencyTargets(classes.get(Catalog.EntryCase.class)))
                .contains("com.google.protobuf.Internal$EnumLite");
        assertThat(classes.get(BookProto.class).getPackage().isAnnotatedWith("org.jspecify.annotations.NullMarked"))
                .isFalse();

        assertThat(bind(PROTOCOL_FREE, GOOD).evaluate(classes).hasViolation()).isFalse();
        assertThat(bind(NULL_MARKED_MODELS, GOOD).evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    void businessCallingOperationFails() {
        List<String> violations = violations(CALL_DIRECTIONS, "com.example.arch.bad.calls");

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(BookBusiness.class.getName())
                        .contains("com.example.arch.bad.calls.operation.GetBookOperation"));
        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(BookConfig.class.getName())
                        .contains("com.example.arch.bad.calls.api.BookApi"));
        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(BookStore.class.getName())
                        .contains(BookBusiness.class.getName()));
        assertThat(bind(CALL_DIRECTIONS, GOOD).getDescription())
                .contains("api layer")
                .contains("operation layer")
                .contains("business layer");
    }

    @Test
    void thirdPartyPackagesNamedLikeLayersAreNotLayers() {
        JavaClasses classes = importPackages(GOOD);
        assertThat(dependencyTargets(classes.get(BookLookup.class)))
                .contains(Tracing.class.getName())
                .contains(StoredObject.class.getName())
                .contains(BookDetails.class.getName());
        assertThat(dependencyTargets(classes.get(TracingConfig.class))).contains(Tracing.class.getName());

        assertThat(bind(CALL_DIRECTIONS, GOOD).evaluate(classes).hasViolation()).isFalse();
        assertThat(bind(BUSINESS_DOES_NOT_USE_SERVICE_MODEL, GOOD).evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    void businessUsingServiceModelFails() {
        List<String> violations = violations(BUSINESS_DOES_NOT_USE_SERVICE_MODEL, "com.example.arch.bad.servicemodel");

        assertThat(violations)
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation)
                        .contains("com.example.arch.bad.servicemodel.business.BookBusiness")
                        .contains("com.example.arch.bad.servicemodel.model.BookView"));
    }

    @Test
    void businessUsingModelEventPasses() {
        JavaClasses classes = importPackages(GOOD);
        assertThat(dependencyTargets(classes.get(BookLookup.class)))
                .contains("com.example.arch.good.model.event.BookViewed")
                .contains("com.example.arch.good.contract.model.Book")
                .contains("com.example.arch.good.business.model.BookDetails");

        assertThat(bind(BUSINESS_DOES_NOT_USE_SERVICE_MODEL, GOOD).evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    void businessBeansUsedOnlyInsideTheBusinessLayerPass() {
        String root = "com.example.arch.businesscalls";
        JavaClasses classes = importPackages(root);
        assertThat(dependencyTargets(classes.get(PriceBook.class)))
                .contains(DiscountStrategy.class.getName(), IsbnRules.class.getName(), BookMapper.class.getName(),
                        CatalogService.class.getName());
        assertThat(dependencyTargets(classes.get(AppConfig.class))).contains(PricingConfiguration.class.getName());
        assertThat(dependencyTargets(classes.get(GetPriceOperation.class)))
                .contains(PriceBook.class.getName(), Price.class.getName(), PriceInput.class.getName(),
                        PricingErrors.class.getName());
        assertThat(classes.get(BookSoldListener.class).isMetaAnnotatedWith(COMPONENT)).isTrue();
        assertThat(classes.get(BookMapperImpl.class).isMetaAnnotatedWith(COMPONENT)).isTrue();
        assertThat(classes.get(PricingConfiguration.class).isMetaAnnotatedWith(COMPONENT)).isTrue();
        // a sealed business result, a business annotation, a business exception, business.model types that aren't
        // records, and a BusinessOperation interface whose execute compiles to execute(Object)
        String business = root + ".business.";
        JavaClass outcomeOperation = classes.get(root + ".operation.GetOutcomeOperation");
        assertThat(dependencyTargets(outcomeOperation))
                .contains(business + "PriceOutcome", business + "Audited", business + "PricingException",
                        business + "QuoteLookup", business + "model.Discount", business + "model.Money");
        assertThat(classes.get(business + "PriceOutcome").isSealed()).isTrue();
        assertThat(classes.get(business + "Audited").isAnnotation()).isTrue();
        assertThat(outcomeOperation.getMethodCallsFromSelf())
                .anySatisfy(call -> assertThat(call.getDescription())
                        .contains("calls method <" + business + "QuoteLookup.execute(java.lang.Object)>"))
                .anySatisfy(call -> assertThat(call.getDescription())
                        .contains("calls method <" + business + "OutcomeBook.execute(" + business
                                + "model.PriceInput)>"));
        assertThat(outcomeOperation.getConstructorCallsFromSelf())
                .anySatisfy(call -> assertThat(call.getTargetOwner().getName()).isEqualTo(business + "model.Money"))
                .anySatisfy(call -> assertThat(call.getTargetOwner().getName())
                        .isEqualTo(business + "PricingException"));

        assertThat(
                bind(OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT, root).evaluate(classes).getFailureReport().getDetails())
                .isEmpty();
        assertThatNoException().isThrownBy(() -> SynapseArchitecture.forRoots(root).check(classes));
    }

    @Test
    void operationReachingBusinessCodeAroundTheContractFails() {
        String operations = "com.example.arch.bad.businesscalls.operation.";
        String business = "com.example.arch.bad.businesscalls.business.";
        List<String> violations = violations(OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT,
                "com.example.arch.bad.businesscalls");

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operations + "GetPriceOperation")
                        .contains(business + "PricingService is a Spring bean in the business layer that doesn't "
                                + "implement " + BusinessOperation.class.getName()))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operations + "GetPriceOperation")
                        .contains(business + "PricingPolicy is an abstract class in the business layer"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operations + "MapPriceOperation")
                        .contains(business + "BookMapper is an interface in the business layer"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operations + "QuotePriceOperation.quote(java.lang.String)> calls method <"
                                + business + "PriceBook.quote(java.lang.String)>")
                        .contains("call only execute(...) on a BusinessOperation"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operations + "QuotePriceOperation.quoter()> references method <" + business
                                + "PriceBook.quote(java.lang.String)>")
                        .contains("call only execute(...) on a BusinessOperation"))
                // execute(...), a business model and a business error enum are allowed
                .noneSatisfy(violation -> assertThat(violation).contains(operations + "PriceOperation"))
                .noneSatisfy(violation -> assertThat(violation).contains("PriceBook.execute(java.lang.String)>"));
    }

    @Test
    void operationConstructingOrCallingBusinessCodeAroundTheContractFails() {
        String operation = "com.example.arch.bad.businesscalls.operation.ConstructingOperation.";
        String business = "com.example.arch.bad.businesscalls.business.";
        List<String> violations = violations(OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT,
                "com.example.arch.bad.businesscalls");

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operation + "constructed(java.lang.String)> calls constructor <" + business
                                + "PriceBook.<init>()>")
                        .contains("operations don't construct business code"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operation + "factory()> references constructor <" + business + "PriceBook.<init>()>")
                        .contains("operations don't construct business code"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operation + "helper(long)> calls constructor <" + business
                                + "DiscountCalculator.<init>()>")
                        .contains("operations don't construct business code"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operation + "rounded(long)> calls method <" + business + "PriceMath.round(long)>")
                        .contains("operations don't call static methods of business code"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(operation + "overload(java.lang.String)> calls method <" + business
                                + "PriceBook.execute(java.lang.String, int)>")
                        .contains("call only execute(...) on a BusinessOperation"))
                // execute(I) itself, records, business.model classes, exceptions, enum and record methods are allowed
                .noneSatisfy(violation -> assertThat(violation)
                        .contains("calls method <" + business + "PriceBook.execute(java.lang.String)>"))
                .noneSatisfy(violation -> assertThat(violation)
                        .contains("com.example.arch.bad.businesscalls.operation.PriceOperation"));
    }

    @Test
    void entityAsOperationOutputFails() {
        List<String> violations = violations(NO_ENTITIES_IN_OPERATION_SIGNATURES, "com.example.arch.bad.entity");

        assertThat(violations)
                .hasSize(3)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(com.example.arch.bad.entity.operation.GetBookOperation.class.getName())
                        .contains("output")
                        .contains("com.example.arch.bad.entity.data.BookEntity")
                        .contains("@jakarta.persistence.Entity"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(ListBooksOperation.class.getName())
                        .contains("output")
                        .contains("com.example.arch.bad.entity.data.BookRecord")
                        .contains("io.americanexpress.synapse.data.archfixture.DataCapability"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(SaveBooksOperation.class.getName())
                        .contains("input")
                        .contains("com.example.arch.bad.entity.data.BookEntity"));
    }

    @Test
    void importingSynapseInternalFails() {
        List<String> violations = violations(NO_SYNAPSE_INTERNALS, "com.example.arch.bad.internal");

        assertThat(violations)
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation)
                        .contains(ContextPeek.class.getName())
                        .contains("io.americanexpress.synapse.core.internal.context.ContextScope"));
    }

    @Test
    void unmarkedModelPackageFails() {
        List<String> violations = violations(NULL_MARKED_MODELS, "com.example.arch.bad.unmarked");

        assertThat(violations)
                .singleElement()
                .asString()
                .contains("com.example.arch.bad.unmarked.model.Book")
                .contains("@NullMarked");
    }

    @Test
    void everyModelPackageIsNullMarkedButOnlyCoreLayersAreProtocolFree() {
        String root = "com.example.arch.bad.boundarymodel";
        JavaClasses classes = importPackages(root);
        assertThat(dependencyTargets(classes.get(AuthorResponse.class)))
                .contains("org.springframework.http.HttpStatus");
        assertThat(dependencyTargets(classes.get(BookResponse.class))).contains("org.springframework.http.HttpStatus");

        // client.model and api.rest.model are client and api code: they may use protocol types...
        assertThat(bind(PROTOCOL_FREE, root).evaluate(classes).hasViolation()).isFalse();
        // ...but every model package needs nullness, whatever its layer
        assertThat(bind(NULL_MARKED_MODELS, root).evaluate(classes).getFailureReport().getDetails())
                .hasSize(2)
                .anySatisfy(violation -> assertThat(violation)
                        .contains(AuthorResponse.class.getName())
                        .contains("@NullMarked"))
                .anySatisfy(violation -> assertThat(violation)
                        .contains(BookResponse.class.getName())
                        .contains("@NullMarked"));
    }

    @Test
    void exceptRemovesRuleWithReason() {
        String root = "com.example.arch.bad.protocol";
        JavaClasses classes = importPackages(root);
        assertThatThrownBy(() -> SynapseArchitecture.forRoots(root).check(classes)).isInstanceOf(AssertionError.class);

        SynapseRules rules = SynapseArchitecture.forRoots(root).except(PROTOCOL_FREE, "legacy adapter");

        assertThatNoException().isThrownBy(() -> rules.check(classes));
        assertThat(rules.asArchRule().evaluate(classes).hasViolation()).isFalse();
        assertThat(rules.rules()).hasSize(LEAF_RULES.size() - 1).doesNotContain(PROTOCOL_FREE);
        assertThat(rules.roots()).containsExactly(root);
        assertThat(SynapseArchitecture.forRoots(root).rules()).containsExactlyElementsOf(LEAF_RULES);
    }

    @Test
    void exceptRequiresReasonAndRuleInTheSet() {
        SynapseRules rules = SynapseArchitecture.forRoots(GOOD);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> rules.except(PROTOCOL_FREE, " "))
                .withMessageContaining("reason")
                .withMessageContaining("PROTOCOL_FREE");
        assertThatIllegalArgumentException().isThrownBy(() -> rules.except(PROTOCOL_FREE, ""));
        SynapseRules withoutProtocolFree = rules.except(PROTOCOL_FREE, "legacy adapter");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> withoutProtocolFree.except(PROTOCOL_FREE, "excepted twice"))
                .withMessageContaining("PROTOCOL_FREE is not in this set");
        assertThatThrownBy(() -> rules.rules().add(PROTOCOL_FREE)).isInstanceOf(UnsupportedOperationException.class);

        SynapseRules none = rules;
        for (SynapseRule rule : ALL_RULES) {
            none = none.except(rule, "everything excepted");
        }
        SynapseRules empty = none;
        assertThat(empty.rules()).isEmpty();
        assertThatNoException().isThrownBy(() -> empty.check(importPackages(GOOD, "com.example.arch.bad")));
    }

    @Test
    void checkFailsWhenNoApplicationClassIsImported() {
        JavaClasses nothing = importPackages("com.example.arch.doesnotexist");
        JavaClasses synapseOnly = importPackages("io.americanexpress.synapse.core");
        JavaClasses otherPackages = importPackages("com.example.arch.bad.protocol");
        assertThat(nothing).isEmpty();
        assertThat(synapseOnly).isNotEmpty();

        assertThat(List.of(nothing, synapseOnly, otherPackages)).allSatisfy(classes -> {
            assertThatThrownBy(() -> SynapseArchitecture.forRoots(GOOD).check(classes))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("No application classes were imported")
                    .hasMessageContaining("Check the root packages");
            assertThat(SynapseArchitecture.forRoots(GOOD).asArchRule().evaluate(classes).hasViolation()).isTrue();
            // each rule on its own still tolerates an import with nothing it applies to
            assertThat(ALL_RULES)
                    .allSatisfy(rule -> assertThat(bind(rule, GOOD).evaluate(classes).hasViolation()).isFalse());
        });

        ArchRule guard = SynapseRules.applicationClassesImported(Roots.of(GOOD));
        assertThatThrownBy(() -> guard.check(nothing)).isInstanceOf(AssertionError.class);
        assertThatNoException().isThrownBy(() -> guard.check(importPackages(GOOD)));
        assertThat(guard.allowEmptyShould(false)).isSameAs(guard);
        assertThat(guard.because("the test must check something").getDescription())
                .endsWith(", because the test must check something");
        assertThat(guard.as("renamed").getDescription()).isEqualTo("renamed");
    }

    @Test
    void everyRuleExplainsWhy() {
        assertThat(EVERY_RULE).allSatisfy(rule -> {
            assertThat(bind(rule, GOOD).getDescription()).contains("because");
            assertThat(rule).hasToString(rule.name());
        });
    }

    private static ArchRule bind(SynapseRule rule, String... roots) {
        return rule.toArchRule(Roots.of(roots));
    }

    private static Placement placement(Roots roots, String packageName) {
        return Objects.requireNonNull(roots.placementOf(packageName), packageName);
    }

    private static JavaClasses importPackages(String... packages) {
        return new ClassFileImporter().importPackages(packages);
    }

    private static List<String> violations(SynapseRule rule, String root) {
        return bind(rule, root).evaluate(importPackages(root)).getFailureReport().getDetails();
    }

    private static List<String> dependencyTargets(JavaClass javaClass) {
        return javaClass
                .getDirectDependenciesFromSelf()
                .stream()
                .map(Dependency::getTargetClass)
                .map(JavaClass::getName)
                .toList();
    }
}
