#!/usr/bin/env python3
"""Storage/documentary audit only. Does not execute Maven or application oracles."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'docs/testing/Evidence_Retention.json'
SPEC = importlib.util.spec_from_file_location('storage', ROOT / 'scripts/testing/evidence-storage.py')
storage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(storage)


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restore-all', action='store_true', help='Physically restore every removed archive and gzip alias into temporary files')
    parser.add_argument('--sample-members', action='store_true', help='Read/hash all TAR members for representative L1/I3/I4/I5 captures')
    args = parser.parse_args()
    inventory = json.loads(MANIFEST.read_text())
    baseline = inventory['baseline']
    rows = inventory['artifacts']
    archived = [r for r in rows if r['category'] == 'B']
    assert all(r['category'] in ('A', 'B') for r in rows)
    removed = {r['path'] for r in archived}
    actual_deleted = set(git('diff', '--name-only', '--diff-filter=D', baseline).decode().splitlines())
    assert removed == actual_deleted, (removed - actual_deleted, actual_deleted - removed)
    assert not git('diff', baseline, '--', 'app'), 'Application/oracle change forbidden'
    added = set(git('diff', '--name-only', '--diff-filter=A', baseline).decode().splitlines())
    expected_added = {row['path'] for row in inventory['delivery_files'] if row['operation'] == 'add'}
    assert added <= expected_added, ('Unexpected added file', added - expected_added)
    modified = git('diff', '--name-only', '--diff-filter=M', baseline).decode().splitlines()
    for name in modified:
        if name in removed or name in ('scripts/testing/evidence-storage.py', 'scripts/testing/test_evidence_storage.py'):
            continue
        assert name.endswith('.md'), ('Unexpected changed file', name)
        before = git('show', baseline + ':' + name).decode()
        after = (ROOT / name).read_text()
        # Historical reports/contracts may change archive link destinations only.
        # The exact allowed substitution list was recorded before deletion.
        for edit in inventory['link_updates']:
            if edit['source'] == name:
                before = before.replace(edit['old_markup'], edit['new_markup'])
        assert before == after, ('Non-link documentary change', name)
    for row in rows:
        if row['category'] == 'B':
            assert not (ROOT / row['path']).exists(), row['path']
            source = row['historical']
            assert git('rev-parse', source['commit'] + ':' + source['path']).decode().strip() == source['blob']
            subprocess.run(['git', 'merge-base', '--is-ancestor', source['commit'], 'HEAD'], cwd=ROOT, check=True)
        else:
            assert storage.digest(ROOT / row['path']) == inventory.get('head_updates', {}).get(row['path'], row['sha256']), row['path']
    links = 0
    for edit in inventory['link_updates']:
        assert edit['new_markup'] in (ROOT / edit['source']).read_text()
        links += 1
    # No newly broken local Markdown links in docs (pre-existing unrelated defects reported separately).
    def missing(text, source, historical=False):
        absent = set()
        for target in re.findall(r'\]\(([^)]+)\)', text):
            target = target.strip().split(' ', 1)[0].strip('<>')
            if re.match(r'^(?:[a-z]+:|#|/)', target):
                continue
            raw = unquote(target.partition('#')[0])
            path = (ROOT / source).parent / raw
            if historical:
                rel = path.resolve().relative_to(ROOT).as_posix()
                if rel not in baseline_paths:
                    absent.add(target)
            elif not path.exists():
                absent.add(target)
        return absent
    baseline_paths = set(git('ls-tree', '-r', '--name-only', baseline).decode().splitlines())
    preexisting = []
    current_documents = {str(p.relative_to(ROOT)) for p in (ROOT / 'docs').rglob('*.md')}
    for source in sorted(baseline_paths | current_documents):
        if not source.startswith('docs/') or not source.endswith('.md'):
            continue
        current = ROOT / source
        if not current.exists():
            continue
        old_text = git('show', baseline + ':' + source).decode() if source in baseline_paths else ''
        old_missing = missing(old_text, source, True)
        new_missing = missing(current.read_text(), source)
        assert not new_missing - old_missing, (source, new_missing - old_missing)
        preexisting.extend((source, target) for target in sorted(new_missing))
    restored = aliases = 0
    member_results = []
    with tempfile.TemporaryDirectory(prefix='pocoma-retention-audit-') as directory:
        destination = Path(directory) / 'proof'
        if args.restore_all:
            for row in archived:
                storage.restore_historical(row['historical'], destination)
                destination.unlink()
                restored += 1
            for entry in inventory['historical_gzip_aliases']:
                storage.restore_historical(entry, destination)
                destination.unlink()
                aliases += 1
        if args.sample_members:
            old = json.loads((ROOT / 'docs/testing/Evidence_Inventory.json').read_text())
            samples = ('L1-AB/A-raw.tar.gz', 'L2-I3/command-1d7cf51c.tar.gz', 'L2-I4/final-command.tar.xz', 'L2-I5/A-9a315e16.tar.xz')
            for suffix in samples:
                row = next(r for r in archived if r['path'].endswith(suffix))
                storage.restore_historical(row['historical'], destination)
                members = []
                with tarfile.open(destination, 'r|*') as tar:
                    for member in tar:
                        if member.isfile():
                            stream = tar.extractfile(member)
                            members.append((member.name, member.size, hashlib.file_digest(stream, 'sha256').hexdigest()))
                member_hash = hashlib.sha256(json.dumps(members, separators=(',', ':')).encode()).hexdigest()
                expected = next(e['archive'] for e in old['artifacts'] if e.get('replacement', e)['path'] == row['path'])
                assert len(members) == expected['members'] and member_hash == expected['member_index_sha256'], row['path']
                member_results.append({'path':row['path'], 'members':len(members), 'member_index_sha256':member_hash})
                destination.unlink()
    print(json.dumps({'storage_audit':'OK', 'application_diff':'EMPTY', 'oracle_and_historical_script_changes':'NONE', 'removed_archives_classified':len(archived), 'physical_restorations':restored, 'original_gzip_restorations':aliases, 'updated_archive_links':links, 'new_broken_local_links':0, 'preexisting_missing_links':preexisting, 'member_samples':member_results, 'application_certification':'NOT RUN'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
