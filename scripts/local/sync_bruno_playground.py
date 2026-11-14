#!/usr/bin/env python3
"""Compose visible Bruno journeys from the editable domain .bru sources.

No HTTP, auth, database or execution-time dependency. --check never writes.
"""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
COLLECTION = ROOT / 'scripts/bruno-playground'


def outputs():
    catalogue = json.loads((COLLECTION/'catalogue.json').read_text())
    sources = {entry['id']: entry for entry in catalogue}
    journeys = json.loads((COLLECTION/'journeys.json').read_text())
    files, inventory = {}, []
    for journey in journeys:
        folder = '90 - End-to-End Journeys/'+journey['id']+' '+journey['name']
        files[folder+'/folder.bru'] = 'meta {\n  name: '+journey['id']+' '+journey['name']+'\n  seq: '+str(len(inventory)+1)+'\n}\n\ndocs {\n  '+journey['description']+'\n  Menu Run : toutes les etapes sont visibles et editables.\n}\n'
        for number, step in enumerate(journey['steps'],1):
            entry = sources[step['request']]
            text = (COLLECTION/entry['file']).read_text()
            match = re.search(r'const options = (.*);',text)
            options = json.loads(match[1])
            options.update(step.get('options',{}))
            if options['role'] == 'actor': options['role'] = 'owner'
            identity = journey['id']+'-'+str(number).zfill(2)+'-'+entry['id']
            options['id'] = identity
            text = re.sub(r'const options = .*?;',lambda _: 'const options = '+json.dumps(options,separators=(',',':'))+';',text)
            text = re.sub(r'  name: .*', '  name: '+identity+' '+Path(entry['file']).stem.split(' ',1)[1], text, count=1)
            text = re.sub(r'  seq: \d+', '  seq: '+str(number),text,count=1)
            text = text.replace("test('"+entry['id']+' ',"test('"+identity+' ')
            if number == 1:
                text = text.replace('  await require(',"  bru.setVar('actor', 'owner');\n  await require(",1)
            if step.get('setup'):
                text = text.replace('  await require(', '  '+step['setup']+'\n  await require(',1)
            if step.get('checks'):
                text = text.replace('    work.record(', '    '+step['checks']+'\n    work.record(',1)
            text = text.replace('docs {','docs {\n  Parcours '+journey['id']+' ; etape '+str(number)+'. Source : '+entry['file']+'.',1)
            filename=folder+'/'+str(number).zfill(2)+' '+Path(entry['file']).name
            files[filename]=text
            url_template = re.search(r'  url: (.*)',text)[1].split('?')[0]
            path_template = re.sub(r'^\{\{[^}]+\}\}','',url_template)
            if url_template.startswith('{{issuerUrl}}'):
                path_template = '/realms/'+json.loads((ROOT/'docker/keycloak/pocoma-realm.json').read_text())['realm']+path_template
            inventory.append({'id':identity,'file':filename,'source':entry['file'],'journey':journey['id'],
                'method':entry['method'],'pathTemplate':path_template,
                'status':options.get('status',202),
                'tests':[identity+' transport and privacy',identity+' contract and variables']})
    files['inventory.json'] = json.dumps({'requests':inventory,'journeys':[j['id'] for j in journeys],
        'namedTests':sum(len(r['tests']) for r in inventory),'fixtureReadInjection':False,
        'cliSelection':'90 - End-to-End Journeys','guiLoginRequests':['00 - Getting Started/00 Login Owner.bru','00 - Getting Started/01 Login Guest.bru']},indent=2)+'\n'
    # Stable folder order in the GUI, independent of filesystem enumeration.
    folders=sorted({str(Path(e['file']).parent) for e in catalogue}|{'90 - End-to-End Journeys'})
    for seq,folder in enumerate(folders,1):
        files[folder+'/folder.bru']='meta {\n  name: '+folder+'\n  seq: '+str(seq)+'\n}\n'
    return files


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    expected=outputs()
    stale=[]
    for name,text in expected.items():
        target=COLLECTION/name
        if not target.exists() or target.read_text()!=text:
            stale.append(name)
            if not args.check:
                target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
    existing={str(p.relative_to(COLLECTION)) for p in (COLLECTION/'90 - End-to-End Journeys').rglob('*.bru')}
    extra=existing-set(expected)
    if extra and not args.check:
        for name in extra:(COLLECTION/name).unlink()
    if args.check and (stale or extra):
        raise SystemExit('Bruno sources/journeys differ: '+', '.join(sorted(set(stale)|extra)))
    data=json.loads(expected['inventory.json'])
    print(f"Bruno composition {'CHECK PASS' if args.check else 'SYNC'}: {len(data['requests'])} requests, {data['namedTests']} named tests, {len(data['journeys'])} journeys")


if __name__=='__main__':main()
