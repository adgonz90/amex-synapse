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
package ${package}.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

/**
 * The service as a process of its own, started the way it is deployed: the packaged jar on a JVM ({@link #jvm}), or
 * the same jar running the code that AOT processing generated at build time ({@link #aot}). A native executable is
 * another launcher of the same kind, which only the command differs for.
 *
 * <p>
 * Before the launch, two free ports are picked (two {@link ServerSocket}s on port 0, open together so they differ,
 * then closed) and passed as {@code --server.port} and {@code --spring.grpc.server.port}, so no port is read from the
 * logs. Another process can take a port between the pick and the launch; the start then fails, and says so.
 * {@link Builder#start()} waits until {@code /health}, the readiness group, answers 200 UP; {@link Builder#run(Duration)}
 * waits for a run that ends by itself. Standard output and standard error go to one file, which every failure message
 * includes.
 *
 * <p>
 * The process gets only the variables the test sets, plus the few the operating system and the JVM need
 * ({@code PATH}, {@code SystemRoot}, the temporary directory, the home directory, the locale and the time zone). Any
 * other variable of the test's environment could configure it: relaxed binding reads {@code SERVER_*},
 * {@code MANAGEMENT_*} or {@code SYNAPSE_*} as properties, and {@code JAVA_TOOL_OPTIONS} changes the JVM.
 *
 * <p>
 * {@link #close()} stops it with {@link Process#destroy()}, then {@link Process#destroyForcibly()} after 30 seconds.
 * On Windows both are a forced kill, so no test may expect a graceful shutdown. A shutdown hook kills the process if
 * the test JVM ends first (an interrupted build), so no service is left running and holding the jar.
 */
public final class AppProcess implements AutoCloseable {

    /** How long a start may take until {@code /health} is UP. */
    private static final Duration STARTUP = Duration.ofSeconds(60);

    /** How long {@link #close()} waits after {@link Process#destroy()}. */
    private static final Duration STOP = Duration.ofSeconds(30);

    private static final Duration POLL = Duration.ofMillis(250);

    private static final String HOST = "localhost";

    /** The system property with the path of the executable jar. */
    private static final String JAR_PROPERTY = "service.jar";

    /** The system property with the path of the OpenTelemetry Java agent jar. */
    private static final String AGENT_PROPERTY = "service.otel-agent";

    /**
     * The variables of the test's environment that the process keeps, upper case (Windows names ignore case), beside
     * the locale categories ({@code LC_*}).
     */
    private static final Set<String> KEPT_VARIABLES = Set
            .of("PATH", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "TEMP", "TMP", "TMPDIR", "HOME", "USERPROFILE", "LANG",
                    "TZ");

    /** Numbers the runs that name no output file. */
    private static final AtomicInteger RUNS = new AtomicInteger();

    private final Launched launched;

    private final Target target;

    private AppProcess(Launched launched, Target target) {
        this.launched = launched;
        this.target = target;
    }

    /**
     * Returns the service's executable jar, which {@code package} built (and AOT-processed) before the integration
     * tests run. The pom's failsafe configuration passes its path as the system property {@value #JAR_PROPERTY}.
     *
     * @return the jar
     * @throws AssertionError when the property isn't set or the jar doesn't exist
     */
    public static Path packagedJar() {
        String jar = System.getProperty(JAR_PROPERTY);
        if (jar == null || !Files.isRegularFile(Path.of(jar))) {
            throw new AssertionError("No jar at %s=%s: mvn verify packages it before the integration tests"
                    .formatted(JAR_PROPERTY, jar));
        }
        return Path.of(jar);
    }

    /**
     * Returns the OpenTelemetry Java agent jar that the build copies to {@code target/otel} (the pom's
     * {@code otel-agent} execution). The pom's failsafe configuration passes its path as the system property
     * {@value #AGENT_PROPERTY}.
     *
     * @return the agent jar
     * @throws AssertionError when the property isn't set or the jar doesn't exist
     */
    public static Path agentJar() {
        String jar = System.getProperty(AGENT_PROPERTY);
        if (jar == null || !Files.isRegularFile(Path.of(jar))) {
            throw new AssertionError("No agent jar at %s=%s: the build copies it before the tests (otel-agent)"
                    .formatted(AGENT_PROPERTY, jar));
        }
        return Path.of(jar);
    }

