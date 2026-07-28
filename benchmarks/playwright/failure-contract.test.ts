import assert from 'node:assert/strict';
import test from 'node:test';
import { matchesExpectedFailure } from './failure-contract.ts';

const strictLocator = {
  category: 'strict-locator',
  harnessCode: 'strictness-violation',
  harnessEvidencePath: '/details/matchCount',
  harnessEvidenceValue: '[redacted] 2',
  playwrightErrorName: 'Error',
  playwrightMessage: 'strict mode violation',
};

test('rejects Playwright timeout when strict locator error was expected', () => {
  const timeout = Object.assign(new Error('locator.click: Timeout 500ms exceeded.'),
    { name: 'TimeoutError' });

  assert.equal(matchesExpectedFailure(timeout, strictLocator), false);
});

test('accepts exact Playwright error class and message category', () => {
  const strict = new Error('locator.click: strict mode violation: resolved to 2 elements');

  assert.equal(matchesExpectedFailure(strict, strictLocator), true);
});
