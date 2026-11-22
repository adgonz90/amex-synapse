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

// Runs after the generated project (com.example:s, serviceName s) built with verify. maven-archetype-plugin passes the
// generated project as context.projectDir; basedir is this script's directory. This file is filtered with @ delimiters:
// the token below is the reactor version. The names are one letter long to keep Windows paths short (LongPathsEnabled
// is off on some machines).

def reactorVersion = '@project.version@'
def project = context.projectDir as File

// The generated files (expected-files.txt). The build's own output (each target/) and the IT's build.log are left out.
def expected = new File(basedir as File, 'expected-files.txt').readLines()*.trim().findAll { it }.sort()
def actual = []
project.eachFileRecurse(groovy.io.FileType.FILES) { file ->
    def path = project.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    if (!(path =~ /^([^\/]+\/)?target\//) && path != 'build.log') {
        actual << path
    }
}
actual.sort()
assert actual == expected: "Generated files differ from expected-files.txt.\nMissing: ${expected - actual}\nExtra: ${actual - expected}"

// The three modules, named after serviceName, are the aggregator's modules, in this order.
def modules = ['contract-s', 'data-s', 'service-s']
modules.each { assert new File(project, it).isDirectory(): "no module directory ${it}" }
def pom = new groovy.xml.XmlSlurper().parse(new File(project, 'pom.xml'))
assert pom.modules.module*.text() == modules: "aggregator modules: ${pom.modules.module*.text()}"

// The aggregator's parent is synapse-parent at the version of the reactor that built this archetype, from a
// repository; each module's parent is the aggregator, and its artifactId is spelled out.
assert pom.parent.groupId.text() == 'io.americanexpress.synapse'
assert pom.parent.artifactId.text() == 'synapse-parent'
assert pom.parent.version.text() == reactorVersion: "parent version ${pom.parent.version.text()}, reactor ${reactorVersion}"
// An empty <relativePath/>: the parent comes from a repository, never from a directory above the generated project.
assert pom.parent.relativePath.size() == 1 && pom.parent.relativePath.text() == '': "parent relativePath: '${pom.parent.relativePath.text()}'"
assert pom.groupId.text() == 'com.example'
assert pom.artifactId.text() == 's'
assert pom.version.text() == '0.1.0-SNAPSHOT'
modules.each { module ->
    def modulePom = new groovy.xml.XmlSlurper().parse(new File(project, "${module}/pom.xml"))
    assert modulePom.parent.groupId.text() == 'com.example': "${module} parent groupId"
    assert modulePom.parent.artifactId.text() == 's': "${module} parent artifactId"
    assert modulePom.parent.version.text() == '0.1.0-SNAPSHOT': "${module} parent version"
    assert modulePom.artifactId.text() == module: "${module} artifactId ${modulePom.artifactId.text()}"
}

// The store is named after the service, with its migrations in db/migration/<name>.
def store = new File(project, 'data-s/src/main/java/com/example/s/data/s/ServiceStore.java').text
assert store.contains('@SynapseDataStore("s")'): "ServiceStore.java doesn't declare the store s:\n${store}"
assert new File(project, 'data-s/src/main/resources/db/migration/s/V1__s.sql').isFile(): 'no db/migration/s/V1__s.sql'

// Every package of every module has its package-info.java with @NullMarked: each main package, and each test package
// that has no main package of the same name (a test package with a main package's name has none).
modules.each { module ->
    def mainPackages = packages(new File(project, "${module}/src/main/java"))
    def testPackages = packages(new File(project, "${module}/src/test/java"))
    assert !mainPackages.isEmpty(): "${module} has no main package"
    mainPackages.each { dir -> assertNullMarked(dir, "${module} main") }
    testPackages.each { dir ->
        def name = new File(project, "${module}/src/test/java").toPath().relativize(dir.toPath()).toString()
        def info = new File(dir, 'package-info.java')
        if (new File(project, "${module}/src/main/java/${name}").isDirectory()) {
            assert !info.exists(): "${module} test package ${name} repeats the main package's package-info.java"
        } else {
            assertNullMarked(dir, "${module} test")
        }
    }
}

// The environment comes from ENV (Velocity leaves the undefined ${ENV} as it is), and the name is serviceName.
def applicationYaml = new File(project, 'service-s/src/main/resources/application.yaml').text
assert applicationYaml =~ /(?m)^    active: \$\{ENV\}$/: "application.yaml doesn't map spring.profiles.active to \${ENV}:\n${applicationYaml}"
assert applicationYaml =~ /(?m)^    name: s$/: "application.yaml doesn't name the application s:\n${applicationYaml}"

// The build environment's files, each saying why it is there (maintainer, 2026-11-08).
['service-s/src/main/resources/application-build.yaml', 'data-s/src/main/resources/META-INF/synapse/config-build.yaml'].each {
    def text = new File(project, it).text
    assert text.contains("for the image's training run and for -Paot"): "${it} doesn't say why it exists:\n${text}"
}

// The Dockerfile keeps its build argument (an undefined Velocity reference) and its training run.
def dockerfile = new File(project, 'service-s/Dockerfile').text
assert dockerfile.contains('COPY ${JAR_FILE} application.jar'): 'Dockerfile lost ${JAR_FILE}'
assert dockerfile.contains('-XX:AOTCacheOutput=app.aot') && dockerfile.contains('&& test -s app.aot'): 'Dockerfile lost its training run'

// Velocity drops lines that start with ##, so the template escapes the README's headings; they must survive.
def readme = new File(project, 'README.md').text
def headings = readme.readLines().findAll { it.startsWith('## ') }
assert headings == [
    '## What it shows',
    '## Layout',
    '## Environments',
    '## Run it',
    '## Try it',
    '## Tests',
    '## Spring AOT and container images',
    '## OpenTelemetry Java agent',
    '## Make it yours',
]: "README headings: ${headings}"
assert !readme.contains('#[[') && !readme.contains(']]#'): 'README still holds Velocity escapes'
assert readme.contains('-Paot') && readme.contains('-Pimage'): 'README says nothing of -Paot and -Pimage'

// Git keeps the project on LF, whatever line separator the generator used, except the wrapper's Windows script.
assert new File(project, '.gitattributes').readLines()*.trim() == ['* text=auto eol=lf', '*.cmd text eol=crlf', 'mvnw text eol=lf']

// Velocity left nothing behind: no template token survives, and every ${...} is one meant for the shell, Docker or
// Maven, never an unresolved archetype property such as ${package} or ${serviceName}. The wrapper's files are copied
// as is, never through Velocity (their shell variables are checked below, against the repository's).
def wrapperFiles = ['mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties']
def allowed = ~/ENV|JAR_FILE|project\.[\w.]+|otel-agent\.jar|opentelemetry-javaagent\.\w+/
project.eachFileRecurse(groovy.io.FileType.FILES) { file ->
    def path = project.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    if (path =~ /^([^\/]+\/)?target\// || path == 'build.log' || wrapperFiles.contains(path)) {
        return
    }
    def text = file.text
    // The last token is Maven's filter token of the template poms, spelled with char 64 so this filtered file keeps it.
    def at = (char) 64
    ['__serviceName__', '#[[', ']]#', "${at}synapse.version${at}"].each { token ->
        assert !text.contains(token): "${path} still holds ${token}"
    }
    (text =~ /\$\{([^}]*)\}/).each { match ->
        assert match[1] ==~ allowed: "${path} still holds \${${match[1]}}"
    }
}

