import groovy.xml.XmlSlurper

// ArgLineTest and ArgLineIT assert their JVM's input arguments (the reports don't list them); both must have run and
// passed, so the parent's argLine composed with the project's in Surefire's and in Failsafe's JVM.
['surefire-reports/TEST-example.ArgLineTest.xml', 'failsafe-reports/TEST-example.ArgLineIT.xml'].each { path ->
    def report = new File(basedir, "target/${path}")
    assert report.isFile() : "no report ${path}: the test did not run"
    def suite = new XmlSlurper().parse(report)
    assert suite.@tests.text() == '2' && suite.@failures.text() == '0' && suite.@errors.text() == '0' && suite.@skipped.text() == '0' : "${path}: ${report.text}"
}
// The invoker fails a build whose post-build script returns anything but true or null.
true
