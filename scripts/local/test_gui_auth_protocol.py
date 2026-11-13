"""Real IdP sessions -> installed Bruno4.2.1 cache API -> full public PG01.

No UI rendering/human acceptance claim. No JWT file, argv or stdout.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
import runtime
from auth import login, prove_pkce


def execute(instance, output):
    with runtime.locked(instance) as path:
        m=runtime.load_manifest(path);runtime.ownership(m)
        credentials=runtime.private(path)
        owner=prove_pkce(m['issuer'],'third',credentials['users']['third'])
        guest=login(m['issuer'],'guest',credentials['users']['guest'])
        env={**os.environ,'ELECTRON_RUN_AS_NODE':'1','POCOMA_GUI_TEST_OWNER':owner,'POCOMA_GUI_TEST_GUEST':guest,
             'POCOMA_GUI_TEST_WRITE':runtime.urls(m)[0],'POCOMA_GUI_TEST_READ':runtime.urls(m)[1],
             'POCOMA_GUI_TEST_ISSUER':m['issuer'],'POCOMA_GUI_TEST_OUTPUT':str(output.resolve())}
        command=['/Applications/Bruno.app/Contents/MacOS/Bruno',str(runtime.ROOT/'scripts/local/test_bruno_gui.js')]
        result=subprocess.run(command,env=env,text=True,capture_output=True,timeout=180,cwd=runtime.ROOT)
        if result.returncode:
            raise RuntimeError('Installed Bruno4.2.1 protocol control failed; no secret/response dump.')
        receipt=json.loads(output.read_text());receipt.update(testedSha=runtime.run(['git','rev-parse','HEAD']),
            dirty=bool(runtime.run(['git','status','--porcelain'])),inputs=runtime.source_hash(),command=command,
            oidc='Real Keycloak: Authorization Code S256, missing/plain refused; private sessions passed in process environment only.')
        output.write_text(json.dumps(receipt,indent=2)+'\n')
        print('Bruno4.2.1 native-cache public PG01 PASS; no token persisted.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--instance',default='demo');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();execute(args.instance,args.output)
