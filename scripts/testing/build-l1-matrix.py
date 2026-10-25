#!/usr/bin/env python3
"""Instantiate SPEC-10 contributions from fresh L1 gate XML evidence; never certify future paths."""
import argparse,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

# Existing normative IDs only. Method prefixes resolve uniquely in the committed proof manifest.
MAP={}
def assign(ids,lot,methods,limit='Bruno/HTTP et composants V3 externes non exécutés ; PASS limité à la contribution interne L1.'):
    for key in ids.split():MAP[key]=(lot,methods.split(),limit)
assign('D01-A D01-B D01-C D01-D D01-E D01-F D01-G D01-H D01-L','A/B/D/E','b02 e01 d10')
assign('D01-I D01-J','B','b03 b09 b10')
assign('D01-K','A/B/C/D/E','b05 c01 c10 d14 e02')
assign('D01-M','A/B/E','b04 e02')
for letter,methods in {'A':'c01 c02','B':'c03','C':'c01 l1a09 e04','D':'c03 d12','E':'c08','F':'l1a02 e04','G':'l1a05 c01 e04','H':'l1a05 c01 e04','I':'c01 e02 e03','J':'c01 e02 e03','K':'c02 d12','L':'c03 d12','M':'c02','N':'c01 c08 e02','O':'c06 c07 c10','P':'c09','Q':'l1a10 c01','R':'b07 c01','S':'c04 c05 d10'}.items():assign('D02-'+letter,'A/B/C/D/E',methods)
for letter,methods in {'A':'d01 e01','B':'d01','C':'d03 d06 d11','D':'d02 d10','E':'d02 d11','F':'d10 e01','G':'d10 e01','H':'d04 d10 e01','I':'d01 d04','J':'d05','K':'l1a11 l1a08 d05 d13','L':'d15','M':'d02 d10 e01','N':'d04 d10','O':'d06'}.items():assign('D03-'+letter,'A/B/D/E',methods,'Calcul/histoire internes exécutés. Matérialisation READ immuable/hors ordre, Bruno et performance reportés. D03-L : primitives B composées à une V, aucune nouvelle Command combinée.')
for letter,methods in {'A':'c16','B':'c16','C':'c16','E':'c01 e02','F':'c10 d14','H':'c16'}.items():assign('E01-'+letter,'C/E',methods,'Seulement familles POT/COMMAND_COMPLETION ; les cinq familles et Identity/Invitation restent reportées.')
for letter,methods in {'A':'c01 e01','B':'c01 e02 e03','C':'c01 e02 e03','F':'c10 d14','G':'c13','H':'c09','I':'c19','J':'c01'}.items():assign('E02-'+letter,'C/D/E',methods,'Seulement Command Pot interne ; autorité/admission/acquisition en fixtures explicites, autres familles et Worker complet reportés.')
for family,entries in {
 'C03':{'E':'c14','F':'c15','G':'c14','H':'c14','J':'c13 c14 c15'},
 'C04':{'A':'c09','B':'c14','C':'c15','D':'c10 d14','E':'c13','F':'c13','G':'c14 c21','I':'c09 c10 d14','K':'c09 c13 c16','L':'carch02 carch03'},
 'C05':{'A':'c24','B':'c10 d14','C':'c02 d12','D':'c02','E':'c03 d12 c19','F':'c12','G':'c14 c21','H':'c23','I':'c21','J':'c12','K':'c13 c21'},
 'C06':{'A':'c17','E':'c17','F':'c17','G':'c17','H':'c18','I':'c20','L':'c13 c14 c15','M':'carch02 carch03'}
}.items():
 for letter,methods in entries.items():assign(family+'-'+letter,'C/D/E',methods,'Contribution Command Pot bornée. Acquisition/discovery, Worker V3 complet et frontières READ non certifiés.')
for letter,methods in {'A':'d01','B':'d03 d11','C':'d02 d10','D':'d10 e01','E':'d10 d11','F':'d04 d10','G':'d04 d10','H':'d10','L':'d05','M':'d06'}.items():assign('P02-'+letter,'D/E',methods,'Contribution numérique préparatoire seulement : aucune matérialisation, insertion immuable, slot READ ou mesure E2E. P02-L : refus de mélanges de Pots, parcours multi-Pots READ reporté.')
assign('A11-A A11-D','A/B/C/D/E','d10 e01','Suppression et historique/calcul tardif internes seulement ; publication et stockage READ/HTTP restent aux lots ultérieurs.')