    /**
     * Returns a file next to the jar for the output of a run: {@code target/app-process/<name>.log}.
     *
     * @param name the run's name
     * @return the file
     */
    public static Path logFile(String name) {
        return packagedJar().resolveSibling("app-process").resolve(name + ".log");
    }

    /**
     * Prepares a run of a jar on this JVM's {@code java}: {@code java -jar <jar>}.
     *
     * @param jar the executable jar
     * @return the builder
     */
    public static Builder jvm(Path jar) {
        return new Builder(List.of("-jar", jar.toString()));
    }

    /**
     * Prepares a run of a jar in AOT mode: {@code java -Dspring.aot.enabled=true -jar <jar>}. The jar must have been
     * AOT-processed ({@code spring-boot:process-aot}); the build-time switches and the beans are then the build's.
     *
     * @param jar the AOT-processed executable jar
     * @return the builder
     */
    public static Builder aot(Path jar) {
        return jvm(jar).systemProperty("spring.aot.enabled", "true");
    }

    /**
     * Returns where the running service answers.
     *
     * @return the REST base URI and the gRPC host and port
     */
    public Target target() {
        return target;
    }

    /**
     * Returns what the process has written so far, standard output and standard error together.
     *
     * @return the output
     */
    public String output() {
        return read(launched.output());
    }

    /** Stops the process: {@link Process#destroy()}, then {@link Process#destroyForcibly()} after 30 seconds. */
    @Override
    public void close() {
        stop(launched);
    }

