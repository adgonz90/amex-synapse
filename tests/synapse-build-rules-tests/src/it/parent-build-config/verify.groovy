import groovy.xml.XmlSlurper

// The parent's build configuration reaches a project that inherits it, built with the parent's aot profile (-Paot:
// Spring AOT processing is opt-in): the profile exists; the effective POM carries the properties, the
// process-aot execution and Paketo's variables; AOT processing ran in exactly one environment (build, as the default
// profile) although invoker.properties sets ENV (which the application's application.yaml maps to
// spring.profiles.active) and SPRING_PROFILES_ACTIVE to e3; the AOT-processed jar, run in e3, has e3 as its only
// active profile; and the Mockito agent, from a transitive mockito-core, was attached.
def buildLog = new File(basedir, 'build.log').getText('UTF-8')
assert !buildLog.contains('The requested profile "aot" could not be activated') : 'synapse-parent has no aot profile'
// A consumer build prints no warning at all. It is aimed at warnings of the parent's making (such as "Ant property ...
// clashes with an existing Maven property", one line per Maven property, from an antrun execution that exports its
// properties), but it rejects every [WARNING] line: a new third-party warning, such as a plugin's deprecation, fails
// it too, so read the lines it prints before blaming the parent.
def warnings = buildLog.readLines().findAll { it.startsWith('[WARNING]') }
assert warnings.isEmpty() : "the build printed ${warnings.size()} warnings, the first: ${warnings.take(3)}"

def effective = new File(basedir, 'effective-pom.xml')
assert effective.isFile() : 'help:effective-pom did not write effective-pom.xml'
def project = new XmlSlurper().parse(effective)

assert project.properties.'synapse.build.environment'.text() == 'build'
assert project.properties.'synapse.image.jvm-version'.text() == '25'

def bootPlugin = project.build.plugins.plugin.find { it.artifactId.text() == 'spring-boot-maven-plugin' }
assert bootPlugin.size() == 1 : 'spring-boot-maven-plugin is not in the effective build plugins'
def aot = bootPlugin.executions.execution.findAll { it.goals.goal.any { goal -> goal.text() == 'process-aot' } }
assert aot.size() == 1 : "process-aot must be bound by exactly one execution, found ${aot.size()}"
assert aot[0].id.text() == 'process-aot'
// No profile in the profiles parameter (an empty one overrides a plugin-level <profiles>): Spring's AOT code
// generation would activate them at run time too.
assert aot[0].configuration.profiles.profile.size() == 0
assert aot[0].configuration.arguments.argument.collect { it.text() } == ['--spring.profiles.active=', '--spring.profiles.default=build']

def env = bootPlugin.configuration.image.env
assert env.BP_JVM_VERSION.text() == '25'
assert env.BP_JVM_AOTCACHE_ENABLED.text() == 'true'
assert env.TRAINING_RUN_JAVA_TOOL_OPTIONS.text() == '-XX:+UseG1GC -Dspring.profiles.active=build'
// The collector is pinned for the training run and every run of the image: the AOT cache needs the one it was trained with.
assert env.BPE_APPEND_JAVA_TOOL_OPTIONS.text() == '-XX:+UseG1GC'
assert env.BPE_DELIM_JAVA_TOOL_OPTIONS.size() == 1 : 'BPE_DELIM_JAVA_TOOL_OPTIONS is not set'
assert env.BP_SPRING_AOT_ENABLED.size() == 0 : 'BP_SPRING_AOT_ENABLED must not be set (Paketo fails the build with it and TRAINING_RUN_JAVA_TOOL_OPTIONS)'
assert !effective.text.contains('BP_SPRING_AOT_ENABLED')

// AOT processing: the build environment (the default profile) was the only one, and its file was read.
def aotDir = new File(basedir, 'target/spring-aot/main')
assert aotDir.isDirectory() : 'spring-boot:process-aot did not write target/spring-aot/main'
def text = []
aotDir.eachFileRecurse(groovy.io.FileType.FILES) { if (it.name.endsWith('.java')) { text << it.getText('UTF-8') } }
assert text.any { it.contains('BuildMarker') } : 'AOT processing did not run in the build environment'
assert !text.any { it.contains('E3Marker') } : 'another environment was active in AOT processing'
assert text.any { it.contains('BuildFileMarker') } : 'AOT processing did not read application-build.yaml'

// A run of the AOT-processed jar in e3, as a platform starts it: e3 is its only active profile, and the build's file
// fills none of the keys that application-e3.yaml leaves unset.
def javaExe = new File(System.getProperty('java.home'), 'bin/java').path
def jar = new File(basedir, 'target').listFiles().find { it.name ==~ /parent-build-config-.*\.jar/ }
assert jar != null : 'package did not write the jar'
def builder = new ProcessBuilder(javaExe, '-Dspring.aot.enabled=true', '-jar', jar.path)
        .directory(basedir)
        .redirectErrorStream(true)
builder.environment().remove('SPRING_PROFILES_ACTIVE')
builder.environment().put('ENV', 'e3')
def process = builder.start()
def runOutput = process.inputStream.getText('UTF-8')
def exitCode = process.waitFor()
new File(basedir, 'aot-run.log').setText(runOutput, 'UTF-8')
assert exitCode == 0 : "the AOT-processed application failed in e3:\n${runOutput}"
assert runOutput.contains('Starting AOT-processed App') : runOutput
assert runOutput.contains('PROFILES-REPORT active=[e3] greeting=from-e3 build-only=null') : runOutput
// Spring's AOT code generation adds every profile that was active during processing to the run's active profiles
// (addActiveProfile in <App>__ApplicationContextInitializer and <App>__EnvironmentPostProcessor). The build
// environment reaches processing as the default profile, so none is.
assert !text.any { it.contains('addActiveProfile') } : 'the generated code activates a profile at run time'

// The Mockito agent: AgentTest asserts the test JVM's -javaagent argument and creates an inline mock; the report shows
// both ran and passed.
def reports = new File(basedir, 'target/surefire-reports')
def report = reports.listFiles().find { it.name == 'TEST-example.AgentTest.xml' }
assert report != null : 'surefire did not run AgentTest'
def suite = new XmlSlurper().parse(report)
assert suite.@tests.text() == '2' && suite.@failures.text() == '0' && suite.@errors.text() == '0' && suite.@skipped.text() == '0'

// The inline mock maker appends to the bootstrap class path, so with class data sharing on the JVM warns on its first
// mock. -Xshare:off in the parent's argLine keeps the test output clean: the warning is in neither the build log nor
// any file Surefire wrote (a JVM writing to its native stdout leaves it in a .dumpstream file).
def warning = 'Sharing is only supported for boot loader classes'
def outputs = [new File(basedir, 'build.log')] + reports.listFiles().findAll { it.isFile() }.toList()
for (output in outputs) {
    assert !output.getText('UTF-8').contains(warning) : "the test JVM printed the CDS warning, in ${output.name}"
}
