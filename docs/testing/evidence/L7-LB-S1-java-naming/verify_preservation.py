"""S1 documentary invariant proof; reads Git source and current files, never touches instances."""
from pathlib import Path
import re,json,subprocess,hashlib,collections,sys
root=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
plan=json.loads((root/'docs/testing/evidence/L7-LB-S1-java-naming/rename-plan.json').read_text())
# Worktree qualification may read its immutable plan from the primary checkout.
sha=plan['initial_sha'];sm=plan['symbols']
lex=re.compile(r'(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)|(?P<string>"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|(?P<id>[A-Za-z_$][A-Za-z0-9_$]*)|(?P<other>[^\s])')
change_by_path=collections.defaultdict(dict)
for c in plan['literal_changes']:change_by_path[c['path']][c['old']]=c['new']
def tokens(s,old=False,path=''):
 ts=[]
 for m in lex.finditer(s):
  if m.lastgroup=='comment':continue
  v=m[0]
  if old and m.lastgroup=='id':
   if v=='v3' and ts and ts[-1]=='.':ts.pop();continue
   v='commandfixture' if v=='v3commandfixture' else sm.get(v,v)
  if old and m.lastgroup=='string':v=change_by_path[path].get(v,v)
  ts.append(v)
 if old:
  source=['com','.','kartaguez','.','pocoma','.','infra','.','postgres','.','write','.','commandfixture']
  target=['com','.','kartaguez','.','pocoma','.','infra','.','postgres','.','fixture','.','command']
  i=0
  while i<=len(ts)-len(source):
   if ts[i:i+len(source)]==source:ts[i:i+len(source)]=target;i+=len(target)
   else:i+=1
 if old and path.endswith('/AdmissionArchitectureTest.java'):
  guard=['&','&','c','.','getPackageName','(',')','.','contains','(', '".v3"',')']
  matches=[i for i in range(len(ts)-len(guard)+1) if ts[i:i+len(guard)]==guard]
  assert len(matches)==1,matches
  i=matches[0];del ts[i:i+len(guard)]
 return ts
# Read original source blobs in one Git batch, preserving exact bytes.
requests=''.join(sha+':'+i['old']+'\n' for i in plan['files']).encode()
batch=subprocess.run(['git','cat-file','--batch'],input=requests,cwd=root,capture_output=True,check=True).stdout
original={};offset=0
for i in plan['files']:
 end=batch.index(b'\n',offset);header=batch[offset:end].split();size=int(header[2]);offset=end+1
 original[i['old']]=batch[offset:offset+size].decode();offset+=size+1
all_fq={};strings=0;changed_java=0;prod_strings=0
initial_java={i['old'] for i in plan['files']};current_java=set()
for item in plan['files']:
 before=original[item['old']]
 p=root/item['new'];after=p.read_text();current_java.add(item['new'])
 old_ts=tokens(before,True,item['new']);new_ts=tokens(after)
 assert old_ts==new_ts,('NON_NAMING_TOKEN_CHANGE',item['new'],next(((a,b) for a,b in zip(old_ts,new_ts) if a!=b),None))
 comments=lambda s:[m[0] for m in lex.finditer(s) if m.lastgroup=='comment']
 assert comments(before)==comments(after),('COMMENT_CHANGED',item['new'])
 strings+=sum(m.lastgroup=='string' for m in lex.finditer(after))
 if '/src/main/' in item['new']:prod_strings+=sum(m.lastgroup=='string' for m in lex.finditer(after))
 pkg=re.search(r'^package\s+([\w.]+);',after,re.M)[1];fq=pkg+'.'+p.stem
 assert fq==item['new_fqcn'],(fq,item['new_fqcn'])
 assert fq not in all_fq,('TYPE_COLLISION',fq);all_fq[fq]=item['new']
 assert not re.search(r'v3',fq,re.I),('GENERATION_IN_JAVA_NAME',fq)
 if before!=after or item['old']!=item['new']:changed_java+=1
actual={str(p.relative_to(root)) for p in (root/'app').rglob('*.java') if 'target' not in p.parts}
assert actual==current_java,('UNPLANNED_JAVA_FILES',actual^current_java)
# Every original file outside the declared Java/name-tooling surface must stay byte-identical.
allowed={'scripts/local/test_bruno_qualification.py','scripts/testing/test_read_campaigns.py','scripts/testing/test_verification.py','scripts/testing/verification-campaigns.json','scripts/v3/worker-dev.py'}
entries=subprocess.check_output(['git','-c','core.quotepath=false','ls-tree','-r',sha],cwd=root,text=True).splitlines()
blobs={line.split('\t',1)[1]:line.split('\t',1)[0].split()[2] for line in entries}
protected=[f for f in blobs if f not in initial_java and f not in allowed]
current_blobs=subprocess.run(['git','hash-object','--stdin-paths'],input=''.join(f+'\n' for f in protected),cwd=root,capture_output=True,text=True,check=True).stdout.splitlines()
assert len(current_blobs)==len(protected),'INCOMPLETE_PROTECTED_FILE_INVENTORY'
for f,actual_blob in zip(protected,current_blobs):
 assert blobs[f]==actual_blob,('PROTECTED_FILE_CHANGED',f)
# Registry changes exactly the name dictionary, no thresholds/method selection/history mutation.
registry='scripts/testing/verification-campaigns.json'
old=json.loads(subprocess.check_output(['git','show',sha+':'+registry],cwd=root,text=True))
fqmap={i['old_fqcn']:i['new_fqcn'] for i in plan['files']}
def rename(x):
 if isinstance(x,dict):return {fqmap.get(k,k):(v if k=='archived_campaigns' else rename(v)) for k,v in x.items()}
 if isinstance(x,list):return [rename(v) for v in x]
 return fqmap.get(x,sm.get(x,x)) if isinstance(x,str) else x
assert json.loads((root/registry).read_text())==rename(old),'REGISTRY_SEMANTICS_CHANGED'
result={'status':'PASS','initial_sha':sha,'java_files_reviewed':len(current_java),'java_files_changed':changed_java,'package_mappings':len(plan['packages']),'simple_type_renames':sum(Path(i['old']).stem!=Path(i['new']).stem for i in plan['files']),'qualified_type_renames':sum(i['old_fqcn']!=i['new_fqcn'] for i in plan['files']),'literals_reviewed':strings,'production_literals_reviewed':prod_strings,'approved_literal_changes':len(plan['literal_changes']),'protected_existing_files':len(protected),'sql_files_unchanged':sum(f.endswith('.sql') for f in protected),'registry_changes':'live name mapping only; archived_campaigns identical to initial SHA','type_collisions':0,'non_naming_code_changes':0,'explicit_guard_adaptations':1,'checks':'token stream modulo declared name dictionary, literal allowlist and one obsolete package-generation conjunct; comment bytes; all protected file blobs; exact registry structure'}
print(json.dumps(result,indent=2))
