"""Real local OIDC callback protocol control, using a synthetic browser HTTP session."""
import argparse
from html.parser import HTMLParser
import http.cookiejar
import json
from pathlib import Path
import subprocess
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from unittest.mock import patch

ROOT=Path.cwd()
sys.path.insert(0,str(ROOT/'scripts/local'))
import auth
from public_api import PublicAPI


def execute(instance, output):
    path=ROOT/'.pocoma-local/instances'/instance
    manifest=json.loads((path/'manifest.json').read_text())
    credentials=json.loads((path/'secrets.json').read_text())
    observations={}
    failures=[]
    threads=[]
    class Form(HTMLParser):
        action=None
        def handle_starttag(self,tag,attributes):
            attrs=dict(attributes)
            if tag=='form' and attrs.get('id')=='kc-form-login':self.action=attrs.get('action')
    def browser(url):
        query=urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
        observations['challengeMethod']=query['code_challenge_method'][0]
        observations['hasState']=bool(query.get('state'))
        def session():
            try:
                # A forged state cannot complete the real listening callback.
                try:urllib.request.urlopen('http://127.0.0.1:8765/callback?code=fake&state=wrong',timeout=5)
                except urllib.error.HTTPError as error:observations['wrongStateStatus']=error.code
                cookies=http.cookiejar.CookieJar()
                opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
                with opener.open(url,timeout=10) as response:
                    page=response.read().decode();login_url=response.url
                form=Form();form.feed(page)
                target=urllib.parse.urljoin(login_url,form.action)
                assert urllib.parse.urlparse(target).netloc==urllib.parse.urlparse(manifest['issuer']).netloc
                data=urllib.parse.urlencode({'username':'third','password':credentials['users']['third']}).encode()
                cookie_header='; '.join(f'{c.name}={c.value}' for c in cookies)
                with opener.open(urllib.request.Request(target,data=data,headers={'Cookie':cookie_header}),timeout=10) as response:
                    observations['callbackStatus']=response.status
            except Exception as error:
                failures.append(type(error).__name__)
                output.with_suffix('.diagnostic.json').write_text(json.dumps({'observations':observations,'failures':failures}))
        t=threading.Thread(target=session,daemon=True);threads.append(t);t.start();return True
    with patch.object(auth.webbrowser,'open',side_effect=browser):
        token=auth.interactive(manifest['issuer'])
    for t in threads:t.join(15)
    assert not failures and not any(t.is_alive() for t in threads)
    api=PublicAPI(f'http://127.0.0.1:{manifest["ports"]["write"]}',f'http://127.0.0.1:{manifest["ports"]["read"]}',token)
    observations['resourceStatus']=api.call('GET','/me/binding')[0]
    assert observations==dict(challengeMethod='S256',hasState=True,wrongStateStatus=400,callbackStatus=200,resourceStatus=200)
    output.write_text(json.dumps(dict(verdict='PASS',testedSha=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        observations=observations,method='Real Keycloak form and actual interactive callback server; HTTP browser session automated; no GUI rendering or human login claimed.'),indent=2)+'\n')
    print('Interactive callback protocol PASS; no credentials or tokens persisted.')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--instance',required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();execute(a.instance,a.output)
