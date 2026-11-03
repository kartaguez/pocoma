"""Targeted complete Architecture module replay; does not silently replace a canonical/full gate."""
import importlib.util,json,sys
from pathlib import Path
root=Path(sys.argv[1]);output=Path(sys.argv[2]);spec=importlib.util.spec_from_file_location('verification',root/'scripts/testing/verify.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
prior=json.loads(Path('/private/tmp/pocoma-i8-FULL-04/plan.json').read_text());tests={name:methods for name,methods in prior['expected'].items() if prior['expected_modules'][name]=='pocoma-architecture-tests'}
assert sum(map(len,tests.values()))==167
config=json.loads(v.CONFIG.read_text());scope='L4-I8-complete-Architecture-oracle-correction'
config['campaigns'][scope]={'level':'INTEGRATION','modules':['architecture-tests'],'expected_invocations':167,'tests':tests,'forecast':{'seconds':120,'basis':'Complete Architecture module only, after test-only correction. No deadline; not a canonical gate without human dispensation.','uncertainty':'HIGH'}}
definition=v.plan('INTEGRATION',scope,config);definition['command']=['./mvnw','-pl','architecture-tests','-am','-Dtest='+','.join(sorted(tests)),'-Dsurefire.failIfNoSpecifiedTests=false','test']
original=v.control_inputs
def controls():
 result=original();result[str(Path(__file__).resolve())]=v.digest(Path(__file__));return result
v.control_inputs=controls
sys.exit(v.run(definition,output.resolve()))
