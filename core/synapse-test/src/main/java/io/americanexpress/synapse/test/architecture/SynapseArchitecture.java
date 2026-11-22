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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageResult;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The Synapse layering rules as ArchUnit rules, for consumer projects. Apply all of them with one test:
 *
 * <pre>{@code
 * @Test
 * void followsSynapseArchitecture() {
 *     SynapseArchitecture.forApplication(BooksApplication.class).check();
 * }
 * }</pre>
 *
 * <p>
 * {@link #forApplication} takes the package of the {@code @SpringBootApplication} class as the root;
 * {@link #forRoots} takes the root packages themselves, for example for a library module without an application
 * class. {@link SynapseRules#check()} imports the classes under the roots from the class path, without test class
 * directories (a sibling module's {@code -tests.jar} is imported as application code).
 * {@link SynapseRules#check(JavaClasses)} checks classes imported by the caller instead.
 *
 * <p>
 * A service lists the roots of the business, client, data and publisher modules it uses next to its own, for example
 * {@code forRoots("com.acme.books", "com.acme.pricing")}; {@code check()} imports them from their jars. Calls into a
 * module whose root isn't listed are not checked, and a module's classes are layer code only under
 * {@code <root>.<layer>..}.
 *
 * <p>
 * A rule can be left out only with a written reason: {@code SynapseArchitecture.forApplication(BooksApplication.class)
 * .except(SynapseArchitecture.PROTOCOL_FREE, "legacy adapter, removed in 3.2").check()}. The check fails when no class
 * under one of the roots was imported, so a mistyped root can't make the test pass without checking anything.
 *
 * <p>
 * <b>Roots and layers.</b> The rules check only the classes under the root packages. A class's layer is the first
 * segment of its package below its root, when that segment names one: {@code api}, {@code operation}, {@code model},
 * {@code business}, {@code contract}, {@code client}, {@code data} or {@code publisher}. With the root
 * {@code com.acme.books}, {@code com.acme.books.business.model} is in the business layer and
 * {@code com.acme.books.config} is in no layer. The root itself may contain layer names: with the root
 * {@code com.acme.data.orders}, {@code com.acme.data.orders.operation} is in the operation layer. Only these exact,
 * singular segments are layers: plural names such as {@code operations}, {@code models} or {@code clients} are not, and
 * classes in them aren't checked as layer code. When roots are nested, a class belongs to the most specific one.
 * Classes that Spring generates are neither checked nor layers: Spring AOT's classes (annotated
 * {@code org.springframework.aot.generate.Generated}, and the classes nested in them) and CGLIB subclasses (named
 * {@code ..$$SpringCGLIB$$..}). Classes in no layer (such as {@code config}, {@code util} or {@code common}) aren't
 * checked by {@link #PROTOCOL_FREE}, and every layer may use them: the rules check direct dependencies only.
 * Configuration that wires api beans belongs under {@code api.<protocol>}.
 *
 * <p>
 * A dependency is on a layer only when its target is under one of the roots. The roots are checked together, as one
 * application. Third-party packages such as {@code io.opentelemetry.api} or
 * {@code software.amazon.awssdk.services.s3.model} are never layers, and neither are Synapse's own packages
 * ({@code io.americanexpress.synapse..}, unless a root is inside them): implementing Synapse's
 * {@link BusinessOperation} is not a dependency on an operation.
 */
public final class SynapseArchitecture {

    private static final String SYNAPSE_PACKAGE = "io.americanexpress.synapse";

    private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";

    private static final String COMPONENT = "org.springframework.stereotype.Component";

    private static final String SPRING_GENERATED = "org.springframework.aot.generate.Generated";

    private static final String SPRING_CGLIB = "$$SpringCGLIB$$";

    /**
     * Operation, model, business and contract classes don't depend on protocol code: Spring Web and HTTP, Servlet,
     * gRPC, GraphQL, protobuf, Kafka or Synapse's API plugins. Classes generated from {@code .proto} files are exempt.
     */
    public static final SynapseRule PROTOCOL_FREE = SynapseRule
            .of("PROTOCOL_FREE", roots -> noClasses()
                    .that(inLayers(roots, Layer.OPERATION, Layer.MODEL, Layer.BUSINESS, Layer.CONTRACT))
                    .and(notGeneratedFromProto())
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                            "io.grpc..", "graphql..", "org.springframework.graphql..", "com.google.protobuf..",
                            "org.apache.kafka..", "org.springframework.kafka..", "io.americanexpress.synapse.api..")
                    .because("operations, models, business logic and contracts stay protocol-agnostic, so every "
                            + "protocol binds the same code; protocol code belongs in the service's api.<protocol> "
                            + "packages")
                    .allowEmptyShould(true));

    /**
     * Nothing depends on the api layer: no other layer, and no class outside every layer (such as configuration).
     * Part of {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule NOTHING_CALLS_API = direction("NOTHING_CALLS_API", Layer.API, List.of(), false,
            "only the api packages bind protocols to operations, and nothing calls into them");

    /**
     * Only api code, other operations and classes outside every layer depend on the operation layer. Part of
     * {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule ONLY_API_AND_OPERATIONS_CALL_OPERATIONS = direction(
            "ONLY_API_AND_OPERATIONS_CALL_OPERATIONS", Layer.OPERATION, List.of(Layer.API), true,
            "protocol bindings call operations; the code operations call, models and contracts never call back");

    /**
     * Only operations, other business code and classes outside every layer depend on the business layer. Part of
     * {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS = direction(
            "ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS", Layer.BUSINESS, List.of(Layer.OPERATION), true,
            "business code is called by operations, so that bindings go through the operation pipeline; clients, "
                    + "data stores, publishers, models and contracts never call it");

    /**
     * Only operations, business code, other client code and classes outside every layer depend on the client layer.
     * Part of {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS = direction(
            "ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS", Layer.CLIENT, List.of(Layer.OPERATION, Layer.BUSINESS), true,
            "operations and business code call clients; bindings go through an operation, data stores and "
                    + "publishers don't call their peers, and models and contracts don't depend on code");

    /**
     * Only operations, business code, other data code and classes outside every layer depend on the data layer. Part
     * of {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA = direction(
            "ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA", Layer.DATA, List.of(Layer.OPERATION, Layer.BUSINESS), true,
            "operations and business code call data stores; bindings go through an operation, clients and "
                    + "publishers don't call their peers, and models and contracts don't depend on code");

    /**
     * Only operations, business code, other publisher code and classes outside every layer depend on the publisher
     * layer. Part of {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS = direction(
            "ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS", Layer.PUBLISHER, List.of(Layer.OPERATION, Layer.BUSINESS),
            true, "operations and business code call publishers; bindings go through an operation, clients and data "
                    + "stores don't call their peers, and models and contracts don't depend on code");

    /**
     * Only api code, operations, business code, other model classes and classes outside every layer depend on the
     * service's model layer; publishers may use its events ({@code model.event}). Client, data and contract code
     * doesn't. Business code's use of the model layer is narrowed further by
     * {@link #BUSINESS_DOES_NOT_USE_SERVICE_MODEL}. Part of {@link #CALL_DIRECTIONS}.
     */
    public static final SynapseRule ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL = direction(
            "ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL", Layer.MODEL,
            List.of(Layer.API, Layer.OPERATION, Layer.BUSINESS), true,
            (origin, target) -> origin.layer() == Layer.PUBLISHER && target.isFollowedBy("event"),
            "the service's models are what its bindings and operations exchange: clients and data stores use their "
                    + "own models, contracts don't depend on the service, and publishers use only its events");

    /**
     * Calls go one way, as an allow-list: {@code api → operation}; {@code operation → business | client | data |
     * publisher} (and other operations); {@code business → business | client | data | publisher}. Every layer may use
     * itself and the contract layer, and api, operation and business code may use the service's model layer
     * (publishers only its events). Client, data and publisher code don't use each other, models and contracts depend
     * on no other layer, and classes outside every layer (such as configuration) may use every layer but api. Made of
     * {@link #NOTHING_CALLS_API}, {@link #ONLY_API_AND_OPERATIONS_CALL_OPERATIONS},
     * {@link #ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS}, {@link #ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS},
     * {@link #ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA}, {@link #ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS} and
     * {@link #ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL}, one per target layer, each of which can be left
     * out on its own.
     */
    public static final SynapseRule CALL_DIRECTIONS = SynapseRule
            .composite("CALL_DIRECTIONS", NOTHING_CALLS_API, ONLY_API_AND_OPERATIONS_CALL_OPERATIONS,
                    ONLY_OPERATIONS_AND_BUSINESS_CALL_BUSINESS, ONLY_OPERATIONS_AND_BUSINESS_CALL_CLIENTS,
                    ONLY_OPERATIONS_AND_BUSINESS_CALL_DATA, ONLY_OPERATIONS_AND_BUSINESS_CALL_PUBLISHERS,
                    ONLY_API_OPERATIONS_AND_BUSINESS_USE_THE_SERVICE_MODEL);

    /**
     * Business code doesn't use the service's model layer, except the service's events ({@code model.event}). The
     * business layer's own models (for example in {@code business.model}) and contract models are allowed.
     */
    public static final SynapseRule BUSINESS_DOES_NOT_USE_SERVICE_MODEL = SynapseRule
            .of("BUSINESS_DOES_NOT_USE_SERVICE_MODEL", roots -> classes()
                    .that(inLayers(roots, Layer.BUSINESS))
                    .should(notDependOnLayer(roots, "the model layer (other than model.event)",
                            (origin, target) -> target.layer() == Layer.MODEL && !target.isFollowedBy("event")))
                    .because("a business owns its models and the calling operation maps the service's models to "
                            + "them; business code may use only the service's events and the contract models of the "
                            + "services it calls")
                    .allowEmptyShould(true));

    /**
     * Operations call business code only through {@link BusinessOperation}, and only its {@code execute} method.
     * Operation classes don't depend on a business-layer Spring bean (annotated or meta-annotated {@code @Component}),
     * interface or abstract class that isn't assignable to {@code BusinessOperation}; business models, value objects
     * and error enums that aren't beans are used directly, and so are annotation types, sealed types (such as a sealed
     * result) and every non-bean type in {@code business.model}. On a business type assignable to
     * {@code BusinessOperation}, operation classes call (or reference) no method other than {@code execute(I)}, so an
     * overload such as {@code execute(String, int)} fails. Operation classes construct (or reference the constructor
     * of) only business values: records, enums, exceptions and {@code business.model} classes that are neither beans
     * nor {@code BusinessOperation}s; {@code new PriceBook()} fails, and so does constructing a non-record builder or
     * helper class in {@code business}. They call static methods of no other concrete business class; a static call
     * whose business callee ArchUnit cannot resolve (its class is not in the imported set) is not flagged. Code
     * inside the business layer (helpers, strategies, mappers, validators, listeners and configuration) needn't
     * implement {@code BusinessOperation}.
     */
    public static final SynapseRule OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT = SynapseRule
            .of("OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT", roots -> classes()
                    .that(inLayers(roots, Layer.OPERATION))
                    .should(new BusinessCallsThroughContract(roots))
                    .because("Synapse validates and observes a business call only when it goes through "
                            + "BusinessOperation.execute; business models, value objects and error enums are used "
                            + "directly")
                    .allowEmptyShould(true));

    /**
     * The input and output types of a {@link ServiceOperation} (and the element type of an {@code Optional},
     * {@code List} or {@code PageResult} among them) aren't persistence entities: annotated {@code @Entity},
     * {@code @Document} or {@code @Table}, or implementing a Synapse data type.
     */
    public static final SynapseRule NO_ENTITIES_IN_OPERATION_SIGNATURES = SynapseRule
            .of("NO_ENTITIES_IN_OPERATION_SIGNATURES", roots -> classes()
                    .that(applicationClass(roots))
                    .and()
                    .areAssignableTo(ServiceOperation.class)
                    .should(new EntityFreeSignature())
                    .because("an operation's input and output are the service's models: an entity would expose the "
                            + "storage schema to every caller and protocol")
                    .allowEmptyShould(true));

    /**
     * Application code doesn't depend on Synapse's internal packages ({@code io.americanexpress.synapse..internal..}).
     */
    public static final SynapseRule NO_SYNAPSE_INTERNALS = SynapseRule
            .of("NO_SYNAPSE_INTERNALS", roots -> noClasses()
                    .that(applicationClass(roots))
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage(SYNAPSE_PACKAGE + "..internal..")
                    .because("Synapse's internal packages are implementation details that can change in any "
                            + "release; use Synapse's public API")
                    .allowEmptyShould(true));

    /**
     * Operation, model, business and contract classes, and every class in a {@code model} package in any layer (for
     * example {@code client.model} or {@code api.rest.model}), are in packages annotated {@code @NullMarked} (JSpecify).
     * Classes generated from {@code .proto} files are exempt.
     */
    public static final SynapseRule NULL_MARKED_MODELS = SynapseRule
            .of("NULL_MARKED_MODELS", roots -> classes()
                    .that(inLayers(roots, Layer.OPERATION, Layer.MODEL, Layer.BUSINESS, Layer.CONTRACT)
                            .or(inModelPackage(roots)))
                    .and(notGeneratedFromProto())
                    .and(notPackageInfo())
                    .should(beInNullMarkedPackage())
                    .because("Synapse reads JSpecify nullness for binding checks, OpenAPI required lists and gRPC "
                            + "wrapper mapping, and in an unmarked package every component counts as nullable")
                    .allowEmptyShould(true));

    private static final List<SynapseRule> ALL = List
            .of(PROTOCOL_FREE, CALL_DIRECTIONS, BUSINESS_DOES_NOT_USE_SERVICE_MODEL,
                    OPERATIONS_CALL_BUSINESS_THROUGH_CONTRACT, NO_ENTITIES_IN_OPERATION_SIGNATURES,
                    NO_SYNAPSE_INTERNALS, NULL_MARKED_MODELS);

    private SynapseArchitecture() {
    }

    /**
     * Returns every Synapse architecture rule for the application whose root package is the package of
     * {@code applicationClass}, usually the {@code @SpringBootApplication} class.
     *
     * @param applicationClass a class in the application's root package
     * @return the rules, which can be checked together or narrowed with {@link SynapseRules#except}
     * @throws IllegalArgumentException if the class is in the unnamed package
     */
    public static SynapseRules forApplication(Class<?> applicationClass) {
        return forRoots(applicationClass.getPackageName());
    }

    /**
     * Returns every Synapse architecture rule for an application made of the classes under {@code rootPackages}.
     *
     * @param rootPackages the application's root packages, for example {@code "com.acme.books"}; layers are the
     *     packages directly below a root, such as {@code com.acme.books.operation}
     * @return the rules, which can be checked together or narrowed with {@link SynapseRules#except}
     * @throws IllegalArgumentException if no root is given, or a root is not a package name
     */
    public static SynapseRules forRoots(String... rootPackages) {
        return new SynapseRules(Roots.of(rootPackages), ALL);
    }

    private static DescribedPredicate<JavaClass> applicationClass(Roots roots) {
        return DescribedPredicate.describe("are application classes", roots::isApplicationClass);
    }

    private static DescribedPredicate<JavaClass> inLayers(Roots roots, Layer... layers) {
        Set<Layer> accepted = Set.of(layers);
        return DescribedPredicate
                .describe(
                        "are in the application's "
                                + Arrays.stream(layers).map(Layer::segment).collect(Collectors.joining(", "))
                                + " layer",
                        javaClass -> {
                            Placement placement = roots.placementOf(javaClass);
                            Layer layer = placement == null ? null : placement.layer();
                            return layer != null && accepted.contains(layer);
                        });
    }

    /**
     * Application classes in a package with a {@code model} segment anywhere below their root (for example
     * {@code client.model} or {@code api.rest.model}), whatever their layer: every model that crosses a boundary
     * needs nullness.
     */
    private static DescribedPredicate<JavaClass> inModelPackage(Roots roots) {
        return DescribedPredicate
                .describe("are in any of the application's model packages",
                        javaClass -> {
                            Placement placement = roots.placementOf(javaClass);
                            return placement != null && placement.segments().contains(Layer.MODEL.segment());
                        });
    }

    private static SynapseRule direction(String name, Layer target, List<Layer> callers, boolean fromOutsideLayers,
            String reason) {
        return direction(name, target, callers, fromOutsideLayers, (origin, dependency) -> false, reason);
    }

    /**
     * Returns a rule that only {@code target} itself, the {@code callers} layers and, if {@code fromOutsideLayers},
     * classes in no layer depend on the {@code target} layer. Dependencies for which {@code alsoAllowed} holds are
     * allowed from any layer.
     */
    private static SynapseRule direction(String name, Layer target, List<Layer> callers, boolean fromOutsideLayers,
            BiPredicate<Placement, Placement> alsoAllowed, String reason) {
        Set<Layer> allowed = EnumSet.of(target);
        allowed.addAll(callers);
        List<Layer> others = Arrays.stream(Layer.values()).filter(layer -> !allowed.contains(layer)).toList();
        String subjects = "are in the application's "
                + others.stream().map(Layer::segment).collect(Collectors.joining(", ")) + " layer"
                + (fromOutsideLayers ? "" : ", or in no layer");
        return SynapseRule
                .of(name, roots -> classes()
                        .that(DescribedPredicate.describe(subjects, javaClass -> {
                            Placement placement = roots.placementOf(javaClass);
                            if (placement == null) {
                                return false;
                            }
                            Layer layer = placement.layer();
                            return layer == null ? !fromOutsideLayers : !allowed.contains(layer);
                        }))
                        .should(notDependOnLayer(roots, "the " + target.segment() + " layer",
                                (origin, dependency) -> dependency.layer() == target
                                        && !alsoAllowed.test(origin, dependency)))
                        .because(reason)
                        .allowEmptyShould(true));
    }

    /**
     * Fails each dependency of a class on a class under the roots for which {@code forbidden} holds, given where the
     * origin and the target sit.
     */
    private static ArchCondition<JavaClass> notDependOnLayer(Roots roots, String layerDescription,
            BiPredicate<Placement, Placement> forbidden) {
        return new ArchCondition<>("not depend on " + layerDescription) {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Placement originPlacement = roots.placementOf(origin);
                if (originPlacement == null) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    Placement target = roots.placementOf(dependency.getTargetClass().getBaseComponentType());
                    if (target != null && forbidden.test(originPlacement, target)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static DescribedPredicate<JavaClass> notGeneratedFromProto() {
        return DescribedPredicate
                .describe("are not generated from .proto files",
                        javaClass -> !ProtoGenerated.isGenerated(javaClass));
    }

    private static DescribedPredicate<JavaClass> notPackageInfo() {
        return DescribedPredicate
                .describe("are not package-info",
                        javaClass -> !javaClass.getSimpleName().equals("package-info"));
    }

    private static ArchCondition<JavaClass> beInNullMarkedPackage() {
        return new ArchCondition<>("be in a package annotated @" + NULL_MARKED) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.getPackage().isAnnotatedWith(NULL_MARKED)) {
                    events
                            .add(SimpleConditionEvent
                                    .violated(javaClass,
                                            String
                                                    .format("%s is in package %s, which is not annotated @NullMarked: annotate its "
                                                            + "package-info.java with @%s %s",
                                                            javaClass.getDescription(),
                                                            javaClass.getPackageName(), NULL_MARKED,
                                                            javaClass.getSourceCodeLocation())));
                }
            }
        };
    }

    static boolean isInPackage(String packageName, String parent) {
        return packageName.equals(parent) || packageName.startsWith(parent + ".");
    }

    /**
     * The layers of a Synapse service, named by exact package segments.
     */
    enum Layer {
        API, OPERATION, MODEL, BUSINESS, CONTRACT, CLIENT, DATA, PUBLISHER;

        String segment() {
            return name().toLowerCase(Locale.ROOT);
        }

        static @Nullable Layer ofSegment(String segment) {
            for (Layer layer : values()) {
                if (layer.segment().equals(segment)) {
                    return layer;
                }
            }
            return null;
        }
    }

    /**
     * The application's root packages, which decide which classes are checked and what layer each is in.
     */
    record Roots(List<String> packages) {

        private static final Pattern PACKAGE_NAME = Pattern
                .compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*"
                        + "(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*");

        Roots {
            packages = List.copyOf(packages);
        }

        static Roots of(String... packages) {
            if (packages.length == 0) {
                throw new IllegalArgumentException("At least one root package is required");
            }
            for (String rootPackage : packages) {
                if (rootPackage.isEmpty()) {
                    throw new IllegalArgumentException("The unnamed package can't be a root: put the application's "
                            + "classes, and its application class, in a named package");
                }
                if (!PACKAGE_NAME.matcher(rootPackage).matches()) {
                    throw new IllegalArgumentException("Not a package name: \"" + rootPackage + "\"");
                }
            }
            return new Roots(List.of(packages));
        }

        /**
         * Returns whether a class is checked: it is under a root (and, unless that root is inside Synapse's own
         * packages, outside {@code io.americanexpress.synapse..}).
         */
        boolean isApplicationClass(JavaClass javaClass) {
            return placementOf(javaClass) != null;
        }

        /**
         * Returns where a class sits, or {@code null} when it isn't checked: it is under no root, is one of Synapse's
         * own classes, or Spring generated it (AOT or CGLIB).
         */
        @Nullable
        Placement placementOf(JavaClass javaClass) {
            return isSpringGenerated(javaClass) ? null : placementOf(javaClass.getPackageName());
        }

        /**
         * Returns where a package sits under the most specific root that contains it, or {@code null} when it is
         * under no root, or is one of Synapse's own packages under a root outside them.
         */
        @Nullable
        Placement placementOf(String packageName) {
            String root = null;
            for (String candidate : packages) {
                if (isInPackage(packageName, candidate) && (root == null || candidate.length() > root.length())) {
                    root = candidate;
                }
            }
            if (root == null || (isInPackage(packageName, SYNAPSE_PACKAGE) && !isInPackage(root, SYNAPSE_PACKAGE))) {
                return null;
            }
            List<String> segments = packageName.length() == root.length()
                    ? List.of()
                    : List.of(packageName.substring(root.length() + 1).split("\\."));
            return new Placement(root, segments);
        }
    }

    /**
     * Where a package sits: its root and the segments below it. The first segment is the layer, when it names one.
     */
    record Placement(String root, List<String> segments) {

        @Nullable
        Layer layer() {
            return segments.isEmpty() ? null : Layer.ofSegment(segments.get(0));
        }

        boolean isFollowedBy(String segment) {
            return layer() != null && segments.size() > 1 && segments.get(1).equals(segment);
        }
    }

    /**
     * Recognizes classes generated by protoc and the gRPC plugin from the class itself and its top-level class:
     * messages and enums (assignable to {@code MessageLiteOrBuilder} or {@code ProtocolMessageEnum}), protoc's outer
     * classes (assignable to {@code GeneratedFile}, which protobuf-java 4.x outer classes extend, with or without
     * {@code java_multiple_files}), and gRPC service classes (annotated {@code @GrpcGenerated}, CLASS retention).
     * Nested classes, such as oneof case enums and stubs, are generated when their top-level class is.
     */
    private static final class ProtoGenerated {

        private static final List<String> GENERATED_SUPERTYPES = List
                .of("com.google.protobuf.MessageLiteOrBuilder",
                        "com.google.protobuf.ProtocolMessageEnum", "com.google.protobuf.GeneratedFile");

        private static final String GRPC_GENERATED = "io.grpc.stub.annotations.GrpcGenerated";

        private ProtoGenerated() {
        }

        static boolean isGenerated(JavaClass javaClass) {
            return isGeneratedType(javaClass) || isGeneratedType(topLevel(javaClass));
        }

        private static boolean isGeneratedType(JavaClass javaClass) {
            return javaClass.isAnnotatedWith(GRPC_GENERATED)
                    || GENERATED_SUPERTYPES.stream().anyMatch(javaClass::isAssignableTo);
        }

    }

    /**
     * Returns the top-level class that {@code javaClass} is nested in, or the class itself.
     */
    private static JavaClass topLevel(JavaClass javaClass) {
        JavaClass current = javaClass;
        for (Optional<JavaClass> enclosing = current.getEnclosingClass(); enclosing
                .isPresent(); enclosing = current.getEnclosingClass()) {
            current = enclosing.get();
        }
        return current;
    }

    /**
     * Returns whether Spring generated a class: Spring AOT's classes (annotated
     * {@code org.springframework.aot.generate.Generated}, or nested in one that is) and CGLIB subclasses (named
     * {@code ..$$SpringCGLIB$$..}, with no annotation).
     */
    private static boolean isSpringGenerated(JavaClass javaClass) {
        return javaClass.isAnnotatedWith(SPRING_GENERATED) || topLevel(javaClass).isAnnotatedWith(SPRING_GENERATED)
                || javaClass.getName().contains(SPRING_CGLIB);
    }

    /**
     * Checks how an operation class reaches business code:
     * <ul>
     * <li>no dependency on a business-layer type outside the business contract: a Spring bean, interface or abstract
     * class not assignable to {@link BusinessOperation} (annotation types, sealed types and {@code business.model}
     * types are values, not contract);</li>
     * <li>on a business type assignable to {@code BusinessOperation}, no call or method reference other than
     * {@code execute(I)} (or the erased {@code execute(Object)} it overrides);</li>
     * <li>no construction (call or constructor reference) of business code other than business values: records,
     * enums, exceptions and {@code business.model} classes that aren't beans;</li>
     * <li>no call of a static method on other concrete business classes.</li>
     * </ul>
     */
    private static final class BusinessCallsThroughContract extends ArchCondition<JavaClass> {

        private static final String EXECUTE = "execute";

        private static final String CALL_EXECUTE_ONLY = ": call only execute(...) on a BusinessOperation, the only "
                + "method Synapse validates and observes";

        private final Roots roots;

        BusinessCallsThroughContract(Roots roots) {
            super("depend on business code only through BusinessOperation types (business models, value objects and "
                    + "error enums that aren't beans are free), call only their execute method, and construct only "
                    + "business values");
            this.roots = roots;
        }

        @Override
        public void check(JavaClass origin, ConditionEvents events) {
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass().getBaseComponentType();
                String kind = outsideContract(target);
                if (kind != null) {
                    events
                            .add(SimpleConditionEvent
                                    .violated(dependency, String
                                            .format("%s: %s is %s in the business layer that doesn't implement %s; "
                                                    + "call business code through a BusinessOperation",
                                                    dependency.getDescription(), target.getName(), kind,
                                                    BusinessOperation.class.getName())));
                }
            }
            List<JavaCodeUnitAccess<?>> calls = new ArrayList<>(origin.getMethodCallsFromSelf());
            calls.addAll(origin.getMethodReferencesFromSelf());
            for (JavaCodeUnitAccess<?> call : calls) {
                JavaClass owner = call.getTargetOwner();
                if (!inBusinessLayer(owner)) {
                    continue;
                }
                if (owner.isAssignableTo(BusinessOperation.class)) {
                    if (!isExecute(owner, call)) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription() + CALL_EXECUTE_ONLY));
                    }
                } else if (outsideContract(owner) == null && !isBusinessValue(owner) && isStatic(call)) {
                    events
                            .add(SimpleConditionEvent
                                    .violated(call, call.getDescription() + ": operations don't call static methods of "
                                            + "business code; call business code through a BusinessOperation"));
                }
            }
            List<JavaCodeUnitAccess<?>> constructions = new ArrayList<>(origin.getConstructorCallsFromSelf());
            constructions.addAll(origin.getConstructorReferencesFromSelf());
            for (JavaCodeUnitAccess<?> construction : constructions) {
                JavaClass owner = construction.getTargetOwner();
                if (inBusinessLayer(owner) && !isBusinessValue(owner)) {
                    events
                            .add(SimpleConditionEvent
                                    .violated(construction, construction.getDescription()
                                            + ": operations don't construct business code; inject the "
                                            + "BusinessOperation that does the work"));
                }
            }
        }

        /**
         * Returns what {@code target} is when it is a business-layer type outside the business contract, otherwise
         * {@code null}.
         */
        private @Nullable String outsideContract(JavaClass target) {
            if (!inBusinessLayer(target) || target.isAssignableTo(BusinessOperation.class) || target.isAnnotation()) {
                return null;
            }
            if (target.isMetaAnnotatedWith(COMPONENT)) {
                return "a Spring bean";
            }
            if (target.isSealed() || inBusinessModel(target)) {
                return null;
            }
            if (target.isInterface()) {
                return "an interface";
            }
            if (target.getModifiers().contains(JavaModifier.ABSTRACT) && !target.isEnum()) {
                return "an abstract class";
            }
            return null;
        }

        /**
         * Returns whether an operation may create {@code owner} or call its static methods: a business-layer record,
         * enum, exception or {@code business.model} class that is neither a bean nor a {@code BusinessOperation}.
         */
        private boolean isBusinessValue(JavaClass owner) {
            return !owner.isAssignableTo(BusinessOperation.class) && !owner.isMetaAnnotatedWith(COMPONENT)
                    && (owner.isRecord() || owner.isEnum() || owner.isAssignableTo(Throwable.class)
                            || inBusinessModel(owner));
        }

        /**
         * Returns whether {@code call} is {@code execute} with the parameter of the owner's {@code BusinessOperation}
         * input type, or the erased {@code execute(Object)} that a {@code BusinessOperation} interface inherits.
         */
        private static boolean isExecute(JavaClass owner, JavaCodeUnitAccess<?> call) {
            if (!call.getName().equals(EXECUTE)) {
                return false;
            }
            List<String> parameters = call
                    .getTarget()
                    .getRawParameterTypes()
                    .stream()
                    .map(JavaClass::getName)
                    .toList();
            if (parameters.equals(List.of(Object.class.getName()))) {
                return true;
            }
            List<Bound> arguments = EntityFreeSignature
                    .typeArguments(owner, Map.of(), BusinessOperation.class.getName());
            return arguments != null && arguments.size() == 2
                    && parameters.equals(List.of(arguments.get(0).resolved().type().toErasure().getName()));
        }

        private static boolean isStatic(JavaCodeUnitAccess<?> call) {
            return call
                    .getTarget()
                    .resolveMember()
                    .map(member -> member.getModifiers().contains(JavaModifier.STATIC))
                    .orElse(false);
        }

        private boolean inBusinessLayer(JavaClass javaClass) {
            Placement placement = roots.placementOf(javaClass);
            return placement != null && placement.layer() == Layer.BUSINESS;
        }

        private boolean inBusinessModel(JavaClass javaClass) {
            Placement placement = roots.placementOf(javaClass);
            return placement != null && placement.layer() == Layer.BUSINESS && placement.isFollowedBy("model");
        }
    }

    /**
     * Fails a {@link ServiceOperation} whose resolved input or output type ({@code I}, {@code O}, or the element type
     * of an {@code Optional}, {@code List} or {@code PageResult}) is a persistence entity. Checked by name, without
     * loading classes.
     */
    private static final class EntityFreeSignature extends ArchCondition<JavaClass> {

        private static final List<String> ENTITY_ANNOTATIONS = List
                .of("jakarta.persistence.Entity",
                        "org.springframework.data.mongodb.core.mapping.Document",
                        "org.springframework.data.relational.core.mapping.Table");

        private static final Set<String> CONTAINERS = Set
                .of(Optional.class.getName(), List.class.getName(),
                        PageResult.class.getName());

        private static final String SYNAPSE_DATA_PACKAGE = SYNAPSE_PACKAGE + ".data";

        EntityFreeSignature() {
            super("not take or return a persistence entity (@Entity, @Document, @Table or a Synapse data type, "
                    + "also as the element of an Optional, List or PageResult)");
        }

        @Override
        public void check(JavaClass operation, ConditionEvents events) {
            List<Bound> arguments = typeArguments(operation, Map.of(), ServiceOperation.class.getName());
            if (arguments == null || arguments.size() != 2) {
                return;
            }
            report(operation, "input", arguments.get(0), events);
            report(operation, "output", arguments.get(1), events);
        }

        private static void report(JavaClass operation, String role, Bound type, ConditionEvents events) {
            JavaClass checked = elementType(type);
            String reason = persistenceReason(checked);
            if (reason != null) {
                events
                        .add(SimpleConditionEvent
                                .violated(operation,
                                        String
                                                .format("%s has %s type %s, which %s %s", operation.getDescription(),
                                                        role,
                                                        checked.getName(), reason, operation.getSourceCodeLocation())));
            }
        }

        private static @Nullable String persistenceReason(JavaClass type) {
            for (String annotation : ENTITY_ANNOTATIONS) {
                if (type.isAnnotatedWith(annotation)) {
                    return "is annotated @" + annotation;
                }
            }
            for (JavaClass supertype : type.getAllClassesSelfIsAssignableTo()) {
                if (!supertype.getName().equals(type.getName())
                        && isInPackage(supertype.getPackageName(), SYNAPSE_DATA_PACKAGE)) {
                    return "implements " + supertype.getName();
                }
            }
            return null;
        }

        private static JavaClass elementType(Bound type) {
            Bound current = type.resolved();
            while (current.type() instanceof JavaParameterizedType parameterized
                    && CONTAINERS.contains(parameterized.toErasure().getName())
                    && parameterized.getActualTypeArguments().size() == 1) {
                current = new Bound(parameterized.getActualTypeArguments().get(0), current.scope()).resolved();
            }
            return current.type().toErasure().getBaseComponentType();
        }

        /**
         * Walks the generic supertypes of {@code type} to {@code target} and returns the type arguments given to
         * it, each with the type-variable bindings it must be read with; {@code null} if {@code type} doesn't extend
         * {@code target}.
         */
        private static @Nullable List<Bound> typeArguments(JavaClass type, Map<String, Bound> scope, String target) {
            List<JavaType> supertypes = new ArrayList<>();
            type.getSuperclass().ifPresent(supertypes::add);
            supertypes.addAll(type.getInterfaces());
            for (JavaType supertype : supertypes) {
                JavaClass raw = supertype.toErasure();
                List<Bound> arguments = supertype instanceof JavaParameterizedType parameterized
                        ? parameterized
                                .getActualTypeArguments()
                                .stream()
                                .map(argument -> new Bound(argument, scope))
                                .toList()
                        : List.of();
                if (raw.getName().equals(target)) {
                    return arguments;
                }
                Map<String, Bound> rawScope = new HashMap<>();
                List<JavaTypeVariable<JavaClass>> parameters = raw.getTypeParameters();
                for (int i = 0; i < Math.min(parameters.size(), arguments.size()); i++) {
                    rawScope.put(parameters.get(i).getName(), arguments.get(i));
                }
                List<Bound> found = typeArguments(raw, rawScope, target);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    /**
     * A type as written in a generic signature, with the bindings of the type variables of the class it appears in.
     */
    private record Bound(JavaType type, Map<String, Bound> scope) {

        Bound resolved() {
            Bound current = this;
            while (current.type() instanceof JavaTypeVariable<?> variable) {
                Bound bound = current.scope().get(variable.getName());
                if (bound == null) {
                    return current;
                }
                current = bound;
            }
            return current;
        }
    }
}
