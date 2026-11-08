#!/usr/bin/env python3
"""Pocoma V3 — Realistic Workload Scheduler Qualification Suite (scripts/performance/test_r5_scheduler.py).

Verifies Phase C requirements for L6/I3-R5:
 1. Mix determination (88% create, 10% correct, 2% rare).
 2. Strict reproducibility across identical seeds.
 3. Respect of confirmed version (no obsolete version emission).
 4. Absence of concurrent mutating commands on a single Pot.
 5. Corrections exclusively targeting previously applied expenses.
 6. Single correction per expense lifecycle.
 7. Single weight update per shareholder.
 8. Single attachment per shareholder.
 9. No reuse of exhausted rare operations.
10. Equitable round-robin distribution across Pots.
11. Ability to sustain 50 cmd/s delivery when pot pool is sufficient.
12. Resilient progress when one Pot is blocked / stalled.
13. Stop, pause, and resumption.
14. Mathematical command conservation.
15. Failure and timeout handling without pot lock leaks.
"""

from pathlib import Path
import sys
import unittest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "performance"))

from v3_realistic_workload import (
    ExpenseInfo,
    PotState,
    RealisticWorkloadScheduler,
    ShareholderInfo,
    WorkloadConfig,
    generate_deterministic_fixtures,
)


class RealisticWorkloadSchedulerTests(unittest.TestCase):

    def setUp(self):
        self.config = WorkloadConfig(
            target_rate=50.0,
            duration_sec=20.0,
            pot_pool_size=50,
            creation_ratio=0.88,
            correction_ratio=0.10,
            rare_ratio=0.02,
            random_seed="test-seed-42",
            shareholders_per_pot=5,
        )
        self.pots = generate_deterministic_fixtures(self.config)
        self.scheduler = RealisticWorkloadScheduler(self.config, self.pots)

    def test_01_mix_determination(self):
        """Simulates 1000 completed commands and checks that the realized mix closely matches 88/10/2."""
        for _ in range(1000):
            cmd = self.scheduler.next_command()
            self.assertIsNotNone(cmd)
            op = cmd["operation"]
            pot_id = cmd["potId"]
            cid = f"cmd-{_}"
            meta = cmd.get("_meta")
            self.scheduler.record_admission(cid, pot_id, op, 202, meta)
            # Simulate immediate terminal success
            self.scheduler.on_command_terminal(cid, pot_id, cmd["observedVersion"] + 1, "CHANGED")

        report = self.scheduler.get_mix_report()
        fam_map = {row["family"]: row for row in report["families"]}

        create_ratio = fam_map["CreateExpense"]["emittedRatio"]
        correct_ratio = fam_map["UpdateExpenseFinancials"]["emittedRatio"]
        rare_ratio = fam_map["UpdateShareholderWeights"]["emittedRatio"] + fam_map["UpdateShareholderDetails"]["emittedRatio"]

        self.assertAlmostEqual(create_ratio, 0.88, delta=0.03, msg=f"CreateExpense ratio {create_ratio} should be ~0.88")
        self.assertAlmostEqual(correct_ratio, 0.10, delta=0.03, msg=f"UpdateExpenseFinancials ratio {correct_ratio} should be ~0.10")
        self.assertAlmostEqual(rare_ratio, 0.02, delta=0.015, msg=f"Rare ops ratio {rare_ratio} should be ~0.02")

    def test_02_reproducibility(self):
        """Identical seeds must produce the exact same sequence of operations and payloads."""
        sched1 = RealisticWorkloadScheduler(self.config, generate_deterministic_fixtures(self.config))
        sched2 = RealisticWorkloadScheduler(self.config, generate_deterministic_fixtures(self.config))

        for i in range(100):
            c1 = sched1.next_command()
            c2 = sched2.next_command()
            self.assertEqual(c1["operation"], c2["operation"])
            self.assertEqual(c1["potId"], c2["potId"])
            self.assertEqual(c1["observedVersion"], c2["observedVersion"])
            self.assertEqual(c1["payload"], c2["payload"])
            # release both
            sched1.on_command_terminal(f"c1-{i}", c1["potId"], c1["observedVersion"] + 1, "CHANGED")
            sched2.on_command_terminal(f"c2-{i}", c2["potId"], c2["observedVersion"] + 1, "CHANGED")

    def test_03_respect_current_version(self):
        """Commands must use the exact confirmed version of the targeted Pot."""
        pot = self.pots[0]
        pot.confirmed_version = 42

        # Make other pots busy so pot 0 is picked
        for p in self.pots[1:]:
            p.in_flight = True

        cmd = self.scheduler.next_command()
        self.assertEqual(cmd["potId"], pot.pot_id)
        self.assertEqual(cmd["observedVersion"], 42)

    def test_04_absence_of_concurrent_commands_on_pot(self):
        """When a Pot has an in-flight command, the scheduler must never emit another command on it."""
        config = WorkloadConfig(pot_pool_size=3, random_seed="small-pool")
        pots = generate_deterministic_fixtures(config)
        sched = RealisticWorkloadScheduler(config, pots)

        c1 = sched.next_command()
        c2 = sched.next_command()
        c3 = sched.next_command()

        targeted_pots = {c1["potId"], c2["potId"], c3["potId"]}
        self.assertEqual(len(targeted_pots), 3, "Each of the 3 commands must target a distinct Pot")

        # 4th command should return None since all 3 pots are in-flight
        c4 = sched.next_command()
        self.assertIsNone(c4, "No pot should be available when all pots are in-flight")

        # Complete c1
        sched.on_command_terminal("c1", c1["potId"], c1["observedVersion"] + 1, "CHANGED")

        # Now pot 1 should be available again
        c5 = sched.next_command()
        self.assertIsNotNone(c5)
        self.assertEqual(c5["potId"], c1["potId"])

    def test_05_corrections_only_on_applied_expenses(self):
        """UpdateExpenseFinancials must only target expenses that were previously confirmed applied."""
        pot = self.pots[0]
        pot.applied_expenses = []  # No applied expenses
        for s in pot.shareholders:
            s.weight_updated = True
            s.attached = True

        # Lock other pots
        for p in self.pots[1:]:
            p.in_flight = True

        # Force high deficit for corrections
        self.scheduler.emitted_counts["CreateExpense"] = 1000
        self.scheduler.emitted_counts["UpdateExpenseFinancials"] = 0

        # Ask for command on pot 0
        cmd = self.scheduler.next_command()
        # Even if correction ratio had deficit, without applied expenses it must fallback to CreateExpense
        self.assertEqual(cmd["operation"], "CreateExpense")

    def test_06_single_correction_per_expense(self):
        """An expense can only be corrected once; subsequent corrections must pick other expenses."""
        pot = self.pots[0]
        for s in pot.shareholders:
            s.weight_updated = True
            s.attached = True

        e1 = ExpenseInfo("exp-1", "E1", "2026-11-08", 100, 1, pot.shareholders[0].id, [s.id for s in pot.shareholders], corrected=False)
        e2 = ExpenseInfo("exp-2", "E2", "2026-11-08", 200, 1, pot.shareholders[0].id, [s.id for s in pot.shareholders], corrected=False)
        pot.applied_expenses = [e1, e2]

        for p in self.pots[1:]:
            p.in_flight = True

        # Force high deficit for corrections
        self.scheduler.emitted_counts["CreateExpense"] = 1000
        self.scheduler.emitted_counts["UpdateExpenseFinancials"] = 0

        cmd1 = self.scheduler.next_command()
        self.assertEqual(cmd1["operation"], "UpdateExpenseFinancials")
        target1 = cmd1["payload"]["id"]
        self.scheduler.record_admission("cid-1", pot.pot_id, cmd1["operation"], 202, cmd1["_meta"])
        self.scheduler.on_command_terminal("cid-1", pot.pot_id, 3, "CHANGED")

        # The targeted expense must now be marked corrected
        corrected_exp = [e for e in pot.applied_expenses if e.id == target1][0]
        self.assertTrue(corrected_exp.corrected)

        # Next correction must target the other expense
        cmd2 = self.scheduler.next_command()
        self.assertEqual(cmd2["operation"], "UpdateExpenseFinancials")
        target2 = cmd2["payload"]["id"]
        self.assertNotEqual(target1, target2)
        self.scheduler.record_admission("cid-2", pot.pot_id, cmd2["operation"], 202, cmd2["_meta"])
        self.scheduler.on_command_terminal("cid-2", pot.pot_id, 4, "CHANGED")

        # Now both expenses are corrected. Next command must fallback to CreateExpense
        cmd3 = self.scheduler.next_command()
        self.assertEqual(cmd3["operation"], "CreateExpense")

    def test_07_single_weight_update_per_shareholder(self):
        """A shareholder's default weight can only be updated once."""
        pot = self.pots[0]
        for s in pot.shareholders:
            s.weight_updated = True
        # Only 1 shareholder left eligible
        pot.shareholders[0].weight_updated = False
        target_sh = pot.shareholders[0].id

        for p in self.pots[1:]:
            p.in_flight = True

        self.scheduler.emitted_counts["CreateExpense"] = 1000
        self.scheduler.emitted_counts["UpdateShareholderWeights"] = 0

        cmd = self.scheduler.next_command()
        self.assertEqual(cmd["operation"], "UpdateShareholderWeights")
        self.assertEqual(cmd["payload"]["targets"][0]["id"], target_sh)

        self.scheduler.record_admission("cid-w", pot.pot_id, cmd["operation"], 202, cmd["_meta"])
        self.scheduler.on_command_terminal("cid-w", pot.pot_id, 3, "CHANGED")

        self.assertTrue(pot.shareholders[0].weight_updated)

    def test_08_single_attachment_per_shareholder(self):
        """An unattached shareholder can only be attached once."""
        pot = self.pots[0]
        for s in pot.shareholders:
            s.attached = True
            s.weight_updated = True
        # Only 1 shareholder left unattached
        pot.shareholders[2].attached = False
        target_sh = pot.shareholders[2].id

        for p in self.pots[1:]:
            p.in_flight = True

        self.scheduler.emitted_counts["CreateExpense"] = 1000
        self.scheduler.emitted_counts["UpdateShareholderDetails"] = 0

        cmd = self.scheduler.next_command()
        self.assertEqual(cmd["operation"], "UpdateShareholderDetails")
        self.assertEqual(cmd["payload"]["targets"][0]["id"], target_sh)
        self.assertEqual(cmd["payload"]["targets"][0]["attachment"], "SELF")

        self.scheduler.record_admission("cid-att", pot.pot_id, cmd["operation"], 202, cmd["_meta"])
        self.scheduler.on_command_terminal("cid-att", pot.pot_id, 3, "CHANGED")

        self.assertTrue(pot.shareholders[2].attached)

    def test_09_no_reuse_of_exhausted_rare_ops(self):
        """When all shareholders on all pots have consumed rare operations, fall back to CreateExpense."""
        for p in self.pots:
            for s in p.shareholders:
                s.attached = True
                s.weight_updated = True

        self.scheduler.emitted_counts["CreateExpense"] = 1000
        self.scheduler.emitted_counts["UpdateShareholderWeights"] = 0
        self.scheduler.emitted_counts["UpdateShareholderDetails"] = 0

        cmd = self.scheduler.next_command()
        self.assertEqual(cmd["operation"], "CreateExpense", "Must safely fallback to CreateExpense without crashing")

    def test_10_round_robin_fairness(self):
        """Commands must rotate equitably across all available Pots."""
        num_pots = len(self.pots)
        targeted_pots = []
        for i in range(num_pots):
            cmd = self.scheduler.next_command()
            self.assertIsNotNone(cmd)
            targeted_pots.append(cmd["potId"])
            # release immediately
            self.scheduler.on_command_terminal(f"cmd-{i}", cmd["potId"], cmd["observedVersion"] + 1, "CHANGED")

        # Every Pot should be targeted exactly once
        self.assertEqual(len(set(targeted_pots)), num_pots)

    def test_11_sustain_target_rate_capacity(self):
        """Simulates 50 cmd/s injection across 50 pots when latency allows continuous feeding."""
        emitted = 0
        for _ in range(500):
            cmd = self.scheduler.next_command()
            if cmd:
                emitted += 1
                self.scheduler.on_command_terminal(f"c-{_}", cmd["potId"], cmd["observedVersion"] + 1, "CHANGED")
        self.assertEqual(emitted, 500)
        self.assertEqual(self.scheduler.pot_starvation_count, 0)

    def test_12_reaction_to_blocked_pot(self):
        """If one Pot is stalled/blocked, the scheduler smoothly rotates among all remaining pots."""
        blocked_pot = self.pots[0]
        blocked_cmd = self.scheduler.next_command()
        self.assertEqual(blocked_cmd["potId"], blocked_pot.pot_id)
        # We DO NOT complete blocked_cmd: blocked_pot remains in_flight!

        # Next commands should never target blocked_pot
        for _ in range(30):
            cmd = self.scheduler.next_command()
            self.assertIsNotNone(cmd)
            self.assertNotEqual(cmd["potId"], blocked_pot.pot_id)
            self.scheduler.on_command_terminal(f"c-{_}", cmd["potId"], cmd["observedVersion"] + 1, "CHANGED")

    def test_13_pause_and_resume(self):
        """Scheduler state must be cleanly serializable and resumable."""
        cmd1 = self.scheduler.next_command()
        self.scheduler.on_command_terminal("c1", cmd1["potId"], cmd1["observedVersion"] + 1, "CHANGED")

        # Snapshot pots
        dumped = [p.to_dict() for p in self.pots]
        restored_pots = [PotState.from_dict(d) for d in dumped]

        sched_restored = RealisticWorkloadScheduler(self.config, restored_pots)
        self.assertEqual(len(sched_restored.pots), len(self.pots))
        self.assertEqual(sched_restored.pots[0].confirmed_version, self.pots[0].confirmed_version)

    def test_14_command_conservation(self):
        """Emitted = Admitted + Rejected, exactly."""
        for i in range(50):
            cmd = self.scheduler.next_command()
            status = 202 if (i % 5 != 0) else 503
            self.scheduler.record_admission(f"c-{i}", cmd["potId"], cmd["operation"], status, cmd.get("_meta"))
            if status == 202:
                self.scheduler.on_command_terminal(f"c-{i}", cmd["potId"], cmd["observedVersion"] + 1, "CHANGED")

        report = self.scheduler.get_mix_report()
        total_emitted = report["totalEmitted"]
        total_admitted = report["totalAdmitted"]
        total_applied = report["totalApplied"]
        self.assertEqual(total_emitted, 50)
        self.assertEqual(total_admitted, 40)
        self.assertEqual(total_applied, 40)

    def test_15_timeout_and_failure_handling(self):
        """When an admission or execution fails, the Pot's in-flight lock is immediately freed."""
        pot = self.pots[0]
        # Lock others
        for p in self.pots[1:]:
            p.in_flight = True

        cmd = self.scheduler.next_command()
        self.assertTrue(pot.in_flight)

        # Simulate admission failure (e.g. 503 capacity refusal)
        self.scheduler.record_admission("cid-fail", pot.pot_id, cmd["operation"], 503)
        self.assertFalse(pot.in_flight, "Pot must be freed immediately when admission fails")

        # Another command can now be emitted on this Pot
        cmd2 = self.scheduler.next_command()
        self.assertIsNotNone(cmd2)
        self.assertTrue(pot.in_flight)

        # Simulate terminal REJECTED
        self.scheduler.on_command_terminal("cid-rej", pot.pot_id, None, "REJECTED")
        self.assertFalse(pot.in_flight, "Pot must be freed when command is terminal REJECTED")


if __name__ == "__main__":
    unittest.main()
