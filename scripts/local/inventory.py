#!/usr/bin/env python3
"""Read-only inventory of local playground state and potential Docker orphans."""
import argparse
from pathlib import Path
import time

import runtime as rt


def _load_instances():
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
    return instances, owners


def _record_container_details(row, value, labels, owner_project, attachments):
    owner, project = owner_project
    mounts = [m for m in value.get('Mounts', []) if m['Type'] == 'volume']
    row.update(service=labels.get('com.docker.compose.service'), state=value['State']['Status'],
               volumes=[mount['Name'] for mount in mounts])
    for mount in mounts:
        attachments.setdefault(mount['Name'], []).append(dict(
            container=row['id'], service=row['service'], owner=owner,
            project=project, destination=mount['Destination']))


def _is_owned_attachment(attachments, name, owners):
    return any(ref['owner'] in owners and owners[ref['owner']]['project'] == ref['project']
               for ref in attachments.get(name, []))


def _classify_resource(row, kind, owners, attachments, buckets):
    owner, project = row['owner'], row['project']
    if owner in owners and owners[owner]['project'] == project:
        owners[owner]['resources'].append(row)
        return
    if kind == 'volume' and _is_owned_attachment(attachments, row['name'], owners):
        buckets['unowned_attached'].append(dict(
            row, attachedTo=attachments[row['name']],
            action='PRESERVE: attached to playground but without matching owner/project labels'))
        return
    is_pocoma = bool(owner) or (project or '').startswith('pocoma-lb-') or row['name'].startswith('pocoma-lb-')
    if is_pocoma:
        buckets['orphans'].append(dict(row, action='RETAIN: ownership requires investigation; no automatic deletion'))
    else:
        buckets['unrelated'].append(row)


def inventory():
    instances, owners = _load_instances()
    buckets = dict(unrelated=[], orphans=[], unowned_attached=[])
    attachments = {}
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
                _record_container_details(row, value, labels, (owner, project), attachments)
            _classify_resource(row, kind, owners, attachments, buckets)
    return dict(observedAt=time.time(), instances=instances, potentialOrphans=buckets['orphans'],
                unownedAttachedVolumes=buckets['unowned_attached'],
                unrelatedResources=buckets['unrelated'], composeProjects=rt.docker_json('compose', 'ls', '--all', '--format', 'json'),
                policy='Dry-run only. No resource or persistent data is deleted. Explicit human confirmation required.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Evidence output must be new')
    rt.save(args.output, inventory())
    print('Read-only inventory saved: ' + str(args.output))
