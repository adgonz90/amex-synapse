// Builds 1 and 4 must fail because of the missing coverage data, not for some other reason (builds 2 and 3 must
// succeed; the invoker checks that through invoker.buildResult.2 and .3). All four builds write to the same build.log.
def log = new File(basedir, 'build.log').text
def message = 'No test coverage data for a module with main sources: add tests (see the Quality gates section of specs/v2/BUILD.md).'
def count = log.count(message)
assert count == 2 : "expected the coverage gate message from builds 1 and 4, found it ${count} times"
