"""Offline regression fixtures for current-trial live-verification evidence.

Run with: python3 -m unittest discover -s local-lab -p 'test_*.py'
No application, Prometheus, Grafana or network access is needed.
"""
import copy
from datetime import datetime, timezone
import importlib.util
from pathlib import Path
import re
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("verify_lab", Path(__file__).with_name("verify-lab.py"))
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)
TRIAL_START = 1_000.0
NOW = 1_020.0


def event(at, kind, source, target, name=VERIFY.SMART, reason=""):
    return {"timestamp": datetime.fromtimestamp(at, timezone.utc).isoformat(),
            "name": name, "type": kind, "from": source, "to": target, "reason": reason}


def recovery_events(start, targets=(25, 50, 75, 100), name=VERIFY.SMART):
    result = [event(start, "STATE_TRANSITION", "CLOSED", "OPEN", name),
              event(start + .1, "STATE_TRANSITION", "OPEN", "HALF_OPEN", name)]
    previous = 0
    for index, target in enumerate(targets):
        result.append(event(start + .2 + index * .1, "HALF_OPEN_PHASE_TRANSITION",
                            str(previous), str(target), name))
        previous = target
    result.append(event(start + 1, "STATE_TRANSITION", "HALF_OPEN", "CLOSED", name))
    return result


def status(events=(), step=25, initial=0, rps=100):
    return {"configuration": {"instanceId": "current-process", "revision": 2, "trialRevision": 1,
                              "trialStartedAtEpochMillis": int(TRIAL_START * 1000),
                              "trafficPercentageStep": step, "initialTrafficPercentage": initial,
                              "requestsPerSecond": rps},
            "scenario": "cycle", "running": rps > 0, "events": list(events)}


class EvidenceFixture(VERIFY.LabChecks):
    """Model raw scrape timestamps separately from query_range evaluation times."""
    def __init__(self, snapshot, states=None, targets=(), percentages=(), counters=(10, 5)):
        args = SimpleNamespace(grafana_user="admin", grafana_password="unused", job="resilience4j-demo",
                               instance="app:8080", lookback_seconds=600, app_url="http://unused")
        super().__init__(args)
        self.snapshot = snapshot
        self.states = states or {}
        self.targets = targets
        self.percentages = percentages
        self.counters = counters
        self.expressions = []

    def get(self, base, path, params=None, authenticated=False):
        return copy.deepcopy(self.snapshot)

    def prom(self, expression, ranged=False):
        self.expressions.append(expression)
        if "rate(" in expression:
            return [{"metric": {}, "values": self.percentages}] if self.percentages else []
        name = VERIFY.STANDARD if VERIFY.STANDARD in expression else VERIFY.SMART
        if name == VERIFY.STANDARD and "resilience4j_circuitbreaker_state" not in expression:
            return []
        guard = re.search(r"timestamp\(.*\) >= ([0-9.]+)", expression)
        raw_since = float(guard.group(1)) if guard else float("-inf")

        def series(metric, samples):
            # A pre-trial raw gauge can be evaluated at a post-trial timestamp.
            # The verifier must reject it by timestamp(metric), not merely by
            # the query_range result's evaluation timestamp.
            values = [[evaluated_at, str(value)] for scraped_at, evaluated_at, value in samples
                      if scraped_at >= raw_since]
            return {"metric": metric, "values": values} if ranged else {
                "metric": metric, "value": values[-1]} if values else None

        if "resilience4j_circuitbreaker_state" in expression:
            result = [series({"state": state}, samples) for state, samples in self.states.get(name, {}).items()]
        elif "recovery_traffic_percentage" in expression:
            result = [series({}, self.targets)]
        elif "recovery_phase" in expression:
            result = [series({}, [(TRIAL_START + 1, TRIAL_START + 1, 0)])]
        else:
            value = self.counters[1] if "permitted_attempts" in expression else self.counters[0]
            result = [series({}, [(TRIAL_START + 1, TRIAL_START + 1, value)])]
        return [item for item in result if item is not None and (not ranged or item["values"])]

    def prepare(self):
        with patch.object(VERIFY.time, "time", return_value=NOW):
            self.trial_context_checks()
        return self


