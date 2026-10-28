// Optional check (needs Node 18+; not part of test.cmd, which has no JS engine):
//   node src/test/js/model-settings-merge.mjs
// Exercises the three-way merge the settings page runs after a 409 revision conflict.
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../../main/java/dev/warden/dashboard/ModelSettingsUi.java', import.meta.url), 'utf8');
const block = source.slice(source.indexOf('/*merge:start*/'), source.indexOf('/*merge:end*/'));
assert.ok(block.length > 0 && !block.includes('\\'), 'merge block is present and free of escapes the Java text block would rewrite');
const { mergeFields, mergeRole } = new Function(block + ';return {mergeFields, mergeRole};')();

const slot = (profile, model, effort) => ({ profile, model, effort, model_vendor: undefined });
const role = (strategy, ...slots) => ({ strategy, slots });
let passed = 0;
const check = (name, fn) => { fn(); passed++; console.log('ok  ' + name); };

check('other tab strategy survives this tab effort change', () => {
  const base = role('first', slot('a', 'm', 'medium'));
  const mine = role('first', slot('a', 'm', 'high'));
  const theirs = role('rotate', slot('a', 'm', 'medium'));
  const merged = mergeRole(base, mine, theirs);
  assert.equal(merged.strategy, 'rotate');
  assert.equal(merged.slots[0].effort, 'high');
  assert.deepEqual(merged.conflicts, []);
});
check('same field changed in both tabs keeps theirs and reports it', () => {
  const merged = mergeRole(role('first', slot('a', 'm', 'medium')), role('rotate', slot('a', 'm', 'medium')), role('first', slot('a', 'm', 'medium')));
  assert.equal(merged.strategy, 'rotate', 'this tab change applies when the other tab left it alone');
  assert.deepEqual(merged.conflicts, []);
  const both = mergeRole(role('first', slot('a', 'm', 'low')), role('first', slot('a', 'm', 'high')), role('first', slot('a', 'm', 'medium')));
  assert.equal(both.slots[0].effort, 'medium');
  assert.deepEqual(both.conflicts, [{ field: 'a.effort', mine: 'high', theirs: 'medium' }]);
});
check('local reorder applies while other tab model change is kept', () => {
  const base = role('first', slot('a', 'm', 'low'), slot('b', 'n', 'low'));
  const mine = role('first', slot('b', 'n', 'low'), slot('a', 'm', 'low'));
  const theirs = role('first', slot('a', 'm2', 'low'), slot('b', 'n', 'low'));
  const merged = mergeRole(base, mine, theirs);
  assert.deepEqual(merged.slots.map(s => s.profile), ['b', 'a']);
  assert.equal(merged.slots[1].model, 'm2');
});
check('unchanged draft adopts every server value', () => {
  const base = role('first', slot('a', 'm', 'low'));
  const merged = mergeRole(base, base, role('rotate', slot('a', 'x', 'max')));
  assert.deepEqual(merged, { strategy: 'rotate', slots: [slot('a', 'x', 'max')], conflicts: [] });
});
check('settings fields merge the same way', () => {
  const conflicts = [];
  const merged = mergeFields({ enabled: false, min_confidence: 0.6 }, { enabled: true, min_confidence: 0.6 }, { enabled: false, min_confidence: 0.8 }, ['enabled', 'min_confidence'], '', conflicts);
  assert.deepEqual(merged, { enabled: true, min_confidence: 0.8 });
  assert.deepEqual(conflicts, []);
});
console.log(`merge checks: ${passed} passed, 0 failed`);
