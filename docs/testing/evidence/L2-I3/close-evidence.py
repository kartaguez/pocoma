from pathlib import Path
import hashlib,json,subprocess,tarfile,shutil,re
ROOT=Path('/private/tmp/pocoma-v3-i3-execution')
OUT=ROOT/'docs/testing/evidence/L2-I3'
CODE='1d7cf51c13671c7c85a5c9c1281f526be2b2abfb'
SHORT=CODE[:8]
MANIFEST=ROOT/'docs/v3/L2_I3_Proof_Manifest.json'
m=json.loads(MANIFEST.read_text())
index=[];results={}
for gate in ['command','read','monolith','architecture']:
    source=Path('/private/tmp/pocoma-i3-gates')/(gate+'-'+SHORT)
    result=json.loads((source/'result.json').read_text())
    assert result['sha']==CODE and result['verdict']=='PASS' and result['exit_code']==0,(gate,result.get('problems'))
    results[gate]=result
    archive=OUT/(gate+'-'+SHORT+'.tar.gz')
    with tarfile.open(archive,'w:gz') as tar:tar.add(source,arcname=source.name)
    summary={k:v for k,v in result.items() if k not in ['cases','artifacts']}
    (OUT/(gate+'-summary.json')).write_text(json.dumps(summary,indent=2)+'\n')
    index.append({'gate':gate,'sha':CODE,'command':result['command'],'verdict':'PASS','tests':result['tests'],'required_methods':result['required_methods'],'failures_or_errors':result['failures_or_errors'],'skips':result['skips'],'archive':archive.name,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'result_sha256':hashlib.sha256((source/'result.json').read_bytes()).hexdigest(),'artifacts':len(result['artifacts'])})
(OUT/'Gate-index.json').write_text(json.dumps(index,indent=2)+'\n')
coverage=[]
known_ids={parts[2].strip() for line in (ROOT/'docs/v3/L2_Certification_Coverage_Manifest.md').read_text().splitlines() if line.startswith('|') and len(parts:=line.split('|'))>3 and parts[2].strip().startswith('L2M-')}
extra={
'i3_lease_races':['R3-06','R10-09'],
'i3_route_admin_serialization':['R3-05','R3-06'],
'i3_acquisition_partial':['R10-06-SATURATION','R10-06','R10-07'],
'i3_acquisition_lease_clock':['R3-06'],
'i3_renew_s1_s2':['R10-08-S1S2','R10-08','R10-09'],
'i3_renew_relation_timeout':['R10-08'],
'i3_cession_once':['R5-05'],
'i3_quarantine_persists':['R8-01','R8-02','R5-06'],
'i3_reactivate':['R2-06','R5-08'],
'i3_two_admins':['R5-08'],
'i3_reactivate_over_cap':['R2-06','R2-07'],
'i3_other_slot_progresses':['R8-01','R10-11'],
'i3_retry_config_prospective':['R5-09','R5-10'],
'i3_capacity_uncertainty':['R10-06','R9-05','R5-04'],
'i3_failure_classification':['R5-03','R5-04']}
for scenario in m['scenarios']:
    method=scenario['method'];scenario['status']='PASS — bounded I3 contribution';scenario['code_sha']=CODE
    match=re.fullmatch('i3_(c0[35])_([a-z])',method)
    ids=['L2M-'+match[1].upper()+'-'+match[2].upper()] if match else []
    ids += ['L2M-'+value for value in extra.get(method,[])]
    assert set(ids)<=known_ids, set(ids)-known_ids
    scenario['normative_ids']=ids
    scenario['global_requirement_status']='INCONCLUSIVE'
    scenario['evidence']=[{'gate':gate,'archive':gate+'-'+SHORT+'.tar.gz','xml_class':'com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.ConsumptionLifecyclePostgresTest','method':method,'physical_files':[method+'-WRITE.txt',method+'-READ.txt']} for gate in ['command','monolith','architecture']]
    coverage.append({'method':method,'normative_ids':ids,'bounded_variant_status':'PASS','authorities':scenario['authorities'],'global_requirement_status':'INCONCLUSIVE','scope':'productive lifecycle primitives; WRITE real Command with fixture Identity; READ technical effect; Discovery/loops/real projections excluded','evidence':scenario['evidence']})
(OUT/'Coverage-by-ID.json').write_text(json.dumps(coverage,indent=2)+'\n')
m['status']='CLOSED / LOCAL BOUNDED PASS — AUDIT PENDING';m['tested_code_sha']=CODE;m['global_L2']='INCONCLUSIVE';m['primary_slice']='COMMAND';m['secondary_slices']=[];m['direct_proofs']=['infra-persistence-read-jdbc','runtime-monolith'];m['global_architecture_gate']='REQUIRED / PASS';m['full_reactor']={'required':False,'executed':False,'reason':'bounded I3, no Maven restructuring, no major L2 closure or main integration'};m['unexpected_slice_crossing']='NONE productive; one additional architecture inventory test amended before correction';m['gates']=[{k:v for k,v in item.items() if k!='skips'} for item in index];m['proof_index']='../testing/evidence/L2-I3/Gate-index.json';m['independent_audit']='PENDING; no L2 ACCEPTED or G1/G2/G3';m['database']='CURRENT V3 fresh isolated WRITE/READ; existing migrations; historical transitive suites reported separately';m['postgres_image']='postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73'
MANIFEST.write_text(json.dumps(m,indent=2)+'\n')
provenance={'start_sha':'bb33b56dc86a8a1d31c577f983d7ea1a07bc8128','tested_code_sha':CODE,'app_tree':subprocess.check_output(['git','rev-parse',CODE+':app'],cwd=ROOT,text=True).strip(),'scripts_tree':subprocess.check_output(['git','rev-parse',CODE+':scripts'],cwd=ROOT,text=True).strip(),'gates':index,'delivery_rule':'Final commit adds documentation/evidence only; git diff CODE HEAD -- app scripts must be empty. Exact published SHA is Git HEAD and the publication receipt; cannot self-embed a Git object ID.'}
(OUT/'Proof-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print(json.dumps([{k:item[k] for k in ['gate','tests','required_methods','failures_or_errors','verdict']} for item in index],indent=2))
