// The build must fail because of the banned dependency, not for some other reason.
def log = new File(basedir, 'build.log').text
assert log.contains('Banned dependency: see the Quality gates section of specs/v2/BUILD.md.')
assert log.contains('junit:junit:jar:4.13.2')
