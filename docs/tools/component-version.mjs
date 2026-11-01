// Derives the Antora component version and its prerelease flag from the project version in the root pom.xml, so
// docs/antora.yml never has to be edited when a release is cut. Run by the Antora Collector extension (see
// docs/antora.yml) from the repository root. Output: docs/build/generated-docs/antora.yml
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// <major>.<minor>.<patch> with an optional -M<n> or -RC<n> qualifier, then an optional -SNAPSHOT. After a milestone,
// release-please sets the next development version to, for example, 2.0.1-M1-SNAPSHOT.
const VERSION = /^(\d+)\.(\d+)\.(\d+)((?:-(?:M|RC)\d+)?(?:-SNAPSHOT)?)$/;

export function componentVersion(pomVersion) {
  const match = VERSION.exec(pomVersion);
  if (!match) {
    throw new Error(`Unsupported project version '${pomVersion}': expected <major>.<minor>.<patch>[-M<n>|-RC<n>][-SNAPSHOT]`);
  }
  return { version: `${match[1]}.${match[2]}`, prerelease: match[4] !== '' };
}

// The project's own <version> is the first one after the </parent> element, or the first one if there is no parent.
export function projectVersion(pomXml) {
  const afterParent = pomXml.includes('</parent>') ? pomXml.slice(pomXml.indexOf('</parent>')) : pomXml;
  const match = /<version>([^<]+)<\/version>/.exec(afterParent);
  if (!match) {
    throw new Error('No project <version> found in pom.xml');
  }
  return match[1].trim();
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const root = process.cwd();
  const { version, prerelease } = componentVersion(projectVersion(readFileSync(join(root, 'pom.xml'), 'utf8')));
  const outputDir = join(root, 'docs', 'build', 'generated-docs');
  mkdirSync(outputDir, { recursive: true });
  writeFileSync(join(outputDir, 'antora.yml'), `version: '${version}'\nprerelease: ${prerelease}\n`);
  console.log(`Component version ${version} (prerelease: ${prerelease})`);
}