def main():
 p=argparse.ArgumentParser();p.add_argument('--summaries',nargs='+',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 manifest=json.loads((ROOT/'docs/v3/L1_Proof_Manifest.json').read_text())
 methods=[(suite['class'],method) for suite in manifest['suites'].values() for method in suite['methods']]
 gates=[(Path(path),json.loads(Path(path).read_text())) for path in a.summaries]
 rows=[]
 for line in (ROOT/'docs/v3/SPEC-10_Test_Certification.md').read_text().splitlines():
  cells=[c.strip() for c in line.split('|')]
  if len(cells)<5 or not re.fullmatch(r'(?:D0[123]|I0[12]|E0[123]|INV|C0[1-6]|P0[123]|R01|S0[12]|A1[01]|RCV01|O01|F01)-[A-Z]',cells[1]):continue
  key=cells[1];row=dict(normative_id=key,required_variants=cells[2],normative_oracle=cells[3])
  if key not in MAP:
   row.update(responsible_lot='Lots ultérieurs selon plan §21',l1_status='DEFERRED_OUTSIDE_L1',result='NOT_EXECUTED',methods=[],execution_gates=[],proofs=[],limit='Parcours système absent de L1 ; aucune preuve transférée depuis V2 ou depuis une fixture.')
  else:
   lot,prefixes,limit=MAP[key];resolved=[]
   for prefix in prefixes:
    matches=[m for m in methods if m[1].startswith(prefix) and (len(m[1])==len(prefix) or not m[1][len(prefix)].isdigit())]
    if len(matches)!=1:raise RuntimeError(f'{key}: ambiguous/missing method {prefix}: {matches}')
    identity=matches[0];proofs=[]
    for path,gate in gates:
     if any(x['classname']==identity[0] and x['method']==identity[1] and x['outcome']=='PASS' for x in gate['executions']):
      proofs.append(dict(gate=gate['gate'],revision=gate['revision'],summary=str(path.relative_to(ROOT))))
    if not proofs:raise RuntimeError(f'{key}: no passing execution for {identity}')
    candidates=list((ROOT/'app').glob('*/src/test/java/'+identity[0].replace('.','/')+'.java'))
    resolved.append(dict(classname=identity[0],method=identity[1],source=str(candidates[0].relative_to(ROOT)) if candidates else None,proofs=proofs))
   row.update(responsible_lot=lot,l1_status='EXECUTED_INTERNAL_CONTRIBUTION',result='PASS_INTERNAL_ONLY',methods=resolved,execution_gates=sorted({p['gate'] for m in resolved for p in m['proofs']}),limit=limit)
  row['global_SPEC10_verdict']='NOT_CERTIFIED'
  rows.append(row)
 for line in (ROOT/'docs/v3/SPEC-10_Test_Certification.md').read_text().splitlines():
  match=re.match(r'- (INV-[A-J]) : (.+)',line)
  if match: rows.append(dict(normative_id=match[1],required_variants=match[2],responsible_lot='Invitation/READ/Supra',l1_status='DEFERRED_OUTSIDE_L1',result='NOT_EXECUTED',global_SPEC10_verdict='NOT_CERTIFIED',limit='d07/d08/d09 testent les préconditions pures réutilisables uniquement ; aucun cycle invitation livré.'))
 result=dict(scope='L1 interne exclusivement ; aucun PASS global SPEC-10/V3',tested_revisions=sorted({g['revision'] for _,g in gates}),rows=rows,
             non_applicable=[dict(id='PRJ-04',status='REMOVED_NOT_REUSED')],
             supplemental_limits=['CON-05 AUD-V3-007/Q : c12/c21/c23/c24 raccord Command seulement ; réactivation administrative et scheduling réels reportés.',
             'CON-06 N/O/P : WRITE/READ mixte et toutes Consumptions reportés ; aucun 2PC ajouté.',
             'INV-01 : d07/d08/d09 règles pures seulement ; token/expiration/confidentialité et cycle durable reportés.',
             'A-01 gardes A/C réexécutées ; A-02 contrôle automatique des entrées et publication séparé ; I-01 suivi borné L1, transversal futur ouvert.'])
 Path(a.output).write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
 print(json.dumps(dict(rows=len(rows),internal=sum(r['result']=='PASS_INTERNAL_ONLY' for r in rows),deferred=sum(r['result']=='NOT_EXECUTED' for r in rows))))
if __name__=='__main__':main()
