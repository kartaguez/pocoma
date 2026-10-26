from pathlib import Path
import json,subprocess,hashlib,re
root=Path('/private/tmp/pocoma-v3-l2-i1-execution');base=root/'docs/testing/evidence/L2-I1'
names=['COMMAND','READ','MONOLITH','ARCHITECTURE']
summaries={n:json.loads((base/(n+'-summary.json')).read_text()) for n in names}
assert all(d['totals']['errors']==d['totals']['failures']==0 for d in summaries.values())
sha='6835b5c15cc40d09c22e0b0a4f3b6538ac49992d'
subprocess.run(['git','diff','--exit-code',sha,'--','app','scripts/v3','docs/testing/evidence/L2-I1/routing-vectors.csv'],cwd=root,check=True)
observed={}
for gate,d in summaries.items():
 for suite in d['suites']:
  for m in suite['methods']:observed.setdefault((m['class'],m['method']),[]).append({'gate':gate,'status':m['status']})
regressions={}
for file in ['L1_Proof_Manifest.json','L1_F_Proof_Manifest.json','L1_F2_Proof_Manifest.json']:
 d=json.loads((root/'docs/v3'/file).read_text());required=[]
 def walk(x):
  if isinstance(x,dict):
   if 'class' in x and isinstance(x.get('methods'),list):required.extend((x['class'],m) for m in x['methods'])
   for v in x.values():walk(v)
  elif isinstance(x,list):
   for v in x:walk(v)
 walk(d)
 rows=[{'class':c,'method':m,'observations':observed.get((c,m),[])} for c,m in sorted(set(required))]
 assert all(any(o['status']=='PASS' for o in r['observations']) for r in rows),file
 regressions[file]={'manifest_sha256':hashlib.sha256((root/'docs/v3'/file).read_bytes()).hexdigest(),'methods':rows,'declared':len(rows),'passed':len(rows)}
newclasses={'RoutingV1Test','ConsumptionFoundationPostgresTest','ReadConsumptionFoundationPostgresTest','ConsumptionBootstrapPostgresTest','ConsumptionPilotIsolationTest','V3ConsumptionFoundationArchitectureTest'}
local=[{'class':c,'method':m,'observations':o} for (c,m),o in sorted(observed.items()) if c.split('.')[-1] in newclasses]
assert len(local)==33,len(local)
assert all(all(o['status']=='PASS' for o in row['observations']) for row in local)
(base/'L1-regression.json').write_text(json.dumps({'scope':'Fresh regression only; historical reports and limits remain unchanged','manifests':regressions},indent=2)+'\n')
(base/'I1-methods.json').write_text(json.dumps({'local_verdict':'PASS bounded I1','final_test_sha':sha,'production_sha':'12eaa5ae983c23a4b07c0299ce59c4d7933bfb5d','methods':local,'unique_methods':len(local),'scope_excludes':['I2','I3','I4','I5','G1','G2','G3']},indent=2)+'\n')
status=json.loads(Path('/private/tmp/i1-gates-status.json').read_text());(base/'gate-timings.json').write_text(json.dumps(status,indent=2)+'\n')
index={'scope':'Pocoma V3 L2 I1 only; no global L2 certification','initial_remote_sha':'2b4d46cf08509c95fc490afa1d66ae38f41957b6','tested_code_sha':sha,'production_source_sha':'12eaa5ae983c23a4b07c0299ce59c4d7933bfb5d',
'G0':'crossed by explicit human decision accepting existing L1-F2 evidence with documented limits; no new audit required',
'I1':'implemented / local bounded PASS','future_gates':{'G1':'FUTURE','G2':'FUTURE','G3':'FUTURE'},
'verification':{'primary_slice':'COMMAND','secondary_canonical_slices':[],'additional_module_proofs':['infra-persistence-read-jdbc','runtime-monolith'],'unexpected_slice_crossings':[],'architecture_gate':'REQUIRED / RUN','full_reactor':'NOT REQUIRED / NOT RUN; bounded I1, major L2 closure and main integration remain future','database':'CURRENT fresh V3; unchanged L1/historical tests also run transitively'},
'local_methods':33,'all_required_local_methods':'PASS','L1_manifest_methods':{f:{'declared':d['declared'],'passed':d['passed']} for f,d in regressions.items()},
'gates':{n:{'command':d['command'],'summary':n+'-summary.json','tested_sha':d['code_sha'],'totals':d['totals'],'archive':d['archive']} for n,d in summaries.items()},
'limitations':['No Engine, acquisition, claims, renewal, retries, Discovery or loops in I1','No production or existing user database deployment; ephemeral new isolated test databases only','One historical optional V2 exploratory measurement is skipped in applicable canonical gates; no mandatory I1 skip','Performance samples and indexes do not establish general O(1) or SLOs','No global L1-F2 or V3 certification; historical proof limitations remain accepted for G0'],
'publication':{'branch':'feat/v3-architecture','force_push':False,'final_documentary_commit':'reported in final delivery; code tree must equal tested_code_sha'},
'files':{}}
index['files']={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(base.iterdir()) if p.is_file() and p.name!='Gate-index.json'}
(base/'Gate-index.json').write_text(json.dumps(index,indent=2,ensure_ascii=False)+'\n')
rows=['| Gate / commande exacte depuis `app/` | Tests | Échecs | Erreurs | Skips |','|---|---:|---:|---:|---:|']
for n,d in summaries.items():
 t=d['totals'];rows.append(f"| {n} (`{d['code_sha'][:8]}`) — `{d['command']}` | {t['tests']} | {t['failures']} | {t['errors']} | {t['skipped']} |")
