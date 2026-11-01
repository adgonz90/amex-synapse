// Build 1 must fail because of the missing coverage data, not for some other reason (build 2 must succeed; the
// invoker checks that through invoker.buildResult.2).
def log = new File(basedir, 'build.log').text
assert log.contains('No test coverage data for a module with main sources: add tests (see the Quality gates section of specs/v2/BUILD.md).')
