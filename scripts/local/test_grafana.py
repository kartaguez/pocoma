"""Grafana contract, negative readiness and non-destructive upgrade controls."""
import copy
import hashlib
import json
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import grafana as gf
import runtime as rt
from test_grafana_installation import verify_external


class GrafanaContract(unittest.TestCase):
    def test_external_inventory_distinguishes_live_attached_tsdb_and_detects_real_loss(self):
        before=dict(containers={'other':dict(state='exited')},volumes={'outside':'same','prometheus':'old'})
        after=dict(containers=copy.deepcopy(before['containers']),volumes={'outside':'same','prometheus':'new','added':'new'})
        verify_external(before,after,{'prometheus':'prometheus'})
        after['volumes']['outside']='changed'
        with self.assertRaisesRegex(AssertionError,'external volume changed'):
            verify_external(before,after,{'prometheus':'prometheus'})
        del after['volumes']['prometheus']
        after['volumes']['outside']='same'
        with self.assertRaisesRegex(AssertionError,'volume disappeared'):
            verify_external(before,after,{'prometheus':'prometheus'})

    def fixture(self, path):
        m = dict(instance='test', owner='owner', project='project', state='STOPPED', inputs='old',
                 ports=rt.allocate_ports(), images={role: dict(digest=role) for role in rt.IMAGES})
        rt.render(path, m, rt.private(path, create=True))
        return m

    def test_ten_distinct_loopback_ports_including_grafana(self):
        ports = rt.allocate_ports()
        self.assertEqual(10, len(ports))
        self.assertEqual(10, len(set(ports.values())))
        self.assertIn('grafana', ports)
        for port in ports.values():
            with socket.socket() as sock:
                sock.bind(('127.0.0.1', port))

    def test_declarative_ten_service_t1a_nats_distribution_and_ephemeral_grafana(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory);m = self.fixture(path)
            compose = rt.read(path / 'compose.json')
            service = compose['services']['grafana']
            self.assertEqual(10, len(compose['services']))
            self.assertIn('nats', compose['services'])
            self.assertNotIn('ports', compose['services']['nats'])
            self.assertEqual('nats:2.15.3-alpine', rt.IMAGES['nats'])
            self.assertEqual('grafana/grafana:12.3.2', rt.IMAGES['grafana'])
            self.assertEqual({'data'}, set(compose['volumes']))
            self.assertEqual({rt.LABEL: m['owner']}, service['labels'])
            self.assertEqual([f'127.0.0.1:{m["ports"]["grafana"]}:3000'], service['ports'])
            self.assertEqual('admin', service['environment']['GF_SECURITY_ADMIN_USER'])
            self.assertEqual('pocoma', service['environment']['GF_SECURITY_ADMIN_PASSWORD'])
            self.assertEqual('false', service['environment']['GF_AUTH_ANONYMOUS_ENABLED'])
            self.assertTrue(service['tmpfs'][0].startswith('/var/lib/grafana:'))
            self.assertEqual('384m', service['mem_limit'])
            self.assertTrue(all(mount.endswith(':ro') for mount in service['volumes']))
            datasource = (path/'grafana/provisioning/datasources/prometheus.yml').read_text()
            self.assertIn('url: http://prometheus:9090', datasource)
            self.assertIn('uid: Prometheus', datasource)
            provider = (path/'grafana/provisioning/dashboards/dashboards.yml').read_text()
            self.assertIn('path: /etc/grafana/dashboards', provider)
            for uid in gf.DASHBOARDS:
                self.assertTrue((path/'grafana/dashboards'/f'{uid}.json').exists())

    def test_l6_panels_reused_and_all_nested_datasources_and_jobs_adapted(self):
        data = gf.dashboards(rt.RUNTIMES)
        for uid, dashboard in data.items():
            original = json.loads((rt.ROOT/'docker/grafana/dashboards'/f'{uid}.json').read_text())
            self.assertEqual([p['title'] for p in original['panels']], [p['title'] for p in dashboard['panels']])
            for panel in dashboard['panels']:
                self.assertEqual('Prometheus', panel['datasource']['uid'])
                for target in panel['targets']:
                    self.assertEqual('Prometheus', target['datasource']['uid'])
                    self.assertNotIn(gf.LEGACY_JOBS, target['expr'])
        self.assertIn('http-write|http-read|command-worker|critical-read-worker|pot-read-worker',
                      data['pocoma-runtime']['panels'][0]['targets'][0]['expr'])

    def test_projection_panel_uses_exported_catalog_names(self):
        data=gf.dashboards(rt.RUNTIMES)['pocoma-v3-overview']
        panel=next(p for p in data['panels'] if p['title']=='Projection Throughput (tasks/s)')
        expression=panel['targets'][0]['expr']
        self.assertIn('PotVersion|PotBalance|PotVisibility|CurrentBinding|CommandResult|IdentityRequestResult',expression)
        self.assertNotIn('Materialize',expression)

    def healthy_get(self, m, route):
        if route == '/api/health': return dict(database='ok', version='12.3.2')
        if route == '/api/user': return dict(login='admin', isGrafanaAdmin=True)
        if route == '/api/datasources/uid/Prometheus':
            return dict(type='prometheus', url='http://prometheus:9090', uid='Prometheus', isDefault=True)
        if route.endswith('/health'): return dict(status='OK')
        if route.startswith('/api/dashboards/uid/'):
            return dict(meta=dict(provisioned=True), dashboard=gf.dashboards(rt.RUNTIMES)[route.rsplit('/',1)[1]])
        raise AssertionError(route)

    def healthy_query(self, m, expression):
        return [dict(metric=dict(job=name), value=[1, '1']) for name in rt.RUNTIMES]

    def checks(self, get=None, query=None):
        with patch.object(gf, 'get', side_effect=get or self.healthy_get), \
             patch.object(gf, 'query', side_effect=query or self.healthy_query):
            return gf.checks(dict(ports=dict(grafana=3000)), rt.RUNTIMES, rt.await_check, rt.save)

    def test_admin_datasource_real_runtime_metrics_and_both_dashboards_required(self):
        checks, diagnostics = self.checks()
        self.assertEqual(dict.fromkeys(('GRAFANA-HTTP','GRAFANA-DATASOURCE','GRAFANA-DASHBOARD'),'PASS'), checks)
        self.assertFalse(diagnostics)

    def test_http200_alone_cannot_make_grafana_ready(self):
        def get(m, route):
            if route not in ('/api/health','/api/user'): raise RuntimeError('missing provisioning')
            return self.healthy_get(m, route)
        checks, diagnostics = self.checks(get=get)
        self.assertEqual('PASS', checks['GRAFANA-HTTP'])
        self.assertEqual('FAIL', checks['GRAFANA-DATASOURCE'])
        self.assertEqual('FAIL', checks['GRAFANA-DASHBOARD'])
        self.assertEqual('GRAFANA_DATASOURCE_UNAVAILABLE', diagnostics['GRAFANA-DATASOURCE']['code'])
        self.assertEqual('NOT_READY', rt.verdict(checks,True,'AVAILABLE')['state'])

    def test_missing_one_runtime_or_down_target_fails_datasource(self):
        for metric in ('up','jvm_memory_used_bytes','process_cpu_usage','count({__name__=~"pocoma_.+"}) by (job)'):
            def query(m, expression):
                rows = self.healthy_query(m, expression)
                return rows[:-1] if expression == metric else rows
            checks, _ = self.checks(query=query)
            self.assertEqual('FAIL', checks['GRAFANA-DATASOURCE'], metric)

    def test_wrong_admin_wrong_internal_url_wrong_dashboard_and_promql_error_fail(self):
        for route, replacement, check in (
            ('/api/user', dict(login='admin',isGrafanaAdmin=False), 'GRAFANA-HTTP'),
            ('/api/datasources/uid/Prometheus', dict(type='prometheus',url='http://localhost:9090',isDefault=True), 'GRAFANA-DATASOURCE'),
            ('/api/dashboards/uid/pocoma-runtime', dict(meta=dict(provisioned=False),dashboard=gf.dashboards(rt.RUNTIMES)['pocoma-runtime']), 'GRAFANA-DASHBOARD')):
            def get(m, actual): return replacement if actual == route else self.healthy_get(m,actual)
            checks, _ = self.checks(get=get)
            self.assertEqual('FAIL', checks[check])
        def query(m, expression):
            if expression.startswith('sum(rate('): raise RuntimeError('invalid PromQL')
            return self.healthy_query(m,expression)
        checks, _ = self.checks(query=query)
        self.assertEqual('FAIL', checks['GRAFANA-DASHBOARD'])

    def test_idle_panels_are_classified_without_faking_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            def query(m, expr):
                if expr.startswith('sum(rate('):return []
                if expr.startswith('histogram_quantile'):return [dict(metric={},value=[1,'NaN'])]
                if expr.startswith('pocoma_v3_capacity'):return [dict(metric={},value=[1,'0'])]
                return self.healthy_query(m,expr)
            with patch.object(gf,'get',side_effect=self.healthy_get),patch.object(gf,'query',side_effect=query):
                checks,_=gf.checks({},rt.RUNTIMES,rt.await_check,rt.save,path)
            self.assertTrue(all(v=='PASS' for v in checks.values()))
            states={p['state'] for p in rt.read(path/'grafana.json')['panels']}
            self.assertEqual({'PRESENT','ABSENT','ZERO','NONFINITE'},states)

    def test_mandatory_grafana_failure_does_not_misreport_business_unavailability(self):
        result=rt.verdict({'DB':'PASS','GRAFANA-DATASOURCE':'FAIL'}, True, 'AVAILABLE')
        self.assertEqual('NOT_READY',result['state'])
        self.assertEqual('AVAILABLE',result['business'])
        self.assertEqual(1,result['exit'])

    def test_access_contract_additive_and_legacy_status_explains_failure(self):
        self.assertIsNone(gf.access(dict(ports={})))
        self.assertEqual('http://127.0.0.1:45678/',gf.access(dict(ports=dict(grafana=45678)))['url'])
        checks, diagnostics=gf.checks(dict(ports={}),rt.RUNTIMES,rt.await_check,rt.save)
        self.assertTrue(all(v=='FAIL' for v in checks.values()))
        self.assertIn('GRAFANA-HTTP',diagnostics)

    def test_upgrade_refuses_running_instance_before_mutation(self):
        with patch.object(rt,'ownership'),patch.object(rt,'prepare_images') as images:
            with self.assertRaisesRegex(rt.Refusal,'STOP_REQUIRED'):
                rt.upgrade_grafana(Path('/unused'),dict(ports={},state='RUNNING'),Path('/unused'))
            images.assert_not_called()

    def test_upgrade_protected_production_drift_refused_before_pull_or_write(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(rt,'ownership'), \
             patch.object(rt,'compose',return_value=''),patch.object(rt,'run',side_effect=['app/changed.java','']), \
             patch.object(rt,'prepare_images') as images:
            path=Path(directory)
            with self.assertRaisesRegex(rt.Refusal,'PRODUCTION_DRIFT'):
                rt.upgrade_grafana(path,dict(ports={},state='STOPPED',build=dict(sha='a'*40)),path)
            images.assert_not_called()
            self.assertTrue((path/'grafana-upgrade-refused.json').exists())

    def test_upgrade_refuses_modified_jar_before_pull(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(rt,'ownership'), \
             patch.object(rt,'compose',return_value=''),patch.object(rt,'run',return_value=''), \
             patch.object(rt,'prepare_images') as images:
            path=Path(directory);(path/'runtime.jar').write_bytes(b'modified')
            m=dict(ports={},state='STOPPED',build=dict(sha='a'*40,jars={'runtime':'unexpected'}))
            with self.assertRaisesRegex(rt.Refusal,'JAR_DRIFT'):rt.upgrade_grafana(path,m,path)
            images.assert_not_called()

    def test_upgrade_adds_only_grafana_preserves_secrets_ports_jars_and_drift_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);m=self.fixture(path)
            del m['ports']['grafana'];del m['images']['grafana']
            old=rt.read(path/'compose.json');del old['services']['grafana'];rt.save(path/'compose.json',old)
            (path/'web-write.jar').write_bytes(b'jar')
            m['build']=dict(sha='a'*40,jars={'web-write':hashlib.sha256(b'jar').hexdigest()})
            before=(path/'secrets.json').read_bytes();ports=dict(m['ports'])
            images=dict(m['images'],grafana=dict(digest='grafana'))
            with patch.object(rt,'ownership'),patch.object(rt,'compose',return_value=''), \
                 patch.object(rt,'run',return_value=''),patch.object(rt,'preflight',return_value='arm64'), \
                 patch.object(rt,'prepare_images',return_value=images),patch.object(rt,'source_hash',return_value='new'), \
                 patch.object(rt,'start',return_value=dict(state='READY')) as start:
                rt.upgrade_grafana(path,m,path)
            new=rt.read(path/'compose.json')
            self.assertEqual(old,dict(new,services={k:v for k,v in new['services'].items() if k!='grafana'}))
            self.assertEqual(before,(path/'secrets.json').read_bytes())
            self.assertEqual(ports,{k:v for k,v in m['ports'].items() if k!='grafana'})
            self.assertEqual('old',m['grafanaUpgrade']['previousInputs'])
            self.assertEqual('new',m['inputs']);start.assert_called_once()
            with patch.object(rt,'source_hash',return_value='unexpected'):
                with self.assertRaisesRegex(rt.Refusal,'PRE-INPUT-DRIFT'):rt.start(path,m,path)

    def test_grafana_in_stop_and_destroy_plan_without_extra_persistent_volume(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(rt,'ownership'), \
             patch.object(rt,'sql',return_value='0'),patch.object(rt,'compose',return_value='') as compose, \
             patch.object(rt,'unowned_attached_volumes',return_value=[]):
            path=Path(directory);m=self.fixture(path)
            rt.stop(path,m,path)
            self.assertIn('grafana',compose.call_args.args)
            plan=rt.destruction_plan(path,m)
            self.assertIn('grafana',plan['services'])
            self.assertEqual('project-data',plan['volume'])

    def test_stopped_installed_grafana_refresh_keeps_ports_and_existing_topology(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);m=self.fixture(path)
            (path/'web-write.jar').write_bytes(b'jar')
            m['build']=dict(sha='a'*40,jars={'web-write':hashlib.sha256(b'jar').hexdigest()})
            old=rt.read(path/'compose.json');ports=dict(m['ports'])
            with patch.object(rt,'ownership'),patch.object(rt,'compose',return_value=''), \
                 patch.object(rt,'run',return_value=''),patch.object(rt,'preflight',return_value='arm64'), \
                 patch.object(rt,'prepare_images',return_value=m['images']),patch.object(rt,'source_hash',return_value='new'), \
                 patch.object(rt,'start',return_value=dict(state='READY')) as start:
                rt.upgrade_grafana(path,m,path)
            self.assertEqual(old,rt.read(path/'compose.json'))
            self.assertEqual(ports,m['ports'])
            self.assertEqual('new',m['inputs'])
            self.assertEqual('REFRESH_PROVISIONING',m['grafanaUpgrade']['operation'])
            start.assert_called_once()

    def test_upgrade_config_or_topology_drift_preserves_manifest_and_refuses_start(self):
        for kind in ('config','topology'):
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory);m=self.fixture(path)
                (path/'web-write.jar').write_bytes(b'jar')
                m['build']=dict(sha='a'*40,jars={'web-write':hashlib.sha256(b'jar').hexdigest()})
                rt.save(path/'manifest.json',m)
                manifest=(path/'manifest.json').read_bytes()
                if kind=='config':(path/'realm.json').write_text('{}')
                else:
                    compose=rt.read(path/'compose.json');compose['services']['keycloak']['cpus']=9
                    rt.save(path/'compose.json',compose)
                with patch.object(rt,'ownership'),patch.object(rt,'compose',return_value=''), \
                     patch.object(rt,'run',return_value=''),patch.object(rt,'preflight',return_value='arm64'), \
                     patch.object(rt,'prepare_images',return_value=m['images']),patch.object(rt,'source_hash',return_value='new'), \
                     patch.object(rt,'start') as start:
                    with self.assertRaisesRegex(rt.Refusal,'GRAFANA_UPGRADE_(CONFIG|COMPOSE)_DRIFT'):
                        rt.upgrade_grafana(path,m,path)
                self.assertEqual(manifest,(path/'manifest.json').read_bytes())
                start.assert_not_called()


if __name__=='__main__':unittest.main()