results='\n'.join(rows)+'''\n\nLes décomptes sont par gate, avec recouvrement Maven ; ne pas les additionner comme tests uniques. Les **33 nouvelles méthodes I1** sont toutes exécutées et PASS, sans skip. Le skip historique éventuel est `TerminalSequencingPostgresTest.zExploratoryContentionSeparateFromCorrectness`, optionnel (`step3b.measure` absent) ; il ne devient pas PASS. Les 91 méthodes du manifeste L1, 17 L1-F et 24 L1-F2 sont retrouvées PASS dans les XML frais (inventaires avec recouvrement, détail dans `L1-regression.json`). Le gate documentaire `python3 docs/v3/check_l2_manifest.py`, l'oracle `python3 scripts/v3/check-routing-vectors.py` et `git diff --check` complètent cette preuve.\n'''
p=root/'docs/v3/L2_I1_G0_Readiness_and_Implementation_Plan.md';s=p.read_text();s=re.sub(r'<!-- I1_GATE_RESULTS -->.*?<!-- /I1_GATE_RESULTS -->','<!-- I1_GATE_RESULTS -->\n'+results+'<!-- /I1_GATE_RESULTS -->',s,flags=re.S)
exits='''| Critère de sortie I1 | Résultat et borne |
|---|---|
| Deux cibles V3 neuves isolées | PASS : bases WRITE `test` et READ `pilot_read`, schémas dédiés, sources historiques témoins conservées ; aucun déploiement utilisateur |
| DDL / contrats / partitions | PASS : quatre tables par autorité, 2/7 routes, 8/28 ownership, absence du slot Command dédié sur le pilote |
| Identités et routage | PASS : neuf UUID exacts Java/SQL/trigger, sept familles, 21 vecteurs indépendants, P=4 pilote |
| Bootstrap / concurrence / rollback | PASS : cible et rôle contrôlés sous verrou, deuxième admin bloqué puis refusé, fautes et rerun sans état partiel |
| Privilèges runtime | PASS : SELECT seulement ; DML/DDL, droits hérités et NOINHERIT/SET ROLE dangereux refusés |
| Conservation Command et L1 | PASS local : fonctions/triggers/contraintes préservés, positifs/négatifs SQL et inventaires historiques frais ; pas d'Engine commun certifié |
| Compositions distribuée et monolithique | PASS : démarrage/restart inertes, import ancien direct/indirect et types writers/owners/pollers refusés, deux bases physiques |
| Tests / architecture / provenance | PASS local : quatre gates, 33 méthodes I1 sans skip, XML/logs/catalogues/états/empreintes conservés |
| G1/G2/G3 | FUTURS ; aucun PASS de certification L2 |

**I1 implémenté / PASS local borné.** Le skip exploratoire V2 et les mécanismes absents empêchent toute lecture de ce verdict comme certification globale. Les preuves d'exploitation doivent encore être produites sur une cible réelle explicitement inventoriée lorsqu'un déploiement sera demandé.
'''
s=re.sub(r'<!-- I1_EXIT -->.*?<!-- /I1_EXIT -->','<!-- I1_EXIT -->\n'+exits+'<!-- /I1_EXIT -->',s,flags=re.S)
s=s.replace('Exécution I1 ouverte depuis','Exécution I1 réalisée (détail et PASS local borné au §9) depuis',1)
s=s.replace('Le profil désactive la persistence automatique','Les noms de base/schéma/rôle du pilote sont des identifiants simples `[a-z][a-z0-9_]*` ; connexions TCP requises. Le profil désactive la persistence automatique')
p.write_text(s)
p=root/'docs/v3/L2_Design_and_Verification_Plan.md';s=p.read_text().replace('I1 AUTORISÉ, fondations implémentées et vérification en cours','I1 IMPLÉMENTÉ / PASS LOCAL BORNÉ, suite non engagée');p.write_text(s)
p=root/'docs/v3/V3_Implementation_Master_Plan.md';s=p.read_text().replace('L2 : IMPLEMENTING (I1 uniquement)','L2 : I1 INTERNAL PASS (borné), suite non engagée').replace('**L2 — IMPLEMENTING / I1**','**L2 — I1 INTERNAL PASS / suite non engagée**').replace('I1 en vérification','I1 implémenté / PASS local borné').replace('[Plan L2 préparé]','[Plan L2 courant]');p.write_text(s)
print('FINAL LOCAL PROOF', {n:d['totals'] for n,d in summaries.items()}, 'I1 methods',len(local))
