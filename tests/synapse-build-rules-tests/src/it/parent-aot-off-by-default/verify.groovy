import groovy.xml.XmlSlurper

// Without -Paot, a project that inherits synapse-parent runs no Spring AOT processing: no execution binds
// process-aot, the goal never ran, and nothing was generated, so the project needs no application-build.yaml. The
// image settings for build-image are still inherited. The jar runs in e3, with e3 as its only active profile. Started
// as the image's training run starts it, it fails: an image build needs the build environment's file.
def effective = new File(basedir, 'effective-pom.xml')
assert effective.isFile() : 'help:effective-pom did not write effective-pom.xml'
def project = new XmlSlurper().parse(effective)

def bootPlugin = project.build.plugins.plugin.find { it.artifactId.text() == 'spring-boot-maven-plugin' }
assert bootPlugin.size() == 1 : 'spring-boot-maven-plugin is not in the effective build plugins'
def goals = bootPlugin.executions.execution.goals.goal.collect { it.text() }
assert goals.contains('repackage') : "repackage is not bound: ${goals}"
assert !goals.contains('process-aot') : "process-aot is bound without the aot profile: ${goals}"
assert bootPlugin.executions.execution.findAll { it.id.text() == 'process-aot' }.size() == 0

def env = bootPlugin.configuration.image.env
assert env.BP_JVM_VERSION.text() == '25'
assert env.BP_JVM_AOTCACHE_ENABLED.text() == 'true'
assert env.TRAINING_RUN_JAVA_TOOL_OPTIONS.text() == '-XX:+UseG1GC -Dspring.profiles.active=build'
// The collector is pinned for the training run and every run of the image: the AOT cache needs the one it was trained with.
assert env.BPE_APPEND_JAVA_TOOL_OPTIONS.text() == '-XX:+UseG1GC'
assert env.BPE_DELIM_JAVA_TOOL_OPTIONS.size() == 1 : 'BPE_DELIM_JAVA_TOOL_OPTIONS is not set'

def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('BUILD SUCCESS')
assert !log.contains(':process-aot') : 'spring-boot:process-aot ran without the aot profile'
assert !new File(basedir, 'target/spring-aot').exists() : 'AOT processing wrote target/spring-aot'
assert !new File(basedir, 'src/main/resources/application-build.yaml').exists()

// The jar, run in e3 as a platform starts it.
def javaExe = new File(System.getProperty('java.home'), 'bin/java').path
def jar = new File(basedir, 'target').listFiles().find { it.name ==~ /parent-aot-off-by-default-.*\.jar/ }
assert jar != null : 'package did not write the jar'
def builder = new ProcessBuilder(javaExe, '-jar', jar.path)
        .directory(basedir)
        .redirectErrorStream(true)
builder.environment().remove('SPRING_PROFILES_ACTIVE')
builder.environment().put('ENV', 'e3')
def process = builder.start()
def runOutput = process.inputStream.getText('UTF-8')
def exitCode = process.waitFor()
new File(basedir, 'run.log').setText(runOutput, 'UTF-8')
assert exitCode == 0 : "the application failed in e3:\n${runOutput}"
assert !runOutput.contains('Starting AOT-processed App') : runOutput
assert runOutput.contains('PROFILES-REPORT active=[e3] greeting=from-e3') : runOutput

// The image's AOT-cache training run, started as Paketo's Spring Boot buildpack starts it: the parent's
// TRAINING_RUN_JAVA_TOOL_OPTIONS as JAVA_TOOL_OPTIONS, -Dspring.context.exit=onRefresh, ENV unset. The project has no
// application-build.yaml, so the run fails to start and says why (parent.adoc, Images). Paketo keeps the partial cache
// and the image build goes on; a Dockerfile's training RUN fails docker build.
def training = new ProcessBuilder(javaExe, '-Dspring.context.exit=onRefresh', '-jar', jar.path)
        .directory(basedir)
        .redirectErrorStream(true)
training.environment().remove('SPRING_PROFILES_ACTIVE')
training.environment().remove('ENV')
training.environment().put('JAVA_TOOL_OPTIONS', env.TRAINING_RUN_JAVA_TOOL_OPTIONS.text())
def trainingProcess = training.start()
def trainingOutput = trainingProcess.inputStream.getText('UTF-8')
def trainingExit = trainingProcess.waitFor()
new File(basedir, 'training-run.log').setText(trainingOutput, 'UTF-8')
assert trainingExit != 0 : "the training run started without application-build.yaml:\n${trainingOutput}"
assert trainingOutput.contains('Picked up JAVA_TOOL_OPTIONS: -XX:+UseG1GC -Dspring.profiles.active=build') : trainingOutput
assert trainingOutput.contains("The environment 'build' has no file in the service: none of application-build.yaml") : trainingOutput
