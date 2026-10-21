import assert from 'node:assert/strict';
import { pickRunnable } from './pilot-queue.mjs';

const jobs = Object.freeze([
  Object.freeze({ id: 'a', state: 'pending', priority: 2 }),
  Object.freeze({ id: 'b', state: 'running', priority: 99 }),
  Object.freeze({ id: 'c', state: 'pending', priority: 5 }),
  Object.freeze({ id: 'd', state: 'pending', priority: 5 }),
  Object.freeze({ id: 'e', state: 'done', priority: 9 }),
]);
assert.deepEqual(pickRunnable(jobs, 2), ['c', 'd']);
assert.deepEqual(pickRunnable(jobs, 20), ['c', 'd', 'a']);
assert.deepEqual(pickRunnable(jobs, 0), []);
assert.deepEqual(pickRunnable([], 2), []);
assert.deepEqual(pickRunnable([{ id: 'x', state: 'pending', priority: -4 }], 1), ['x']);
for (const limit of [-1, 0.5, Infinity, NaN, Number.MAX_SAFE_INTEGER + 1, '1', null, undefined]) {
  assert.throws(() => pickRunnable(jobs, limit), RangeError);
}
for (const invalid of [null, {}, 'jobs', [null], [{ id: '', state: 'pending', priority: 1 }],
  [{ id: 'x', state: 'invalid', priority: 1 }], [{ id: 'x', state: 'pending', priority: NaN }],
  [{ id: 'x', state: 'pending', priority: '2' }],
  [{ id: 'x', state: 'done', priority: 0 }, { id: 'x', state: 'running', priority: 1 }]]) {
  assert.throws(() => pickRunnable(invalid, 0), TypeError);
}
assert.deepEqual(jobs.map(x => x.id), ['a', 'b', 'c', 'd', 'e']);
console.log('PILOT_QUEUE_ACCEPTANCE_OK');
