import groovy.xml.XmlSlurper

// A project inheriting synapse-parent gets the guava CVE pin in its dependencyManagement. The pin is an explicit entry,
// so it beats the older guava that the project's own BOM import manages (Maven processes that import before the
// inherited synapse-bom import, so the import alone would not be enough) and the older guava a declared dependency
// brings.
def effective = new File(basedir, 'effective-pom.xml')
assert effective.isFile() : 'help:effective-pom did not write effective-pom.xml'
def project = new XmlSlurper().parse(effective)
def managed = project.dependencyManagement.dependencies.dependency

// The project's guava-bom import took effect: it manages guava-testlib, which nothing else manages.
def testlib = managed.findAll { it.groupId.text() == 'com.google.guava' && it.artifactId.text() == 'guava-testlib' }
assert testlib.size() == 1 && testlib[0].version.text() == '33.6.0-jre' : 'the project\'s guava-bom 33.6.0-jre import is not in the effective POM'

def guava = managed.findAll { it.groupId.text() == 'com.google.guava' && it.artifactId.text() == 'guava' }
assert guava.size() == 1 : "the effective POM must manage com.google.guava:guava once, found ${guava.size()}"
assert guava[0].version.text() == '33.7.2-jre' : "the effective POM manages guava ${guava[0].version.text()}, not 33.7.2-jre"

def tree = new File(basedir, 'tree.txt').getText('UTF-8')
assert tree.contains('io.grpc:grpc-api:jar:1.84.1') : "grpc-api is not in the dependency tree:\n${tree}"
assert tree.contains('com.google.guava:guava:jar:33.7.2-jre') : "the dependency tree does not show guava 33.7.2-jre:\n${tree}"
assert !(tree =~ /com\.google\.guava:guava:jar:(?!33\.7\.2-jre)/) : "another guava version is in the dependency tree:\n${tree}"

// The pin is not vacuous: grpc-api asks for an older guava, which the pin replaces ("version managed from <older>").
def verbose = new File(basedir, 'tree-verbose.txt').getText('UTF-8')
def managedFrom = verbose =~ /com\.google\.guava:guava:jar:33\.7\.2-jre:[a-z]+ \(version managed from ([^)\s;]+)/
assert managedFrom.find() : "the verbose tree does not show guava's version before the pin managed it:\n${verbose}"
def numbers = { String version -> (version =~ /^(\d+)\.(\d+)(?:\.(\d+))?/).with { it.find() ? [it.group(1), it.group(2), it.group(3) ?: '0'].collect { n -> n as int } : null } }
def declared = numbers(managedFrom.group(1))
def pinned = numbers('33.7.2')
assert declared != null && [declared, pinned].transpose().find { a, b -> a != b }?.with { a, b -> a < b } :
        "grpc-api's guava ${managedFrom.group(1)} is not older than the pin 33.7.2-jre, so this test no longer proves the pin"