class CurrentTrialEvidenceTests(unittest.TestCase):
    def test_previous_completed_trial_and_forward_filled_gauges_cannot_verify_a_new_trial(self):
        old = TRIAL_START - 20
        events = recovery_events(old) + recovery_events(old, name=VERIFY.STANDARD)
        # All query evaluation times are current, but their underlying raw
        # scrapes precede the new trial. This reproduced the false positive.
        states = {name: {state: [(old + index, TRIAL_START + index, 1)]
                        for index, state in enumerate(("closed", "open", "half_open", "closed"))}
                  for name in (VERIFY.SMART, VERIFY.STANDARD)}
        checker = EvidenceFixture(status(events), states, [(old, TRIAL_START + 4, 100)]).prepare()

        checker.state_history_checks()
        checker.event_checks()

        self.assertEqual([], checker.events)
        self.assertFalse(checker.checks["smart_recovers_to_closed_after_half_open"]["passed"])
        self.assertFalse(checker.checks["smart_ramp_validates_100_percent_phase"]["passed"])
        self.assertFalse(checker.checks["native_state_transition_events_" + VERIFY.SMART]["passed"])
        self.assertTrue(all("timestamp(" in expression for expression in checker.expressions
                            if "rate(" not in expression))

    def test_rate_windows_crossing_the_trial_boundary_are_excluded(self):
        checker = EvidenceFixture(status(), percentages=[(TRIAL_START + 1, "25"),
                                  (TRIAL_START + 3.999, "50"), (TRIAL_START + 4, "100")]).prepare()

        checker.state_history_checks()

        observed = checker.report["observedAdmissionPercentage"]
        self.assertEqual(1, observed["sampleCount"])
        self.assertEqual(100, observed["min"])
        self.assertFalse(observed["intermediateObserved"])
        self.assertEqual("partial", observed["coverage"])

    def test_fast_phases_use_current_native_events_without_claiming_scraped_percentage_coverage(self):
        events = recovery_events(TRIAL_START + 1) + recovery_events(TRIAL_START + 1, name=VERIFY.STANDARD)
        states = {name: {"closed": [(TRIAL_START + 4, TRIAL_START + 4, 1)]}
                  for name in (VERIFY.SMART, VERIFY.STANDARD)}
        checker = EvidenceFixture(status(events), states).prepare()

        checker.state_history_checks()
        checker.event_checks()

        self.assertTrue(all(check["passed"] for check in checker.checks.values()))
        self.assertEqual([], checker.report["observedRecoveryTargets"]["scraped"])
        self.assertEqual([25, 50, 75, 100], checker.report["observedRecoveryTargets"]["native"])
        self.assertEqual("no samples", checker.report["observedAdmissionPercentage"]["coverage"])

    def test_automatic_or_explicit_initial_100_does_not_require_an_intermediate_phase(self):
        for step, initial in ((100, 0), (25, 100)):
            with self.subTest(step=step, initial=initial):
                checker = EvidenceFixture(status(recovery_events(TRIAL_START + 1, (100,)),
                                                   step=step, initial=initial),
                                          percentages=[(TRIAL_START + 6, "100")]).prepare()
                checker.state_history_checks()
                intermediate = checker.checks["smart_ramp_has_intermediate_target"]
                self.assertTrue(intermediate["passed"])
                self.assertFalse(intermediate["detail"]["applicable"])
                self.assertTrue(checker.checks["smart_recovers_to_closed_after_half_open"]["passed"])
                self.assertEqual("full samples for single target", checker.report["observedAdmissionPercentage"]["coverage"])

    def test_idle_trial_has_no_percentage_and_does_not_fabricate_recovery(self):
        checker = EvidenceFixture(status(rps=0), counters=(0, 0)).prepare()

        checker.state_history_checks()

        observed = checker.report["observedAdmissionPercentage"]
        self.assertEqual("no samples", observed["coverage"])
        self.assertIsNone(observed["min"])
        self.assertIsNone(observed["max"])
        self.assertFalse(checker.checks["smart_recovers_to_closed_after_half_open"]["passed"])

    def test_independent_counter_reads_can_cross_without_failing_the_snapshot(self):
        states = {name: {"closed": [(TRIAL_START + 1, TRIAL_START + 1, 1)]}
                  for name in (VERIFY.SMART, VERIFY.STANDARD)}
        checker = EvidenceFixture(status(), states, counters=(10, 11)).prepare()

        checker.live_metrics_checks()

        self.assertTrue(checker.checks["admission_counters_are_present_and_nonnegative"]["passed"])

    def test_precise_fresh_trial_marker_excludes_same_millisecond_retired_events(self):
        marker = event(TRIAL_START + .0007, "CONFIGURATION_CHANGED", "old", "new", reason="NEW_RUN")
        retired = event(TRIAL_START + .0002, "STATE_TRANSITION", "HALF_OPEN", "CLOSED")
        checker = EvidenceFixture(status([retired, marker])).prepare()

        self.assertEqual([marker], checker.events)
        self.assertFalse(checker.native_recovered(VERIFY.SMART))

    def test_restart_during_verification_invalidates_the_evidence(self):
        checker = EvidenceFixture(status()).prepare()
        checker.snapshot["configuration"]["trialRevision"] += 1

        checker.trial_consistency_check()

        self.assertFalse(checker.checks["same_trial_throughout_verification"]["passed"])


if __name__ == "__main__":
    unittest.main()