// The generated build ran the unit and integration tests, none skipped: the data module's, and the service's scenarios
// over both protocols, its operational checks, its training run and the OpenTelemetry Java agent. The AOT scenarios run
// only with -Paot.
def log = new File(project, 'build.log').text
['ItemRepositoryIT', 'ServiceJvmIT', 'ServiceOperationalIT', 'ServiceExitOnRefreshIT', 'ServiceAgentIT',
        'ItemOperationsTest', 'ItemRestTest', 'ItemGrpcTest', 'OpenTelemetryAgentConfigurationTest',
        'ArchitectureTest'].each { test ->
    assert log =~ /Tests run: [1-9]\d*, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: .* -+ in com\.example\.s\.(\w+\.)*${test}/:
            "build.log has no passing run of ${test}"
}
assert !log.contains('in com.example.s.ServiceAotIT'): 'ServiceAotIT ran without -Paot'

// The Maven Wrapper is the repository's, copied as is (an unfiltered fileSet), byte for byte: mvnw on LF and mvnw.cmd
// on CRLF. basedir is archetypes/<archetype>/target/it/projects/<name>, so the repository root is six levels up. The
// post-generation script sets mvnw executable; only a POSIX file system (Linux CI) can show it.
def root = new File(basedir as File, '../../../../../..').canonicalFile
wrapperFiles.each {
    assert new File(project, it).isFile(): "no ${it}"
    assert new File(project, it).bytes == new File(root, it).bytes: "${it} differs from the repository's (${root})"
}
def mvnw = new File(project, 'mvnw')
assert !mvnw.getText('UTF-8').contains('\r'): 'mvnw has a carriage return'
def mvnwCmd = new File(project, 'mvnw.cmd').getText('UTF-8')
assert mvnwCmd.contains('\r\n') && !(mvnwCmd =~ /(?<!\r)\n/): 'mvnw.cmd is not on CRLF'
if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains('posix')) {
    assert java.nio.file.Files.isExecutable(mvnw.toPath()): 'mvnw is not executable'
}

