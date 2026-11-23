// The archetypes generate projects in the shape of two samples: the quickstart is samples/hello-rest, and the service
// archetype shares its infrastructure files with samples/reference-bookstore (DESIGN.md, D37). A fix made in a sample
// must reach its archetype, so this check compares their code: comments, blank lines and import order are ignored,
// and each sample's names are replaced by the archetype's (its package by ${package}, and so on). It fails naming
// each file that differs, and each quickstart file that one side has and the other hasn't. The service archetype's
// domain files (Item, against the bookstore's Book) are not compared. Each archetype's Maven Wrapper must equal the
// repository's, byte for byte. basedir is tests/synapse-build-rules-tests/target/it/archetype-drift, so the repository
// root is five levels up.
def root = new File(basedir, '../../../../..').canonicalFile

// The code of a file: no comment lines, no blank lines, the imports sorted at the end.
def code = { String text ->
    def lines = []
    def imports = []
    def inComment = false
    text.readLines().each { line ->
        def trimmed = line.trim()
        if (inComment) {
            inComment = !trimmed.contains('*/')
            return
        }
        if (trimmed.startsWith('/*')) {
            inComment = !trimmed.contains('*/')
            return
        }
        if (trimmed.isEmpty() || trimmed.startsWith('//') || trimmed.startsWith('#')) {
            return
        }
        if (trimmed.startsWith('import ')) {
            imports << trimmed
            return
        }
        lines << line.replaceAll(/\s+$/, '')
    }
    lines + imports.sort()
}

def differs = []
def compare = { String archetypeRoot, String sampleRoot, String archetypePath, String samplePath, Map substitutions ->
    def archetypeFile = new File(root, "${archetypeRoot}/${archetypePath}")
    def sampleFile = new File(root, "${sampleRoot}/${samplePath}")
    assert archetypeFile.isFile() : "${archetypeFile} is missing"
    assert sampleFile.isFile() : "${sampleFile} is missing"
    def sample = sampleFile.getText('UTF-8')
    substitutions.each { from, to -> sample = sample.replace(from, to) }
    def expected = code(sample)
    def actual = code(archetypeFile.getText('UTF-8'))
    if (expected != actual) {
        def at = (0..<Math.max(expected.size(), actual.size())).find { expected[it] != actual[it] }
        differs << "${archetypeRoot}/${archetypePath} and ${sampleRoot}/${samplePath}, first at code line ${at + 1}:\n" +
                "  sample:    ${expected[at] ?: '<end>'}\n  archetype: ${actual[at] ?: '<end>'}"
    }
}

// The quickstart is hello-rest: every file under src/ on either side, the Java sources one package level up.
def quickstart = 'archetypes/synapse-archetype-quickstart/src/main/resources/archetype-resources'
def hello = 'samples/hello-rest'
def helloPackage = 'io/americanexpress/synapse/samples/hello/'
def helloNames = ['io.americanexpress.synapse.samples.hello': '${package}', 'hello-rest': '${artifactId}']
def files = { String dir ->
    def base = new File(root, dir)
    def found = []
    new File(base, 'src').eachFileRecurse(groovy.io.FileType.FILES) { file ->
        found << base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)
    }
    found as Set
}
def toSample = { String path -> path.replaceFirst(/^src\/(main|test)\/java\//, "src/\$1/java/${helloPackage}") }
def archetypeFiles = files(quickstart)
def sampleFiles = files(hello).findAll { !it.contains('/target/') } as Set
assert archetypeFiles : "${quickstart} has no file under src/; the check is reading the wrong directory"
def onlyInSample = sampleFiles - archetypeFiles.collect(toSample)
def onlyInArchetype = archetypeFiles.findAll { !sampleFiles.contains(toSample(it)) }
onlyInSample.each { differs << "${hello}/${it} has no counterpart in ${quickstart}".toString() }
onlyInArchetype.each { differs << "${quickstart}/${it} has no counterpart in ${hello}".toString() }
archetypeFiles.findAll { sampleFiles.contains(toSample(it)) }.each { compare(quickstart, hello, it, toSample(it), helloNames) }

// The service archetype shares the bookstore's infrastructure: its image, its agent recipe, its environments and the
// scenario suite's process launcher.
def service = 'archetypes/synapse-archetype-service/src/main/resources/archetype-resources'
def bookstore = 'samples/reference-bookstore'
def bookstoreNames = ['io.americanexpress.synapse.samples.bookstore': '${package}', '"bookstore.': '"service.',
        'name: bookstore': 'name: ${serviceName}']
def bookstorePackage = 'io/americanexpress/synapse/samples/bookstore/'
[
    'service-__serviceName__/.dockerignore',
    'service-__serviceName__/Dockerfile',
    'service-__serviceName__/src/main/java/config/OpenTelemetryAgentAotConfiguration.java',
    'service-__serviceName__/src/main/java/config/OpenTelemetryAgentConfiguration.java',
    'service-__serviceName__/src/main/java/config/package-info.java',
    'service-__serviceName__/src/main/resources/application.yaml',
    'service-__serviceName__/src/main/resources/application-build.yaml',
    'service-__serviceName__/src/main/resources/application-dev.yaml',
    'service-__serviceName__/src/main/resources/application-local.yaml',
    'service-__serviceName__/src/test/java/config/OpenTelemetryAgentConfigurationTest.java',
    'service-__serviceName__/src/test/java/scenario/AppProcess.java',
    'service-__serviceName__/src/test/java/scenario/Protocol.java',
    'service-__serviceName__/src/test/java/scenario/Target.java',
    'service-__serviceName__/src/test/resources/application-test.yaml',
    'data-__serviceName__/src/test/resources/application-test.yaml',
].each { path ->
    def samplePath = path
            .replace('service-__serviceName__/', 'service-bookstore/')
            .replace('data-__serviceName__/', 'data-books/')
            .replaceFirst(/\/src\/(main|test)\/java\//, "/src/\$1/java/${bookstorePackage}")
    compare(service, bookstore, path, samplePath, bookstoreNames)
}

// Both archetypes ship the repository's Maven Wrapper, copied byte for byte (their fileSets are unfiltered), so there
// is one wrapper version to bump: after a wrapper upgrade, copy the three files into each archetype again.
[quickstart, service].each { archetypeRoot ->
    ['mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties'].each { path ->
        def copy = new File(root, "${archetypeRoot}/${path}")
        if (!copy.isFile()) {
            differs << "${archetypeRoot}/${path} is missing: copy the repository's ${path} there".toString()
        } else if (!Arrays.equals(copy.bytes, new File(root, path).bytes)) {
            differs << "${archetypeRoot}/${path} differs from the repository's ${path}, byte for byte".toString()
        }
    }
}

// The service archetype ships the repository's proto contract check, copied byte for byte (unfiltered): the workflow
// and buf.lock. Its buf.yaml differs (it names the generated contract module), so it is not compared. Dependabot's
// version updates bump both copies of buf.yml in one pull request; its security updates and buf dep update don't.
['.github/workflows/buf.yml', 'buf.lock'].each { path ->
    def copy = new File(root, "${service}/${path}")
    if (!copy.isFile()) {
        differs << "${service}/${path} is missing: copy the repository's ${path} there".toString()
    } else if (!Arrays.equals(copy.bytes, new File(root, path).bytes)) {
        differs << ("${service}/${path} differs from the repository's ${path}, byte for byte: copy the updated one " +
                "over the other").toString()
    }
}

assert differs.isEmpty() : 'an archetype has drifted from its sample; make the same change on both sides:\n' +
        differs.join('\n')
true
