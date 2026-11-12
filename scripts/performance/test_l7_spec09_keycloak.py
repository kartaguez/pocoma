"""Offline checks for Keycloak F01-F fixture, observables and reconciliation."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import time
import unittest
from unittest.mock import Mock
import uuid

SCRIPT = Path(__file__).with_name("run-l7-spec09-keycloak.py")
SPEC = importlib.util.spec_from_file_location("l7_spec09_keycloak", SCRIPT)
bench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bench)
ROOT = SCRIPT.resolve().parents[2]
REALM = "pocoma-spec09-0123abcd"
REG = str(uuid.UUID(int=1))
UNLINK = str(uuid.UUID(int=2))


class KeycloakContract(unittest.TestCase):
    def test_realm_has_only_unique_test_subjects_and_keeps_pkce(self):
        template = json.loads((ROOT/"docker/keycloak/pocoma-realm.json").read_text())
        subjects = [str(uuid.UUID(int=i)) for i in range(1,781)]
        choices = iter(f"opaque-fixture-{i}" for i in range(len(subjects)))
        realm, actors = bench.prepare(template, subjects, REALM, lambda _: next(choices))
        self.assertEqual(subjects, [u["id"] for u in realm["users"]])
        self.assertEqual(len(subjects), len({a["password"] for a in actors}))
        self.assertEqual("S256",realm["clients"][0]["attributes"]["pkce.code.challenge.method"])
        self.assertFalse(realm["clients"][0]["directAccessGrantsEnabled"])
        self.assertNotEqual(template["users"],realm["users"])

    def test_login_and_exchange_times_are_separate_and_sub_must_match(self):
        auth = SimpleNamespace(
            authorize=Mock(return_value=({"code":["sample"]},"pkce")),
            exchange=Mock(return_value=("opaque-token",{"sub":REG})),
            check_token=Mock(),
        )
        account = {"subject":REG,"username":"isolated","password":"fixture-only"}
        clock = iter([0,19_000_000,23_000_000]).__next__
        _, facts = bench.login(auth,account,clock)
        self.assertEqual({"authorizeMs":19,"exchangeMs":4},facts)
        auth.check_token.assert_called_once_with({"sub":REG},set())
        with self.assertRaisesRegex(ValueError,"subject"):
            bench.login(auth,{**account,"subject":UNLINK},iter([0,1,2]).__next__)

    def test_old_binding_must_be_denied_after_unlink(self):
        def run(old_status):
            now = time.monotonic_ns()
            transport = Mock(side_effect=[
                (202,{"requestId":UNLINK},False,now+100_000_000),
                (200,{"active":False,"euiVersion":2},False,now+200_000_000),
                (old_status,None,False,now+300_000_000),
            ])
            identity = SimpleNamespace(http_call=transport,POLL_DEADLINE_SECONDS=8)
            result = bench.unlink(identity,"write","read","jwt","old", "issuer",REG,"key")
            self.assertEqual({"issuer":"issuer","subject":REG},transport.call_args_list[0].kwargs["body"]["target"])
            self.assertEqual("old",transport.call_args_list[-1].kwargs["binding"])
            return result
        result = run(404)
        self.assertEqual("UNLINK_OBSERVED",result["outcome"])
        self.assertEqual(200,result["lowerMs"])
        self.assertNotIn("old",json.dumps(result))
        self.assertEqual("OLD_BINDING_ACCEPTED",run(200)["outcome"])

    def test_reconciliation_requires_matching_completed_pairs(self):
        rows = [{"registration":{"requestId":REG},"unlink":{"requestId":UNLINK}}]
        expected = {"pairs":1,"euis":1}
        sql = Mock(side_effect=[expected, {"pairs":0,"euis":0}, expected, expected])
        identity = SimpleNamespace(observe_sql=sql)
        self.assertFalse(bench.reconcile(identity,"write","read",rows)["complete"])
        self.assertTrue(bench.reconcile(identity,"write","read",rows)["complete"])
        self.assertEqual(["v3_write","v3_read"]*2,[call.args[1] for call in sql.call_args_list])
        self.assertFalse(bench.reconcile(identity,"write","read",rows*2)["complete"])

    def test_issuer_must_identify_a_disposable_realm(self):
        self.assertEqual(f"http://localhost/realms/{REALM}",
                         bench.validate_issuer(f"http://localhost/realms/{REALM}"))
        for value in ("http://localhost/realms/pocoma",f"http://foo@localhost/realms/{REALM}",
                      f"http://localhost/realms/{REALM}?credential=secret"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                bench.validate_issuer(value)


if __name__ == "__main__":
    unittest.main()
