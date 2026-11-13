// -Pimage on a service without application-build.yaml fails in validate, at the image profile's enforcer check, with
// the message that names the file to add. Nothing after validate ran: no jar, no image.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('BUILD FAILURE') : log
assert log.contains('(image-build-environment)') : 'the image-build-environment check did not run'
assert log.contains('-Pimage builds an image whose AOT-cache training run starts this service in the build environment (synapse.build.environment=build), but the service has no file for it. Add src/main/resources/application-build.yaml') : log
assert !log.contains('build-image-no-fork') : 'the image build ran despite the failed check'
assert !log.contains('--- compiler:') : 'the build went past validate'
def target = new File(basedir, 'target')
assert !target.exists() || target.listFiles().every { !it.name.endsWith('.jar') } : 'a jar was built'
