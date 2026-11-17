"""Temporary receipt: two historical oracles affected by the diagnosed I8 assembly-order regression."""
import importlib.util,json,sys
from pathlib import Path
root=Path(sys.argv[1]);output=Path(sys.argv[2]);spec=importlib.util.spec_from_file_location('verification',root/'scripts/testing/verify.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
config=json.loads(v.CONFIG.read_text());scope='L4-I8-historical-order-correction'
config['campaigns'][scope]={'level':'INTEGRATION','modules':['runtime-command-consumption-worker'],'expected_invocations':2,'tests':{'com.kartaguez.pocoma.runtime.command.v3.V3WorkerPostgresTest':['i5_w04'],'com.kartaguez.pocoma.runtime.command.v3.V3WorkerResourcesPostgresTest':['i5_r06']},'forecast':{'seconds':60,'basis':'Affected historical capacity and wire-fault oracles after restoring the original assembly order; no deadline','uncertainty':'HIGH'}}
definition=v.plan('INTEGRATION',scope,config);definition['command']=['./mvnw','-pl','runtime-command-consumption-worker','-am','-Dtest=V3WorkerPostgresTest#i5_w04,V3WorkerResourcesPostgresTest#i5_r06','-Dsurefire.failIfNoSpecifiedTests=false','test']
original=v.control_inputs
def controls():
 result=original();result[str(Path(__file__).resolve())]=v.digest(Path(__file__));return result
v.control_inputs=controls
sys.exit(v.run(definition,output.resolve()))
