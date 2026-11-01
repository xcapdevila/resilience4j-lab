#!/usr/bin/env python3
"""Read-only end-to-end checks against the running local recovery lab.

Exit 0: required checks passed for the current recovery trial. Exit 2: that trial
lacks recovery evidence or a service/check is unavailable. Scraped percentage
coverage is reported separately, since short phases can fall between scrapes.
This never changes scenarios or breaker states.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, ProxyHandler

SMART = "DemoService.process"
STANDARD = "DemoService.processStandard"
OPTIONAL_METRICS = (
    "resilience4j_circuitbreaker_recovery_phase",
    "resilience4j_circuitbreaker_recovery_traffic_percentage",
    "resilience4j_circuitbreaker_admission_attempts_total",
    "resilience4j_circuitbreaker_admission_permitted_attempts_total",
)
LAB_DIR = Path(__file__).resolve().parent
RATE_WINDOW_SECONDS = 4


class LabChecks:
    def __init__(self, args):
        self.args = args
        # The lab is local; corporate HTTP proxies must not intercept it.
        self.opener = build_opener(ProxyHandler({}))
        token = base64.b64encode(
            f"{args.grafana_user}:{args.grafana_password}".encode()
        ).decode()
        self.auth = {"Authorization": f"Basic {token}"}
        self.report = {}
        self.checks = {}
        self.status = None
        self.trial_start = None
        self.history_start = None
        self.history_end = None
        self.events = []

    def get(self, base, path, params=None, authenticated=False):
        url = base.rstrip("/") + path
        if params:
            url += "?" + urlencode(params)
        request = Request(url, headers=self.auth if authenticated else {})
        with self.opener.open(request, timeout=5) as response:
            return json.load(response)

    def prom(self, expression, ranged=False):
        params = {"query": expression}
        endpoint = "/api/v1/query"
        if ranged:
            endpoint += "_range"
            end = self.history_end if self.history_end is not None else time.time()
            start = self.history_start if self.history_start is not None else end - self.args.lookback_seconds
            params.update(start=start, end=end, step="1s")
        result = self.get(self.args.prometheus_url, endpoint, params)
        if result.get("status") != "success":
            raise ValueError(f"Prometheus query rejected: {result.get('error', result)}")
        return result["data"]["result"]

    def selector(self, name=None, state=None):
        labels = {"job": self.args.job, "instance": self.args.instance}
        if name:
            labels["name"] = name
        if state:
            labels["state"] = state
        return "{" + ",".join(f"{key}={json.dumps(value)}" for key, value in labels.items()) + "}"

    def check(self, name, passed, detail):
        self.checks[name] = {"passed": bool(passed), "detail": detail}

    def attempt(self, name, operation):
        try:
            operation()
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, KeyError, TypeError) as error:
            # No credentials or full responses are emitted on errors.
            self.check(name, False, f"{type(error).__name__}: {str(error)[:240]}")

    @staticmethod
    def samples(series, since=-math.inf):
        return [(float(timestamp), float(value)) for item in series
                for timestamp, value in item.get("values", [])
                if float(timestamp) >= since and math.isfinite(float(value))]

    @staticmethod
    def event_time(event):
        return datetime.fromisoformat(event["timestamp"].replace("Z", "+00:00")).timestamp()

    def trial_context_checks(self):
        self.status = self.get(self.args.app_url, "/lab/api/status")
        config = self.status["configuration"]
        self.trial_start = float(config["trialStartedAtEpochMillis"]) / 1000
        self.history_end = time.time()
        if not 0 < self.trial_start <= self.history_end + 1:
            raise ValueError("The application did not supply a valid current-trial start time")
        self.history_start = max(self.trial_start, self.history_end - self.args.lookback_seconds)
        events = self.status.get("events", [])
        # A fresh configuration marker is more precise than the millisecond
        # boundary and excludes the final events of the retired circuit.
        markers = [self.event_time(event) for event in events
                   if event.get("type") == "CONFIGURATION_CHANGED"
                   and event.get("reason") == "NEW_RUN"
                   and self.event_time(event) >= self.trial_start]
        event_start = max(self.history_start, max(markers, default=self.trial_start))
        self.events = sorted((event for event in events
                              if event_start <= self.event_time(event) <= self.history_end),
                             key=self.event_time)
        self.report["currentTrial"] = {
            key: config[key] for key in ("instanceId", "revision", "trialRevision", "trialStartedAtEpochMillis")
        }
        self.report["currentTrial"].update(historySinceEpochMillis=round(self.history_start * 1000),
                                           excludedEvents=len(events) - len(self.events))
        self.check("current_trial_identified", True, self.report["currentTrial"])

    def current_sample(self, expression):
        if self.trial_start is None:
            raise ValueError("Current-trial metadata is required before reading metric history")
        # query_range evaluation timestamps can forward-fill an old gauge for
        # Prometheus's lookback period. Guard its raw scrape timestamp instead.
        return f"({expression}) and (timestamp({expression}) >= {self.trial_start:.3f})"

    def native_transitions(self, name):
        return [event for event in self.events
                if event.get("name") == name and event.get("type") == "STATE_TRANSITION"]

    def native_recovered(self, name):
        entered = False
        for event in self.native_transitions(name):
            if event.get("from") == "OPEN" and event.get("to") == "HALF_OPEN":
                entered = True
            elif event.get("from") == "HALF_OPEN" and event.get("to") == "CLOSED" and entered:
                return True
            elif event.get("to") != "HALF_OPEN":
                entered = False
        return False

    def service_checks(self):
        targets = self.get(self.args.prometheus_url, "/api/v1/targets")["data"]["activeTargets"]
        target = [entry for entry in targets
                  if entry.get("labels", {}).get("job") == self.args.job
                  and entry.get("labels", {}).get("instance") == self.args.instance]
        self.report["prometheusTargets"] = [
            {key: entry.get(key) for key in ("health", "scrapeUrl", "lastError", "lastScrape")}
            for entry in target
        ]
        self.check("prometheus_scrapes_real_app", len(target) == 1 and target[0]["health"] == "up",
                   self.report["prometheusTargets"])
        health = self.get(self.args.grafana_url, "/api/health")
        self.report["grafanaVersion"] = health.get("version")
        self.check("grafana_healthy", health.get("database") == "ok", health.get("database"))
        datasource = self.get(self.args.grafana_url,
                              "/api/datasources/uid/r4j-prometheus", authenticated=True)
        self.check("grafana_datasource_provisioned",
                   datasource.get("type") == "prometheus" and datasource.get("url") == "http://prometheus:9090",
                   {key: datasource.get(key) for key in ("uid", "type", "url")})
        proxied = self.get(self.args.grafana_url,
                           "/api/datasources/proxy/uid/r4j-prometheus/api/v1/query",
                           {"query": "up" + self.selector()}, authenticated=True)
        proxy_values = proxied.get("data", {}).get("result", [])
        self.check("grafana_can_query_prometheus",
                   proxied.get("status") == "success" and any(float(item["value"][1]) == 1 for item in proxy_values),
                   {"resultCount": len(proxy_values)})
        dashboard = self.get(self.args.grafana_url,
                             "/api/dashboards/uid/r4j-local-lab", authenticated=True)
        panels = dashboard["dashboard"]["panels"]
        core_panel_ids = set(range(1, 11))
        panel_ids = {panel.get("id") for panel in panels}
        self.check("grafana_dashboard_loaded", core_panel_ids.issubset(panel_ids),
                   {"uid": dashboard["dashboard"].get("uid"), "panels": len(panels),
                    "provisioned": dashboard.get("meta", {}).get("provisioned")})

    def live_metrics_checks(self):
        exported = {}
        for name in (SMART, STANDARD):
            state = self.prom(self.current_sample("resilience4j_circuitbreaker_state" + self.selector(name)))
            self.check("state_metrics_" + name, bool(state), {"series": len(state)})
            exported[name] = [metric for metric in OPTIONAL_METRICS
                              if self.prom(self.current_sample(metric + self.selector(name)))]
        self.report["optionalMetrics"] = exported
        self.check("smart_exports_all_four_optional_metrics",
                   set(exported[SMART]) == set(OPTIONAL_METRICS), exported[SMART])
        self.check("standard_has_no_optional_metrics", not exported[STANDARD], exported[STANDARD])
        totals = {}
        for metric in OPTIONAL_METRICS[2:]:
            result = self.prom(self.current_sample(metric + self.selector(SMART)))
            totals[metric] = float(result[0]["value"][1]) if result else None
        attempts, permitted = (totals[metric] for metric in OPTIONAL_METRICS[2:])
        self.check("admission_counters_are_present_and_nonnegative",
                   all(value is not None and math.isfinite(value) and value >= 0 for value in (attempts, permitted)), totals)
        # Independent scrapes are not an atomic cohort: another offer and grant
        # can occur between the attempts read and the permitted read.
        self.report["admissionCounterSnapshot"] = totals

    def state_history_checks(self):
        history = {}
        for name in (SMART, STANDARD):
            series = self.prom(self.current_sample("resilience4j_circuitbreaker_state" + self.selector(name)), ranged=True)
            active = sorted((float(timestamp), item["metric"].get("state"))
                            for item in series for timestamp, value in item.get("values", [])
                            if float(timestamp) >= self.history_start and float(value) == 1)
            sequence = []
            for _, state in active:
                if not sequence or sequence[-1] != state:
                    sequence.append(state)
            history[name] = sequence
            required = {"closed", "open", "half_open"} if name == SMART else {"closed", "open"}
            native_states = {str(event.get(key)).lower() for event in self.native_transitions(name)
                             for key in ("from", "to")}
            scraped = required.issubset(sequence)
            self.check("public_state_history_" + name, scraped or required.issubset(native_states),
                       {"scraped": sequence, "nativeStates": sorted(native_states),
                        "evidence": "scrapes" if scraped else "native events if phases fell between scrapes"})
            if name == SMART:
                recovered = any(state == "half_open" and "closed" in sequence[index + 1:]
                                for index, state in enumerate(sequence))
                self.check("smart_recovers_to_closed_after_half_open", recovered or self.native_recovered(name),
                           {"scraped": sequence, "nativeRecovery": self.native_recovered(name)})
        self.report["scrapedStateSequences"] = history
        target = self.prom(self.current_sample(OPTIONAL_METRICS[1] + self.selector(SMART)), ranged=True)
        values = [value for _, value in self.samples(target, self.history_start)]
        targets = sorted(set(values))
        phase_targets = [int(event["to"]) for event in self.events
                         if event.get("name") == SMART and event.get("type") == "HALF_OPEN_PHASE_TRANSITION"]
        config = self.status["configuration"]
        initial = config["initialTrafficPercentage"] or config["trafficPercentageStep"]
        intermediate_expected = initial < 100
        self.report["observedRecoveryTargets"] = {"scraped": targets, "native": sorted(set(phase_targets)),
                                                  "configuredInitialTarget": initial}
        self.check("smart_ramp_has_intermediate_target",
                   not intermediate_expected or any(0 < value < 100 for value in values + phase_targets),
                   {**self.report["observedRecoveryTargets"], "applicable": intermediate_expected})
        self.check("smart_ramp_validates_100_percent_phase", 100 in values + phase_targets,
                   self.report["observedRecoveryTargets"])
        selector = self.selector(SMART)
        attempts = "rate(resilience4j_circuitbreaker_admission_attempts_total" + selector + "[4s])"
        admitted = "rate(resilience4j_circuitbreaker_admission_permitted_attempts_total" + selector + "[4s])"
        observed = self.prom(f"(100 * {admitted} / {attempts}) and ({attempts} > 0)", ranged=True)
        # Every counter range must be wholly inside the current trial. Filtering
        # only by evaluation time >= trial_start would still include old calls.
        percentages = [value for _, value in self.samples(observed,
                       max(self.history_start, self.trial_start + RATE_WINDOW_SECONDS))]
        intermediate = any(0 < value < 99 for value in percentages)
        full = any(99 <= value <= 101 for value in percentages)
        coverage = "no samples"
        if percentages:
            coverage = "intermediate and full samples" if intermediate and full else "partial"
            if not intermediate_expected and full:
                coverage = "full samples for single target"
        detail = {"sampleCount": len(percentages), "min": min(percentages, default=None),
                  "max": max(percentages, default=None), "window": "4s",
                  "intermediateExpected": intermediate_expected, "intermediateObserved": intermediate,
                  "fullTrafficObserved": full,
                  "coverage": coverage,
                  "note": "Sampled percentage coverage is separate from recovery proof. Short phases may be missed; no attempts means no percentage."}
        self.report["observedAdmissionPercentage"] = detail

    def event_checks(self):
        status = self.status
        self.report["scenario"] = {key: status.get(key) for key in ("scenario", "stage", "successRatio", "running")}
        self.report["circuits"] = status.get("circuits", [])
        self.report["actualTransitionEvents"] = [event for event in self.events
            if event.get("type") in ("STATE_TRANSITION", "HALF_OPEN_PHASE_TRANSITION")]
        for name in (SMART, STANDARD):
            circuit_events = self.native_transitions(name)
            states = {str(event.get(key)).upper() for event in circuit_events for key in ("from", "to")}
            # Standard's three probes can complete between two 1s scrapes.
            # The native transition event is the reliable HALF_OPEN evidence.
            self.check("native_state_transition_events_" + name,
                       {"CLOSED", "OPEN", "HALF_OPEN"}.issubset(states),
                       {"states": sorted(states), "events": len(circuit_events)})

    def trial_consistency_check(self):
        latest = self.get(self.args.app_url, "/lab/api/status")["configuration"]
        original = self.status["configuration"]
        keys = ("instanceId", "trialRevision", "trialStartedAtEpochMillis")
        self.check("same_trial_throughout_verification", all(latest[key] == original[key] for key in keys),
                   {"started": {key: original[key] for key in keys},
                    "finished": {key: latest[key] for key in keys}})

    def grafana_queries(self, queries):
        """Execute read-only datasource queries through Grafana's real backend.

        POST is the datasource API's query verb. It does not edit dashboards,
        datasource configuration, application controls or circuit state.
        """
        end = int(time.time() * 1000)
        payload = {"queries": queries,
                   "from": str(end - self.args.lookback_seconds * 1000), "to": str(end)}
        request = Request(self.args.grafana_url.rstrip("/") + "/api/ds/query",
                          data=json.dumps(payload).encode(), method="POST",
                          headers={**self.auth, "Content-Type": "application/json"})
        with self.opener.open(request, timeout=10) as response:
            result = json.load(response)
        for query in queries:
            ref = query["refId"]
            entry = result.get("results", {}).get(ref)
            if entry is None or entry.get("error") or entry.get("status", 200) != 200:
                raise ValueError(f"Grafana rejected datasource query {ref}: "
                                 f"{entry.get('error') if entry else 'missing result'}")
        return [{"refId": query["refId"],
                 "frames": len(result["results"][query["refId"]].get("frames", []))}
                for query in queries]

    def dashboard_query_checks(self):
        sources = [LAB_DIR / "dashboards" / "recovery.json"]
        sources.extend(path for path in sorted((LAB_DIR / "generated" / "dashboards").glob("*.json")))
        evaluated = []
        grafana_evaluated = []
        template_count = 0
        replacements = {"${job}": json.dumps(self.args.job, ensure_ascii=False)[1:-1],
                        "${instance}": json.dumps(self.args.instance, ensure_ascii=False)[1:-1],
                        "$__rate_interval": "4s"}
        for source in sources:
            dashboard = json.loads(source.read_text())
            template_count += sum(len(panel.get("targets", [])) for panel in dashboard["panels"])
            for name in (SMART, STANDARD):
                queries = []
                for panel in dashboard["panels"]:
                    for target in panel.get("targets", []):
                        expression = target["expr"]
                        for variable, value in replacements.items():
                            expression = expression.replace(variable, value)
                        expression = expression.replace("${circuitbreaker}", json.dumps(name, ensure_ascii=False)[1:-1])
                        if "$" in expression:
                            raise ValueError("An unsupported dashboard variable remains unexpanded")
                        result = self.prom(expression, ranged=target.get("range", False))
                        evaluated.append({"dashboard": dashboard["uid"], "name": name, "panel": panel["id"],
                                          "refId": target["refId"], "series": len(result)})
                        queries.append({"refId": f"P{panel['id']}{target['refId']}",
                                        "datasource": {"uid": "r4j-prometheus", "type": "prometheus"},
                                        "expr": expression, "format": "time_series",
                                        "instant": target.get("instant", False),
                                        "range": target.get("range", False),
                                        "intervalMs": 1000, "maxDataPoints": self.args.lookback_seconds})
                grafana_evaluated.extend({"dashboard": dashboard["uid"], "name": name, **entry}
                                         for entry in self.grafana_queries(queries))
        self.report["dashboardQueries"] = evaluated
        self.report["grafanaDatasourceQueries"] = grafana_evaluated
        self.report["interpolationValidation"] = (
            "Explicit values encoded as Prometheus string literal contents; actual browser variable interpolation "
            "must be checked in the Grafana UI separately.")
        expected = template_count * 2
        self.check("all_dashboard_queries_execute_on_real_prometheus", len(evaluated) == expected and expected >= 22,
                   {"executions": len(evaluated), "queryTemplates": template_count, "dashboards": len(sources)})
        self.check("all_dashboard_queries_execute_through_grafana", len(grafana_evaluated) == expected,
                   {"executions": len(grafana_evaluated), "datasource": "r4j-prometheus"})
        exported = {name: [metric for metric in ("lab_traffic_requested", "lab_traffic_dispatched_total",
                    "lab_traffic_completed_total", "lab_traffic_skipped_total", "lab_traffic_in_flight")
                    if self.prom(metric + self.selector(name))] for name in (SMART, STANDARD)}
        self.report["loadGeneratorMetrics"] = exported
        self.check("load_generator_metrics_exported", all(len(metrics) == 5 for metrics in exported.values()), exported)

    def run(self):
        self.report = {"checkedAt": datetime.now(timezone.utc).isoformat(),
                       "readOnly": True, "lookbackSeconds": self.args.lookback_seconds,
                       "urls": {"app": self.args.app_url, "prometheus": self.args.prometheus_url,
                                "grafana": self.args.grafana_url},
                       "scrapeLabels": {"job": self.args.job, "instance": self.args.instance}}
        self.checks = {}
        self.status = None
        self.trial_start = self.history_start = self.history_end = None
        self.events = []
        self.attempt("trial_context", self.trial_context_checks)
        if not self.checks.get("current_trial_identified", {}).get("passed"):
            self.report["checks"] = self.checks
            self.report["passed"] = False
            return self.report
        for name, operation in (("services", self.service_checks),
                                ("live_metrics", self.live_metrics_checks),
                                ("history", self.state_history_checks),
                                ("native_events", self.event_checks),
                                ("dashboard_queries", self.dashboard_query_checks),
                                ("trial_consistency", self.trial_consistency_check)):
            self.attempt(name, operation)
        self.report["checks"] = self.checks
        self.report["passed"] = all(check["passed"] for check in self.checks.values())
        return self.report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus-url", default="http://localhost:9090")
    parser.add_argument("--grafana-url", default="http://localhost:3000")
    parser.add_argument("--app-url", default="http://localhost:8080/demo")
    parser.add_argument("--grafana-user", default="admin")
    parser.add_argument("--grafana-password", default="local-demo")
    parser.add_argument("--job", default="resilience4j-demo")
    parser.add_argument("--instance", default="app:8080")
    parser.add_argument("--lookback-seconds", type=int, default=600)
    parser.add_argument("--wait-seconds", type=int, default=180,
                        help="Wait for naturally generated evidence; 0 checks once (default 180).")
    parser.add_argument("--output", type=Path, default=LAB_DIR / "generated" / "live-verification.json")
    args = parser.parse_args()
    if not 0 <= args.wait_seconds <= 600 or not 30 <= args.lookback_seconds <= 3600:
        parser.error("wait-seconds must be 0..600; lookback-seconds must be 30..3600")
    deadline = time.monotonic() + args.wait_seconds
    checker = LabChecks(args)
    while True:
        report = checker.run()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        missing = [name for name, check in report["checks"].items() if not check["passed"]]
        if report["passed"]:
            print(f"PASS: {len(report['checks'])} required live checks for the current trial. Evidence: {args.output}", flush=True)
            print("Sampled percentage coverage: " + report.get("observedAdmissionPercentage", {}).get("coverage", "unavailable"), flush=True)
            return 0
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            print("INCOMPLETE: " + ", ".join(missing), flush=True)
            print(f"Read-only evidence: {args.output}. Services may still be warming up, or the current trial lacks a full cycle in the selected window.")
            return 2
        print(f"Waiting for real evidence ({max(0, int(remaining))}s left): " + ", ".join(missing), flush=True)
        # Keep each individual wait short, including when invoked interactively.
        for _ in range(2):
            delay = min(5, max(0, deadline - time.monotonic()))
            if delay:
                time.sleep(delay)


if __name__ == "__main__":
    sys.exit(main())
