"""Paid live pilot in an explicitly prepared, disposable fixture worktree."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--project', type=Path, required=True)
p.add_argument('--classes', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--fixture-accept', action='store_true', required=True,
               help='Record automated fixture-operator acceptance after real checks; not user acceptance.')
p.add_argument('--adopt-first', help='Wait for an already launched first run instead of duplicating it.')
p.add_argument('--prefix', default='live', help='Fresh run namespace; existing IDs are never overwritten.')
a = p.parse_args()
root, classes, output = a.project.resolve(), a.classes.resolve(), a.output.resolve()
output.mkdir(parents=True, exist_ok=True)
fixture = Path(__file__).resolve().parent
for src, dst in [('pilot-queue.test.mjs', 'pilot-queue.test.mjs'), ('task.yaml', '.warden/tasks/pilot-queue.yaml')]:
    if (root / dst).read_bytes().replace(b'\r\n', b'\n') != (fixture / src).read_bytes().replace(b'\r\n', b'\n'):
        raise SystemExit('Refusing a project without the exact pilot fixture: ' + dst)
base = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
original = subprocess.check_output(['git', 'show', 'HEAD:pilot-queue.mjs'], cwd=root)
if original.replace(b'\r\n', b'\n') != (fixture / 'pilot-queue.mjs').read_bytes().replace(b'\r\n', b'\n'):
    raise SystemExit('HEAD must contain the intentionally incomplete pilot module.')

def warden(args, log):
    with log.open('w', encoding='utf-8') as f:
        done = subprocess.run(['java', '-cp', str(classes), 'dev.warden.Main', *args], cwd=root,
                              stdout=f, stderr=subprocess.STDOUT)
    return done.returncode

manifest = {'experiments': [{'id': 'secondary-review-live', 'variants': ['agy-secondary', 'deepseek-secondary']}],
            'runs': []}
receipts = {'base_commit': base, 'project': str(root), 'acceptance_actor': 'warden-live-fixture-operator',
            'acceptance_is_user_judgement': False, 'runs': []}
sequence = [('agy', 1), ('deepseek', 1), ('deepseek', 2), ('agy', 2), ('agy', 3), ('deepseek', 3)]
for index, (vendor, repeat) in enumerate(sequence):
    run_id = a.adopt_first if index == 0 and a.adopt_first else f'{a.prefix}-{vendor}-r{repeat}-measured'
    run_dir = root / '.warden/runs' / run_id
    summary = run_dir / 'task-run.json'
    if not (index == 0 and a.adopt_first):
        if run_dir.exists():
            raise SystemExit('Refusing to overwrite or rerun an existing run: ' + run_id)
        if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip() != base:
            raise SystemExit('Fixture HEAD changed; comparisons would use a different base.')
        # Only this pilot's owned source file is restored. No reset/clean of user files.
        (root / 'pilot-queue.mjs').write_bytes(original)
        print('START', run_id, flush=True)
        code = warden(['run', '.warden/tasks/pilot-queue.yaml', '--run-id', run_id,
                       '--use', 'review-second=' + vendor + '-review', '--no-wait-for-gate'],
                      output / (run_id + '.log'))
    else:
        print('ADOPT', run_id, flush=True)
        deadline = time.monotonic() + 20 * 60
        while not summary.exists():
            if time.monotonic() > deadline:
                raise SystemExit('Existing run has not completed. Inspect it; do not launch another writer.')
            time.sleep(2)
        code = None
    data = json.loads(summary.read_text(encoding='utf-8'))
    if data['diff_base_commit'] != base:
        raise SystemExit('Unexpected comparison base in ' + run_id)
    decision = json.loads((run_dir / 'decision.json').read_text(encoding='utf-8'))
    if any(s.get('code') == 'role_violated_read_only' for s in data.get('steps', [])):
        raise SystemExit('Read-only fingerprint changed; inspect before any reset or further dispatch: ' + run_id)
    check = subprocess.run(['node', 'pilot-queue.test.mjs'], cwd=root, capture_output=True, text=True)
    passed = bool(data.get('candidate_review_passed') and not data.get('workflow_incomplete')
                  and check.returncode == 0 and 'PILOT_QUEUE_ACCEPTANCE_OK' in check.stdout)
    choice = 'accept' if passed else 'abort'
    if decision.get('state') == 'pending':
        if choice not in decision.get('options', []):
            raise SystemExit('Run does not offer the expected fixture decision: ' + run_id)
        if warden(['approve', run_id, '--decision', choice, '--actor', 'warden-live-fixture-operator',
                   '--note', 'Automated disposable-fixture outcome after Node acceptance and vendor reviews: ' + choice + '. '
                   'This is a test operator, not an end-user quality judgement. Nothing is landed.', '--no-start'],
                  output / (run_id + '-accept.json')) != 0:
            raise SystemExit('Acceptance did not persist; inspect before resetting the fixture: ' + run_id)
    elif decision.get('decision') != choice or decision.get('actor') != 'warden-live-fixture-operator':
        raise SystemExit('Unexpected preexisting decision: ' + run_id)
    manifest['runs'].append({'run_id': run_id, 'experiment_id': 'secondary-review-live',
                             'task_pair_id': 'pilot-queue', 'variant': vendor + '-secondary',
                             'repeat': repeat, 'operator_minutes': None})
    receipts['runs'].append({'run_id': run_id, 'run_exit_code': code, 'fixture_outcome': choice,
                             'reason': data.get('reason'),
                             'module_sha256': hashlib.sha256((root / 'pilot-queue.mjs').read_bytes()).hexdigest(),
                             'summary_sha256': hashlib.sha256(summary.read_bytes()).hexdigest(),
                             'acceptance_stdout': check.stdout.strip()})
    (output / 'paired-baseline.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    (output / 'receipts.json').write_text(json.dumps(receipts, indent=2) + '\n', encoding='utf-8')
    print('FIXTURE_' + choice.upper(), run_id, 'role_runs=' + str(data.get('role_runs')), flush=True)
warden(['ledger', '--compare', '--baseline-file', str(output / 'paired-baseline.json')], output / 'comparison.json')
print('COMPLETE', output / 'comparison.json', flush=True)
