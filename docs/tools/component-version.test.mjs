import assert from 'node:assert/strict';
import { test } from 'node:test';
import { componentVersion } from './component-version.mjs';

test('snapshots are prereleases', () => {
  assert.deepEqual(componentVersion('2.0.0-SNAPSHOT'), { version: '2.0', prerelease: true });
});

test('milestones are prereleases', () => {
  assert.deepEqual(componentVersion('2.0.0-M1'), { version: '2.0', prerelease: true });
});

test('release candidates are prereleases', () => {
  assert.deepEqual(componentVersion('2.0.0-RC2'), { version: '2.0', prerelease: true });
});

test('snapshots after a milestone or release candidate are prereleases', () => {
  assert.deepEqual(componentVersion('2.0.1-M1-SNAPSHOT'), { version: '2.0', prerelease: true });
  assert.deepEqual(componentVersion('2.0.0-RC1-SNAPSHOT'), { version: '2.0', prerelease: true });
});

test('qualifiers are still strict', () => {
  for (const version of ['2.0.1-SNAPSHOT-M1', '2.0.1-M1-RC1', '2.0.1-M-SNAPSHOT', '2.0.1-beta1-SNAPSHOT']) {
    assert.throws(() => componentVersion(version), /Unsupported project version/, version);
  }
});

test('a GA release is not a prerelease', () => {
  assert.deepEqual(componentVersion('2.0.0'), { version: '2.0', prerelease: false });
  assert.deepEqual(componentVersion('2.0.1'), { version: '2.0', prerelease: false });
});

test('the version is major.minor', () => {
  assert.deepEqual(componentVersion('2.1.3'), { version: '2.1', prerelease: false });
});

test('an unrecognised version throws', () => {
  assert.throws(() => componentVersion('garbage'), /garbage/);
});
