"""Discriminating controls for the smoke oracle; no HTTP or database mutation."""
from copy import deepcopy
from fractions import Fraction
import unittest

import smoke_test_5_runtimes as smoke


class SmokeOracleTest(unittest.TestCase):
    def setUp(self):
        self.state = dict(potId='pot', shareholders=['a', 'b'], version=4,
                          balances={'a': Fraction(2), 'b': Fraction(-2)},
                          weights={'a': Fraction(1), 'b': Fraction(3)}, expenses=2)
        self.expected = smoke.expected_balances(self.state, 'a', Fraction(8))
        self.pot = dict(id='pot', potVersion=5, shareholders=[{'id': 'a'}, {'id': 'b'}],
                        expenses=[{'id': 'old1'}, {'id': 'old2'}, {'id': 'new'}],
                        balances={'a': {'n': '8', 'd': '1'}, 'b': {'n': '-8', 'd': '1'}})
        self.balance = dict(potId='pot', potVersion=5, balances=deepcopy(self.pot['balances']))

    def check(self, pot=None, balance=None):
        smoke.validate_complete(pot or self.pot, balance or self.balance,
                                self.state, 5, 'new', self.expected)

    def test_exact_weighted_delta(self):
        self.assertEqual({'a': Fraction(8), 'b': Fraction(-8)}, self.expected)
        self.check()

    def test_equal_cardinality_foreign_ids_is_rejected(self):
        self.balance['balances']['foreign'] = self.balance['balances'].pop('b')
        with self.assertRaises(ValueError):
            self.check()

    def test_wrong_values_with_zero_sum_are_rejected(self):
        self.balance['balances'] = {'a': {'n': '7', 'd': '1'}, 'b': {'n': '-7', 'd': '1'}}
        with self.assertRaises(ValueError):
            self.check()

    def test_wrong_version_and_pot_are_rejected(self):
        for key, value in [('potVersion', 6), ('potId', 'foreign')]:
            balance = deepcopy(self.balance)
            balance[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(balance=balance)

    def test_incomplete_or_foreign_expense_is_rejected(self):
        self.pot['expenses'][-1]['id'] = 'foreign'
        with self.assertRaises(ValueError):
            self.check()

    def test_rejected_unchanged_missing_version_and_concurrent_writer(self):
        for result in [dict(outcome='REJECTED', resultingPotVersion=5),
                       dict(outcome='UNCHANGED', resultingPotVersion=4),
                       dict(outcome='COMPLETED'),
                       dict(outcome='COMPLETED', resultingPotVersion=6)]:
            with self.subTest(result=result), self.assertRaises(ValueError):
                smoke.applied_version(result, 4)
        self.assertEqual(5, smoke.applied_version(dict(outcome='COMPLETED', resultingPotVersion=5), 4))

    def test_bad_rational_and_nonzero_sum(self):
        for value in [{'n': '1', 'd': '0'}, {'n': '1', 'd': '-1'}, {'n': 1, 'd': '1'}, {}]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                smoke.rational(value)
        with self.assertRaises(ValueError):
            smoke.balance_values({'balances': {'a': {'n': '1', 'd': '1'}}}, ['a'])

    def test_empty_statistics_never_zero_latency(self):
        self.assertIsNone(smoke.percentile([], .95))


if __name__ == '__main__':
    unittest.main()
