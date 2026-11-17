#!/usr/bin/env python3
"""Explicit diagnosed replay within one adopted READ campaign; no retry or scope expansion."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import sys

p=argparse.ArgumentParser()
p.add_argument('--workspace',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--campaign',required=True)
p.add_argument('--methods',required=True,help='Comma-separated exact base names from the adopted campaign')
p.add_argument('--reason',required=True)
a=p.parse_args()
root=a.workspace.resolve()
spec=importlib.util.spec_from_file_location('verification',root/'scripts/testing/verify.py')
v=importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
import json
registry=json.loads(v.CONFIG.read_text())
if a.campaign not in registry['campaigns'] or not a.reason.strip(): raise ValueError('Explicit registered campaign and diagnosis required')
d=v.plan(registry['campaigns'][a.campaign]['level'],a.campaign)
requested=a.methods.split(',')
if len(requested)!=len(set(requested)): raise ValueError('Duplicate method selector')
selected={}
found=set()
for cls, methods in d['expected'].items():
    chosen=[m for m in methods if m.split('(')[0] in requested]
    if chosen:
        selected[cls]=chosen; found.update(m.split('(')[0] for m in chosen)
if found!=set(requested): raise ValueError('Method absent from adopted campaign')
d['original_expected']=d['expected']; d['expected']=selected
selectors=[]
for cls, methods in selected.items():
    selectors.append(cls+'#'+'+'.join(dict.fromkeys(m.split('(')[0] for m in methods)))
d['command']=[('-Dtest='+','.join(selectors)) if s.startswith('-Dtest=') else s for s in d['command']]
d['requalification']=dict(reason=a.reason,expected_invocations=sum(map(len,selected.values())),operator_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
original=v.control_inputs
def controls():
    result=original(); result[str(Path(__file__).resolve())]=v.digest(Path(__file__)); return result
v.control_inputs=controls
sys.exit(v.run(d,a.output.resolve()))