    private static void stop(Launched launched) {
        Process process = launched.process();
        process.destroy();
        try {
            if (!process.waitFor(STOP.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        launched.forget();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * How a run ended.
     *
     * @param code the exit code
     * @param output standard output and standard error together
     */
    public record Exit(int code, String output) {
    }

    /** A run being prepared: its JVM options, system properties, environment variables, arguments and output file. */
    public static final class Builder {

        private final List<String> launch;

        private final List<String> jvmOptions = new ArrayList<>();

        private final Map<String, String> systemProperties = new LinkedHashMap<>();

        private final Map<String, String> environment = new LinkedHashMap<>();

        private final List<String> arguments = new ArrayList<>();

        private @Nullable Path output;

        private Builder(List<String> launch) {
            this.launch = launch;
        }

        /**
         * Adds a JVM option before the system properties, such as {@code -javaagent:<jar>} or {@code -Xmx256m}.
         *
         * @param option the option, as {@code java} takes it
         * @return this builder
         */
        public Builder jvmOption(String option) {
            jvmOptions.add(option);
            return this;
        }

        /**
         * Sets a system property ({@code -D<name>=<value>}).
         *
         * @param name the property
         * @param value its value
         * @return this builder
         */
        public Builder systemProperty(String name, String value) {
            systemProperties.put(name, value);
            return this;
        }

        /**
         * Sets an environment variable of the process, such as {@code ENV}.
         *
         * @param name the variable
         * @param value its value
         * @return this builder
         */
        public Builder env(String name, String value) {
            environment.put(name, value);
            return this;
        }

        /**
         * Adds an argument after the ports, such as {@code --name=value}.
         *
         * @param argument the argument
         * @return this builder
         */
        public Builder arg(String argument) {
            arguments.add(argument);
            return this;
        }

        /**
         * Sets the file that receives the output; its directory is created. Without one, the output goes to
         * {@code target/app-process/run-<test JVM pid>-<n>.log}.
         *
         * @param file the file
         * @return this builder
         */
        public Builder output(Path file) {
            this.output = file;
            return this;
        }

        /**
         * Starts the process and waits until {@code /health} answers 200 UP, for at most 60 seconds.
         *
         * @return the running process, which the caller closes
         * @throws AssertionError when the process ends or isn't ready in time; the process is stopped, and the
         *     message has its output
         */
        public AppProcess start() {
            Launched launched = launch();
            URI health = URI.create("http://" + HOST + ":" + launched.restPort() + "/health");
            long deadline = System.nanoTime() + STARTUP.toNanos();
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
                while (true) {
                    if (!launched.process().isAlive()) {
                        launched.forget();
                        throw new AssertionError("The process ended with exit code %d before /health was UP:%n%s"
                                .formatted(launched.process().exitValue(), read(launched.output())));
                    }
                    if (isUp(http, health)) {
                        Target target = new Target(URI.create("http://" + HOST + ":" + launched.restPort()), HOST,
                                launched.grpcPort());
                        return new AppProcess(launched, target);
                    }
                    if (System.nanoTime() > deadline) {
                        stop(launched);
                        throw new AssertionError("/health was not UP within %d s:%n%s"
                                .formatted(STARTUP.toSeconds(), read(launched.output())));
                    }
                    Thread.sleep(POLL);
                }
            } catch (InterruptedException ex) {
                stop(launched);
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for /health", ex);
            }
        }

        /**
         * Runs the process to its end, for a run that ends by itself: a failed start, or one that exits on refresh.
         *
         * @param timeout how long the run may take
         * @return its exit code and output
         * @throws AssertionError when the run takes longer; the process is stopped, and the message has its output
         */
        public Exit run(Duration timeout) {
            Launched launched = launch();
            try {
                if (!launched.process().waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    stop(launched);
                    throw new AssertionError("The process didn't end within %d s:%n%s"
                            .formatted(timeout.toSeconds(), read(launched.output())));
                }
            } catch (InterruptedException ex) {
                stop(launched);
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the process to end", ex);
            }
            launched.forget();
            return new Exit(launched.process().exitValue(), read(launched.output()));
        }

        private Launched launch() {
            int[] ports = freePorts();
            List<String> command = new ArrayList<>();
            command.add(java());
            command.addAll(jvmOptions);
            systemProperties.forEach((name, value) -> command.add("-D" + name + "=" + value));
            command.addAll(launch);
            command.add("--server.port=" + ports[0]);
            command.add("--spring.grpc.server.port=" + ports[1]);
            command.addAll(arguments);
            try {
                // The test JVM's pid too: several failsafe forks number their runs from 1 each.
                Path file = output != null
                        ? output
                        : logFile("run-" + ProcessHandle.current().pid() + "-" + RUNS.incrementAndGet());
                Path directory = file.toAbsolutePath().getParent();
                if (directory != null) {
                    Files.createDirectories(directory);
                }
                ProcessBuilder builder = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(file.toFile());
                Map<String, String> env = builder.environment();
                env.keySet().removeIf(name -> !kept(name));
                env.putAll(environment);
                Process process = builder.start();
                Thread hook = new Thread(process::destroyForcibly, "app-process-stop-" + process.pid());
                Runtime.getRuntime().addShutdownHook(hook);
                return new Launched(process, hook, file, ports[0], ports[1]);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        /**
         * Whether the process keeps a variable of the test's environment: one of {@code KEPT_VARIABLES}, or a locale
         * category such as {@code LC_ALL} or {@code LC_CTYPE}, which a Unix runner may set instead of {@code LANG}.
         */
        static boolean kept(String name) {
            String upper = name.toUpperCase(Locale.ROOT);
            return KEPT_VARIABLES.contains(upper) || upper.startsWith("LC_");
        }

        /** The {@code java} of this JVM, which under {@code -Djvm=<java>} is that one. */
        private static String java() {
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path java = bin.resolve("java");
            return (Files.isRegularFile(java) ? java : bin.resolve("java.exe")).toString();
        }

        private static boolean isUp(HttpClient http, URI health) throws InterruptedException {
            try {
                HttpResponse<String> response = http
                        .send(HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(5)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
            } catch (IOException ex) {
                // Not listening yet.
                return false;
            }
        }

        /** Two different free ports: both sockets are open at once, so the system can't give the same port twice. */
        private static int[] freePorts() {
            try (ServerSocket rest = new ServerSocket(0); ServerSocket grpc = new ServerSocket(0)) {
                return new int[]{rest.getLocalPort(), grpc.getLocalPort()};
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * A launched process, the shutdown hook that kills it if the test JVM ends first, its output file and its ports.
     */
    private record Launched(Process process, Thread hook, Path output, int restPort, int grpcPort) {

        /** Removes the shutdown hook once the process has ended. */
        void forget() {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ex) {
                // The JVM is shutting down: the hook runs anyway, on a process that has already ended.
            }
        }
    }
}
