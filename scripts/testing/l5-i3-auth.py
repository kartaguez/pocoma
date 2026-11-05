#!/usr/bin/env python3
"""Obtain three independent real Keycloak sessions using the existing S256 fixture."""
import importlib.util,json,os
from pathlib import Path
spec=importlib.util.spec_from_file_location('auth',Path(__file__).resolve().parents[1]/'functional-auth-proof.py')
auth=importlib.util.module_from_spec(spec);spec.loader.exec_module(auth)
result={}
for name,username,password in [('E1','bruno','bruno-local-only'),('E2','link-e2','link-e2-local-only'),('E3','link-e3','link-e3-local-only')]:
 values,verifier=auth.authorize(['openid'],username=username,password=password)
 token,claims=auth.exchange(values,verifier);auth.check_token(claims,set())
 result[name]={'token':token,'issuer':claims['iss'],'subject':claims['sub'],'iat':claims['iat'],'exp':claims['exp'],'auth_time':claims['auth_time'],'aud':claims['aud']}
assert len({(v['issuer'],v['subject']) for v in result.values()})==3
output=Path(os.environ['POCOMA_I3_AUTH_OUTPUT'])
with open(output,'x',opener=lambda path,flags:os.open(path,flags,0o600)) as stream:json.dump(result,stream)
