#!/usr/bin/env python3
"""Read-only inventory of local playground state and potential Docker orphans."""
import argparse
from pathlib import Path
import time

import runtime as rt


def inventory():
    instances, owners = [], {}
    root = rt.HOME_LOCAL / 'instances'
    for path in sorted(root.iterdir()) if root.exists() else []:
        rt.safe_tree(path)
        row = dict(instance=path.name, directory=str(path), resources=[])
        if (path / 'manifest.json').exists():
            m = rt.load_manifest(path)
            rt.ownership(m)
            row.update(owner=m['owner'], project=m['project'], manifestState=m['state'],
                       plan=rt.destruction_plan(path, m))
            owners[m['owner']] = row
        else:
            row.update(manifestState='NO_MANIFEST', plan='RETAIN: no ownership authority')
        row['privateFilesBytes'] = sum(f.stat().st_size for f in path.rglob('*') if f.is_file())
        instances.append(row)
    unrelated, orphans = [], []
    for kind, query, inspect in (
        ('container', ['ps', '-aq'], ['inspect']),
        ('volume', ['volume', 'ls', '-q'], ['volume', 'inspect']),
        ('network', ['network', 'ls', '-q'], ['network', 'inspect'])):
        for resource in rt.run(['docker', *query]).splitlines():
            value, = rt.docker_json(*inspect, resource)
            labels = (value.get('Config', {}).get('Labels') if kind == 'container' else value.get('Labels')) or {}
            project, owner = labels.get('com.docker.compose.project'), labels.get(rt.LABEL)
            row = dict(kind=kind, id=value.get('Id', value.get('ID', resource)),
                       name=value.get('Name', resource).lstrip('/'), project=project, owner=owner)
            if kind == 'container':
                row.update(service=labels.get('com.docker.compose.service'), state=value['State']['Status'],
                           volumes=[mount['Name'] for mount in value.get('Mounts', []) if mount['Type'] == 'volume'])
            if owner in owners and owners[owner]['project'] == project:
                owners[owner]['resources'].append(row)
            elif owner or (project or '').startswith('pocoma-lb-') or row['name'].startswith('pocoma-lb-'):
                orphans.append(dict(row, action='RETAIN: ownership requires investigation; no automatic deletion'))
            else:
                unrelated.append(row)
    return dict(observedAt=time.time(), instances=instances, potentialOrphans=orphans,
                unrelatedResources=unrelated, composeProjects=rt.docker_json('compose', 'ls', '--all', '--format', 'json'),
                policy='Dry-run only. No resource or persistent data is deleted. Explicit human confirmation required.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Evidence output must be new')
    rt.save(args.output, inventory())
    print('Read-only inventory saved: ' + str(args.output))
