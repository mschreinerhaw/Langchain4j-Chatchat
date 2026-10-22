import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyClinePackages, CLINE_PACKAGES, CLINE_VERSION } from '../src/sdk-packages.js';

test('all five real Cline packages import together and public SDK re-exports the installed core', async () => {
  const versions = await verifyClinePackages();
  assert.deepEqual(Object.keys(versions), CLINE_PACKAGES);
  assert.ok(Object.values(versions).every(version => version === CLINE_VERSION));
  const [sdk, core, agents] = await Promise.all([import('@cline/sdk'), import('@cline/core'), import('@cline/agents')]);
  assert.deepEqual(Object.keys(sdk), Object.keys(core));
  assert.equal(sdk.Agent, core.Agent);
  assert.equal(sdk.Agent, agents.Agent);
});
