"""Adversarial controls for the local Bruno proof collector, without HTTP."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parent))
import runtime

class BrunoQualification(unittest.TestCase):
    def data(self):
        item={'id':'J-01','file':'90/J/01.bru','tests':['transport','contract'],'method':'GET','pathTemplate':'/api/v3/pots/{{potId}}','status':200}
        record={'request':'J-01','verdict':'PASS','assertions':4,'requests':[{'method':'GET','path':'/api/v3/pots/abc','status':200}]}
        suite=ET.fromstring('<testsuite file="90/J/01.bru" tests="2" failures="0" errors="0" skipped="0"><testcase name="transport" status="pass"/><testcase name="contract" status="pass"/></testsuite>')
        return {'requests':[item]},[record],[suite]
    def test_exact_nonempty_proof(self):
        self.assertTrue(runtime.validate_bruno_results(*self.data(),0)['success'])
    def test_empty_never_passes(self):
        self.assertFalse(runtime.validate_bruno_results({'requests':[]},[],[],0)['success'])
    def test_zero_receipts_or_zero_junit(self):
        inv,records,suites=self.data()
        self.assertFalse(runtime.validate_bruno_results(inv,[],suites,0)['success'])
        self.assertFalse(runtime.validate_bruno_results(inv,records,[],0)['success'])
    def test_wrong_identity_route_method_status_or_zero_assertions(self):
        for field,value in [('request','other'),('assertions',0)]:
            inv,records,suites=self.data();records[0][field]=value
            self.assertFalse(runtime.validate_bruno_results(inv,records,suites,0)['success'])
        for field,value in [('method','POST'),('path','/api/v3/me/binding'),('status',404)]:
            inv,records,suites=self.data();records[0]['requests'][-1][field]=value
            self.assertFalse(runtime.validate_bruno_results(inv,records,suites,0)['success'])
    def test_duplicate_or_missing_request(self):
        inv,records,suites=self.data()
        self.assertFalse(runtime.validate_bruno_results(inv,records*2,suites,0)['success'])
        self.assertFalse(runtime.validate_bruno_results(inv,records,suites*2,0)['success'])
    def test_same_counts_wrong_test_names_or_file(self):
        inv,records,suites=self.data();suites[0].find('testcase').set('name','foreign')
        self.assertFalse(runtime.validate_bruno_results(inv,records,suites,0)['success'])
        inv,records,suites=self.data();suites[0].set('file','wrong.bru')
        self.assertFalse(runtime.validate_bruno_results(inv,records,suites,0)['success'])
    def test_failure_skip_error_and_process_exit(self):
        for flag in ['failures','errors','skipped']:
            inv,records,suites=self.data();suites[0].set(flag,'1')
            self.assertFalse(runtime.validate_bruno_results(inv,records,suites,0)['success'])
        inv,records,suites=self.data();ET.SubElement(suites[0].find('testcase'),'skipped')
        self.assertFalse(runtime.validate_bruno_results(inv,records,suites,0)['success'])
        self.assertFalse(runtime.validate_bruno_results(*self.data(),1)['success'])
    def test_reordered_same_totals(self):
        inv,records,suites=self.data()
        other=deepcopy(inv['requests'][0]);other.update(id='J-02',file='90/J/02.bru',tests=['transport2','contract2'])
        inv['requests'].append(other)
        record=deepcopy(records[0]);record['request']='J-02';records.append(record)
        suite=deepcopy(suites[0]);suite.set('file',other['file'])
        for test,name in zip(suite.findall('testcase'),other['tests']):test.set('name',name)
        suites.append(suite)
        self.assertTrue(runtime.validate_bruno_results(inv,records,suites,0)['success'])
        self.assertFalse(runtime.validate_bruno_results(inv,records[::-1],suites,0)['success'])
    def test_inventory_covers_all_public_admitted_intents(self):
        root=runtime.ROOT
        catalogue=json.loads((root/'scripts/bruno-playground/catalogue.json').read_text())
        import re
        commands=(root/'app/contract-write-admission/src/main/java/com/kartaguez/pocoma/contract/writeadmission/CommandAdmissionInput.java').read_text()
        operations=set()
        for match in re.finditer(r'case ((?:"\w+"(?:, )?)+) ->',commands):
            operations.update(re.findall(r'"(\w+)"',match[1]))
        self.assertEqual(13,len(operations))
        self.assertEqual(operations,{x['operation'] for x in catalogue if x['path']=='/commands' and x['options']['kind']=='commandAdmission'})
        self.assertEqual({'Registration','Unlink'},{x['operation'] for x in catalogue if x['options']['kind']=='identityAdmission'})
    def test_inventory_matches_actual_public_controllers_and_bru_requests(self):
        import re
        root=runtime.ROOT
        catalogue=json.loads((root/'scripts/bruno-playground/catalogue.json').read_text())
        def normalize(path):
            path=path.split('?')[0]
            path=re.sub(r'\{\{[^}]+\}\}', '{}',path)
            return re.sub(r'\{[^}]*\}', '{}',path)
        declared=set()
        for entry in catalogue:
            text=(root/'scripts/bruno-playground'/entry['file']).read_text()
            method=re.search(r'\n(get|post) \{',text)[1].upper()
            url=re.search(r'  url: (.*)',text)[1]
            self.assertEqual(entry['method'],method)
            if url.startswith(('{{writeUrl}}','{{readUrl}}')):
                route=re.sub(r'^\{\{[^}]+\}\}', '',url)
                declared.add((method,normalize(route)))
        source=root/'app/supra-web-read/src/main/java/com/kartaguez/pocoma/supra/web/read/v3'
        public=set()
        for file in [source/'V3ReadController.java',source/'V3InvitationController.java',root/'app/supra-web-write/src/main/java/com/kartaguez/pocoma/supra/web/write/v3/V3AdmissionController.java']:
            text=file.read_text()
            for method,path in re.findall(r'@(Get|Post)Mapping\("([^"]+)"\)',text):
                if not path.startswith('/api/v3'):path='/api/v3'+path
                public.add((method.upper(),normalize(path)))
        self.assertEqual(12,len(public))
        self.assertTrue(public.issubset(declared),public-declared)

if __name__=='__main__':unittest.main()
