"""Prerequisite and credential failures on temporary state only; never use demo."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prerequisites as pre
import runtime as rt
import entrypoint

ROOT = rt.ROOT


def tree(path):
    return {str(p.relative_to(path)): (p.read_bytes(), p.stat().st_mode & 0o777)
            for p in path.rglob('*') if p.is_file()}


def fixture(path, deployed=False):
    path.mkdir(exist_ok=True)
    m = dict(schema=1, instance=path.name, owner='a'*36, project='pocoma-lb-' + path.name + '-' + 'a'*8,
             inputs='b'*64, sha='c'*40, state='NEW',
             ports=dict(zip(['write', 'read', 'issuer', *rt.RUNTIMES, 'metrics', 'grafana'], range(23000,23010))))
    rt.save(path/'manifest.json', m)
    data = rt.private(path, create=True)
    if deployed:
        m['images'] = {role:dict(digest=role+'@sha256:'+'d'*64) for role in rt.IMAGES}
        m['issuer'] = rt.render(path,m,data)
        with patch.object(rt,'pg_id',return_value='pg'), patch.object(rt,'docker_json',return_value=[{
                'NetworkSettings':{'Networks':{m['project']+'_default':{'IPAddress':'172.19.0.2'}}}}]):
            rt.properties(path,m,data)
        hashes = {}
        for module in {v[0] for v in rt.RUNTIMES.values()}:
            (path/(module+'.jar')).write_bytes(module.encode())
            hashes[module] = hashlib.sha256(module.encode()).hexdigest()
        m.update(state='RUNNING',build=dict(jars=hashes,sha='c'*40))
        rt.save(path/'manifest.json',m)
    return m, data


class PythonGuard(unittest.TestCase):
    def guard(self, version):
        stream = io.StringIO()
        with patch.object(sys,'version_info',version), contextlib.redirect_stderr(stream), \
             patch.dict(sys.modules, {'runtime':None}):
            code=entrypoint.main()
        return code, stream.getvalue()

    def test_old_interpreter_refused_before_runtime_import(self):
        code,text=self.guard((3,9,6))
        self.assertEqual(2,code)
        for part in ('Python incompatible','Required: Python >= 3.11','Detected: Python 3.9.6','Action:'):
            self.assertIn(part,text)

    def test_minimum_and_newer_interpreters_reach_runtime(self):
        for version in ((3,11,0),(3,14,7)):
            with patch.object(sys,'version_info',version),patch.object(rt,'main',return_value=17) as main:
                self.assertEqual(17,entrypoint.main())
                main.assert_called_once()

    def test_missing_python_returns_explicit_shell_exit_without_machine_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            result=subprocess.run(['/bin/bash',str(ROOT/'pocoma-local'),'--help'],
                                  env={**os.environ,'PATH':directory},capture_output=True,text=True)
        self.assertEqual(2,result.returncode)
        self.assertIn('Python unavailable',result.stderr)
        self.assertIn('Action:',result.stderr)

    def test_help_does_not_probe_java_node_docker_or_create_state(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(rt,'HOME_LOCAL',Path(directory)/'state'), \
             patch.object(pre,'tools') as tools, contextlib.redirect_stdout(io.StringIO()), \
             patch.object(sys,'argv',['pocoma-local','--help']):
            with self.assertRaises(SystemExit) as error: rt.main()
            self.assertEqual(0,error.exception.code)
            tools.assert_not_called()
            self.assertFalse(rt.HOME_LOCAL.exists())

    def test_entrypoint_parses_with_python_35_grammar(self):
        import ast
        ast.parse((ROOT/'scripts/local/entrypoint.py').read_text(),feature_version=(3,5))


class ToolChecks(unittest.TestCase):
    def test_absent_tool_is_named_with_action_and_exit_contract(self):
        with patch.object(pre.shutil,'which',return_value=None):
            with self.assertRaises(pre.Failure) as error:pre.probe('docker',[], 'Docker CLI','Install Docker')
        self.assertEqual(2,error.exception.code)
        self.assertIn('docker',error.exception.diagnostic['detected'])
        self.assertEqual('Install Docker',error.exception.diagnostic['recommendedAction'])

    def test_process_errors_and_timeouts_do_not_leak_output(self):
        for response in (subprocess.CompletedProcess([],1,'secret','bearer secret'),
                         OSError('secret'),subprocess.TimeoutExpired([],20,output='secret')):
            with patch.object(pre.shutil,'which',return_value='/tool'), patch.object(pre.subprocess,'run') as run:
                if isinstance(response,Exception):run.side_effect=response
                else:run.return_value=response
                with self.assertRaises(pre.Failure) as error:pre.probe('docker',[],'Docker daemon','Start Docker')
            self.assertNotIn('secret',json.dumps(error.exception.diagnostic))

    def test_incompatible_java_and_node_versions_are_explicit(self):
        for tool,value,expected in [('java','openjdk version "17.0.9"',21),('node','v20.19.0',24),('javac','javac 17.0.9',21)]:
            with patch.object(pre,'probe',return_value=value), self.assertRaises(pre.Failure) as error:
                pre.major(tool,[],expected)
            self.assertEqual('PRE-TOOL-VERSION',str(error.exception))
            self.assertIn(str(expected),error.exception.diagnostic['required'])
            self.assertIn('17' if tool!='node' else '20',error.exception.diagnostic['detected'])

    def test_supported_java_node_and_compiler_formats(self):
        for tool,value,expected in [('java','openjdk version "21" 2023-09-19',21),('node','v24.15.0',24),('javac','javac 21',21)]:
            with patch.object(pre,'probe',return_value=value):self.assertEqual(value,pre.major(tool,[],expected))

    def tool_root(self,root,cli=False):
        local=root/'scripts/local';local.mkdir(parents=True)
        (local/'toolchain.json').write_bytes((ROOT/'scripts/local/toolchain.json').read_bytes())
        (root/'app').mkdir();(root/'app/mvnw').write_text('#!/bin/sh\n');(root/'app/mvnw').chmod(0o700)
        if cli:
            path=root/'scripts/bruno-v3/node_modules/.bin/bru';path.parent.mkdir(parents=True);path.write_text('cli')

    def response(self,tool,args,*a,**kw):
        if tool=='node':return 'v24.15.0'
        if str(tool).endswith('javac'):return 'javac 21'
        if str(tool).endswith('java'):return 'openjdk version "21"'
        if tool=='npm':return '12.0.2'
        if str(tool).endswith('/bru'):return '4.3.0'
        return 'valid'

    def test_daemon_and_compose_unavailable_are_distinguished(self):
        for failing,required in [('info','Docker daemon'),('compose','Compose plugin')]:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);self.tool_root(root)
                def response(tool,args,want,action,**kw):
                    if tool=='docker' and args[0]==failing:
                        pre.reject('PRE-TOOL-FAILED',want,'probe failed',action)
                    return self.response(tool,args,want,action,**kw)
                with patch.object(pre,'probe',side_effect=response),self.assertRaises(pre.Failure) as error:
                    pre.tools(root,'start')
                self.assertIn(required,error.exception.diagnostic['required'])

    def test_status_stop_gui_destroy_and_reset_plan_require_no_java_node_npm(self):
        for command in ('status','stop','gui','login','destroy','reset'):
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);self.tool_root(root)
                with patch.object(pre,'probe',side_effect=self.response) as probe:
                    pre.tools(root,command,dry_run=True)
                self.assertFalse({'java','javac','node','npm'} & {str(c.args[0]) for c in probe.call_args_list})

    def test_npm_is_optional_with_valid_local_cli_and_maven_system_never_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root,cli=True)
            with patch.object(pre,'probe',side_effect=self.response) as probe:
                pre.tools(root,'start')
            names={str(c.args[0]) for c in probe.call_args_list}
            self.assertNotIn('npm',names);self.assertNotIn('mvn',names)

    def test_bruno_requires_no_jdk(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            with patch.object(pre,'probe',side_effect=self.response) as probe,patch.dict(os.environ,{},clear=True):
                pre.tools(root,'bruno')
            self.assertFalse({'java','javac','mvn'} & {str(c.args[0]) for c in probe.call_args_list})

    def test_java_home_effective_runtime_and_compiler_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            with patch.dict(os.environ,{'JAVA_HOME':'/selected-jdk'},clear=True), \
                 patch.object(pre,'probe',side_effect=self.response) as probe:
                pre.tools(root,'start')
            names={str(c.args[0]) for c in probe.call_args_list}
            self.assertTrue({'java','/selected-jdk/bin/java','/selected-jdk/bin/javac'}.issubset(names))

    def test_java_home_mismatch_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            def response(tool,*args,**kw):
                return 'openjdk version "17"' if str(tool)=='/wrong/bin/java' else self.response(tool,*args,**kw)
            with patch.dict(os.environ,{'JAVA_HOME':'/wrong'},clear=True), \
                 patch.object(pre,'probe',side_effect=response),self.assertRaises(pre.Failure) as error:
                pre.tools(root,'start')
            self.assertEqual('PRE-TOOL-VERSION',str(error.exception))

    def test_invalid_npm_version_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            def response(tool,*args,**kw):return 'invalid' if tool=='npm' else self.response(tool,*args,**kw)
            with patch.object(pre,'probe',side_effect=response),self.assertRaises(pre.Failure) as error:
                pre.tools(root,'bruno')
            self.assertEqual('PRE-NPM-VERSION',str(error.exception))

    def test_old_npm_cannot_prepare_lockfile_v3(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            def response(tool,*args,**kw):return '6.14.0' if tool=='npm' else self.response(tool,*args,**kw)
            with patch.object(pre,'probe',side_effect=response),self.assertRaises(pre.Failure) as error:
                pre.tools(root,'bruno')
            self.assertIn('npm >= 7',error.exception.diagnostic['required'])
            self.assertIn('6',error.exception.diagnostic['detected'])

    def test_npm_configuration_omitting_cli_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            def response(tool,args,*rest,**kw):
                return 'dev' if tool=='npm' and 'config' in args else self.response(tool,args,*rest,**kw)
            with patch.object(pre,'probe',side_effect=response),self.assertRaises(pre.Failure) as error:
                pre.tools(root,'bruno')
            self.assertEqual('PRE-NPM-DEV-DEPS',str(error.exception))

    def test_cached_start_does_not_require_compiler_or_java_home(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root,cli=True)
            with patch.object(pre,'probe',side_effect=self.response) as probe:
                pre.tools(root,'start',build_required=False)
            names={str(c.args[0]) for c in probe.call_args_list}
            self.assertIn('java',names);self.assertNotIn('javac',names)

    def test_unsupported_compose_configuration_is_refused_without_creating_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            before=tree(root)
            def response(tool,args,want,action,**kw):
                if tool=='docker' and 'config' in args:
                    self.assertIn('pocoma-probe:unused',kw['input'])
                    pre.reject('PRE-TOOL-FAILED',want,'unsupported configuration',action)
                return self.response(tool,args,want,action,**kw)
            with patch.object(pre,'probe',side_effect=response),self.assertRaises(pre.Failure):
                pre.tools(root,'start')
            self.assertEqual(before,tree(root))

    def test_missing_or_non_executable_wrapper_refused_for_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root);(root/'app/mvnw').chmod(0o600)
            with patch.object(pre,'probe',side_effect=self.response),self.assertRaises(pre.Failure) as error:
                pre.tools(root,'start')
            self.assertEqual('PRE-WRAPPER',str(error.exception))

    def test_java_environment_variables_are_isolated_not_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            injected={'MAVEN_OPTS':'secret','JAVA_TOOL_OPTIONS':'-Xmx64m','_JAVA_OPTIONS':'secret2','PATH':'/usr/bin'}
            with patch.dict(os.environ,injected,clear=True), \
                 patch.object(pre,'probe',side_effect=self.response):
                pre.tools(root,'start')
                cleaned=pre.clean_toolchain_env()
            for var in pre.TOOLCHAIN_ENV_VARS:
                self.assertNotIn(var,cleaned)
            self.assertEqual('/usr/bin',cleaned['PATH'])

    def test_node_accepts_minimum_major_and_rejects_older(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.tool_root(root)
            for node_ver in ('v22.14.0','v24.15.0','v25.1.0'):
                def ok_resp(tool,args,*a,**kw):
                    return node_ver if tool=='node' else self.response(tool,args,*a,**kw)
                with patch.object(pre,'probe',side_effect=ok_resp):
                    pre.tools(root,'start')
            def old_resp(tool,args,*a,**kw):
                return 'v20.19.0' if tool=='node' else self.response(tool,args,*a,**kw)
            with patch.object(pre,'probe',side_effect=old_resp),self.assertRaises(pre.Failure) as error:
                pre.tools(root,'start')
            self.assertEqual('PRE-TOOL-VERSION',str(error.exception))


class InstanceChecks(unittest.TestCase):
    def validate(self,path,command='start'):
        return pre.instance(path,command,rt.RUNTIMES,rt.ROLES,rt.LOCAL_USERS)

    def test_virgin_is_recognized_without_creating_any_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'fresh'
            self.assertIsNone(self.validate(path));self.assertFalse(path.exists())

    def test_initial_creation_requires_explicit_permission(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            with self.assertRaises(pre.Failure):rt.private(path)
            self.assertEqual({},tree(path))
            data=rt.private(path,create=True)
            self.assertEqual(data,rt.private(path))
            self.assertEqual(0o600,(path/'secrets.json').stat().st_mode & 0o777)

    def test_existing_valid_instance_reuses_exact_secret_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test';fixture(path,deployed=True)
            before=tree(path)
            self.validate(path);first=rt.private(path);second=rt.private(path,create=True)
            self.assertEqual(first,second);self.assertEqual(before,tree(path))

    def test_new_manifest_missing_secrets_is_partial_even_before_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test';fixture(path);(path/'secrets.json').unlink()
            before=tree(path)
            with self.assertRaises(pre.Failure) as error:self.validate(path)
            self.assertEqual('PRE-SECRETS-MISSING',str(error.exception));self.assertEqual(before,tree(path))

    def test_initialized_missing_secrets_never_regenerates_even_when_create_requested(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test';fixture(path,deployed=True);(path/'secrets.json').unlink()
            before=tree(path)
            for create in (False,True):
                with self.assertRaises(pre.Failure):rt.private(path,create=create)
            self.assertEqual(before,tree(path))

    def test_existing_files_without_manifest_are_not_adopted(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);(path/'secrets.json').write_text('private fixture')
            before=tree(path)
            with self.assertRaises(pre.Failure) as error:self.validate(path)
            self.assertEqual('PRE-PARTIAL-INSTANCE',str(error.exception));self.assertEqual(before,tree(path))

    def test_invalid_and_incomplete_secrets_do_not_change_any_bytes_or_echo_values(self):
        for kind in ('json','missing-user','missing-key','bad-key','bad-password','invalid-extra-password'):
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'test';m,data=fixture(path)
                if kind=='missing-user':del data['users']['guest']
                if kind=='missing-key':del data['invitation']
                if kind=='bad-key':data['lookup']='secret-invalid-key'
                if kind=='bad-password':data['passwords']['pocoma']='secret-invalid-password'
                if kind=='invalid-extra-password':data['passwords']['extra']=False
                rt.save(path/'secrets.json',data)
                if kind=='json':(path/'secrets.json').write_text('secret-invalid-json')
                before=tree(path)
                with self.assertRaises(pre.Failure) as error:self.validate(path)
                self.assertNotIn('secret-invalid',str(error.exception.diagnostic))
                self.assertEqual(before,tree(path))

    def test_invalid_ports_or_manifest_refused_without_modification(self):
        for kind in ('port','identity','structure'):
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'test';m,_=fixture(path)
                if kind=='port':m['ports']['read']=m['ports']['write']
                if kind=='identity':m['project']='other'
                if kind=='structure':m=[]
                rt.save(path/'manifest.json',m);before=tree(path)
                with self.assertRaises(pre.Failure):self.validate(path)
                self.assertEqual(before,tree(path))

    def test_missing_existing_configuration_is_refused(self):
        for name in ('compose.json','http-write.properties','web-write.jar','realm.json'):
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'test';fixture(path,deployed=True);(path/name).unlink();before=tree(path)
                with self.assertRaises(pre.Failure):self.validate(path)
                self.assertEqual(before,tree(path))

    def test_inconsistent_configuration_refused_without_rewriting(self):
        for kind in ('pg-password','realm','properties','compose','issuer','jar'):
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'test';m,_=fixture(path,deployed=True)
                if kind=='pg-password':(path/'pg-password').write_text('private-wrong')
                if kind=='realm':
                    data=rt.read(path/'realm.json');data['users'][0]['credentials'][0]['value']='private-wrong';rt.save(path/'realm.json',data)
                if kind=='properties':
                    p=path/'http-write.properties';p.write_text(p.read_text().replace('pocoma.v3.http.admission.lookup-key=', 'pocoma.v3.http.admission.wrong-key='))
                if kind=='compose':
                    data=rt.read(path/'compose.json');data['services']['keycloak']['ports']=['127.0.0.1:1:8080'];rt.save(path/'compose.json',data)
                if kind=='issuer':m['issuer']='http://wrong';rt.save(path/'manifest.json',m)
                if kind=='jar':(path/'web-write.jar').write_bytes(b'wrong')
                before=tree(path)
                with self.assertRaises(pre.Failure) as error:self.validate(path)
                self.assertNotIn('private-wrong',str(error.exception.diagnostic));self.assertEqual(before,tree(path))

    def test_safe_stop_does_not_require_lost_secrets_or_java(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test';fixture(path);(path/'secrets.json').unlink()
            self.assertEqual('NEW',self.validate(path,'stop')['state'])

    def test_sources_are_validated_without_loading_or_mutating_bootstrap(self):
        before=tree(ROOT/'scripts/local')
        pre.sources(ROOT,rt.RUNTIMES)
        self.assertEqual(before,tree(ROOT/'scripts/local'))

    def test_missing_source_input_is_named_before_any_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);before=tree(root)
            with self.assertRaises(pre.Failure) as error:pre.sources(root,rt.RUNTIMES)
            self.assertIn('setup-v3-database.py',error.exception.diagnostic['required'])
            self.assertEqual(before,tree(root))

    def test_explicit_reset_does_not_require_old_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'test';fixture(path);(path/'secrets.json').unlink()
            self.assertEqual('NEW',self.validate(path,'reset')['state'])


class Ordering(unittest.TestCase):
    def main_result(self,home):
        stream=io.StringIO()
        with patch.object(rt,'HOME_LOCAL',home),patch.object(sys,'argv',['pocoma-local','start','--instance','isolated']),contextlib.redirect_stdout(stream):
            code=rt.main()
        return code,json.loads(stream.getvalue())

    def test_missing_tool_refuses_before_directory_manifest_secrets_or_mutators(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'state'
            with patch.object(pre,'sources'),patch.object(pre,'tools',side_effect=pre.Failure('PRE-TOOL-MISSING','Docker','absent','Install Docker')), \
                 patch.object(rt,'private') as private,patch.object(rt,'compose') as compose,patch.object(rt,'package') as package:
                code,result=self.main_result(home)
            self.assertEqual(2,code);self.assertEqual('PRE-TOOL-MISSING',result['control'])
            self.assertIn('diagnostic',result);self.assertIsNone(result['evidence']);self.assertFalse(home.exists())
            private.assert_not_called();compose.assert_not_called();package.assert_not_called()

    def test_resource_refusal_before_any_instance_write(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'state'
            with patch.object(pre,'sources'),patch.object(pre,'tools'),patch.object(rt,'source_hash',return_value='b'*64), \
                 patch.object(rt,'preflight',side_effect=rt.Refusal('PRE-RESOURCES: Docker >=6GiB')):
                code,result=self.main_result(home)
            self.assertEqual(2,code);self.assertFalse(home.exists())

    def test_missing_secret_refuses_before_tools_locks_diagnostics_or_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'state';path=home/'instances/isolated';path.mkdir(parents=True)
            fixture(path);(path/'secrets.json').unlink();before=tree(home)
            with patch.object(pre,'tools') as tools,patch.object(rt,'diagnostics') as diagnostics,patch.object(rt,'private') as private:
                code,result=self.main_result(home)
            self.assertEqual(2,code);self.assertEqual('PRE-SECRETS-MISSING',result['control'])
            self.assertEqual(before,tree(home));tools.assert_not_called();diagnostics.assert_not_called();private.assert_not_called()

    def test_failed_start_keeps_existing_secret_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'state';path=home/'instances/isolated';path.mkdir(parents=True)
            fixture(path);before=(path/'secrets.json').read_bytes()
            with patch.object(rt,'prepare'),patch.object(rt,'start',side_effect=RuntimeError('fixture failure')), \
                 patch.object(rt,'diagnostics'),patch.object(rt,'run',return_value=''):
                code,result=self.main_result(home)
            self.assertEqual(1,code);self.assertEqual(before,(path/'secrets.json').read_bytes())


if __name__=='__main__':unittest.main()
