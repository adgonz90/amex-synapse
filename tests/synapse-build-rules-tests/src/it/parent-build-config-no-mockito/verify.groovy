import groovy.xml.XmlSlurper

// Without Mockito there is no agent and no failure: the test JVM started, and NoAgentTest saw no -javaagent argument.
def report = new File(basedir, 'target/surefire-reports').listFiles().find { it.name == 'TEST-example.NoAgentTest.xml' }
assert report != null : 'surefire did not run NoAgentTest'
def suite = new XmlSlurper().parse(report)
assert suite.@tests.text() == '1' && suite.@failures.text() == '0' && suite.@errors.text() == '0' && suite.@skipped.text() == '0'

// A consumer build prints no warning at all. It is aimed at warnings of the parent's making (such as "Ant property ...
// clashes with an existing Maven property", one line per Maven property, from an antrun execution that exports its
// properties), but it rejects every [WARNING] line: a new third-party warning, such as a plugin's deprecation, fails
// it too, so read the lines it prints before blaming the parent.
def warnings = new File(basedir, 'build.log').readLines('UTF-8').findAll { it.startsWith('[WARNING]') }
assert warnings.isEmpty() : "the build printed ${warnings.size()} warnings, the first: ${warnings.take(3)}"