// The wrapper runs: it downloads Maven 3.9.16 into ~/.m2/wrapper (once) and prints its version. On Windows the script
// is named with its directory, .\mvnw.cmd: with NoDefaultCurrentDirectoryInExePath set, cmd doesn't look for a bare
// mvnw.cmd in the current directory.
def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).startsWith('windows')
def versionLog = new File(basedir as File, 'mvnw-version.log')
// Its stdin is the null device, so nothing it starts can wait for input. A timeout destroys the whole process tree:
// on Windows, destroying cmd alone would leave its powershell or java running, holding the log open.
def wrapper = new ProcessBuilder(windows ? ['cmd', '/c', '.\\mvnw.cmd', '--version'] : ['./mvnw', '--version'])
        .directory(project).redirectErrorStream(true).redirectOutput(versionLog)
        .redirectInput(ProcessBuilder.Redirect.from(new File(windows ? 'NUL' : '/dev/null'))).start()
if (!wrapper.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
    wrapper.toHandle().descendants().forEach { it.destroyForcibly() }
    wrapper.destroyForcibly()
    assert false: "mvnw --version didn't finish in 10 minutes:\n${versionLog.text}"
}
def versionOutput = versionLog.text
assert wrapper.exitValue() == 0: "mvnw --version exited with ${wrapper.exitValue()}:\n${versionOutput}"
assert versionOutput.contains('Apache Maven 3.9.16'): "mvnw --version didn't run Maven 3.9.16:\n${versionOutput}"

true

/** The directories under a source root that hold a .java file. */
static List<File> packages(File root) {
    def dirs = [] as Set
    if (root.isDirectory()) {
        root.eachFileRecurse(groovy.io.FileType.FILES) { file ->
            if (file.name.endsWith('.java')) {
                dirs << file.parentFile
            }
        }
    }
    dirs as List
}

static void assertNullMarked(File dir, String where) {
    def info = new File(dir, 'package-info.java')
    assert info.isFile(): "${where}: no package-info.java in ${dir}"
    assert info.text =~ /(?m)^@NullMarked$/: "${where}: ${info} isn't @NullMarked"
}
