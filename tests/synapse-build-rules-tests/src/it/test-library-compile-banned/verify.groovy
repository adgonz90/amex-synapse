// The build must fail because of the test-scope ban, not for some other reason.
def log = new File(basedir, 'build.log').text
assert log.contains('Test libraries must stay in test scope (only synapse-test and the synapse-test-<plugin> slices may export them): see the Quality gates section of specs/v2/BUILD.md.')
assert log.contains('org.junit.jupiter:junit-jupiter')
