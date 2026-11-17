"""Historical drivers must refuse incomplete-slot cleanup before any external effect."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TARGETS = {
    'v3_r9_resilience.py': ('cleanup_quarantined_slots', 'run_experiment_c6'),
    'v3_r10_consolidation.py': ('cleanup_quarantined_slots', 'resolve_quarantined_slots_option_c'),
    'v3_r11_filter_qualification.py': ('cleanup_quarantined_slots',),
    'v3_populated_db_generator.py': ('synchronize_checkpoints_and_analyze', 'retire_all_quarantines'),
}


class HistoricalAdminSafety(unittest.TestCase):
    def test_entrypoints_and_importable_cleanup_refuse_before_external_effects(self):
        for filename, names in TARGETS.items():
            spec = importlib.util.spec_from_file_location('l73_historical', ROOT/'scripts/performance'/filename)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            for name in (*names, 'main'):
                with self.subTest(script=filename, entrypoint=name), \
                        patch.object(module.subprocess, 'run', side_effect=AssertionError('external process forbidden')), \
                        patch.object(module.time, 'sleep', side_effect=AssertionError('sleep forbidden')), \
                        patch.object(module.Path, 'write_text', side_effect=AssertionError('file mutation forbidden')):
                    with self.assertRaisesRegex(RuntimeError, 'retired.*L7.3'):
                        getattr(module, name)()


if __name__ == '__main__':
    unittest.main()
