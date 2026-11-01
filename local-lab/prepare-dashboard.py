#!/usr/bin/env python3
"""Prepare a local provisioning copy without changing the importable upstream dashboard."""
import argparse
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path

LAB_DIR = Path(__file__).resolve().parent
DEFAULT_SOURCE = LAB_DIR / "dashboards" / "recovery.json"
CLASSIC_SOURCE = LAB_DIR / "dashboards" / "classic.json"
DATASOURCE = {"type": "prometheus", "uid": "r4j-prometheus"}


def target(expression, reference="A", legend="{{kind}}", instant=False):
    return {"datasource": dict(DATASOURCE), "refId": reference, "editorMode": "code",
            "expr": expression, "legendFormat": legend, "format": "time_series",
            "range": not instant, "instant": instant}


def classic_dashboard(original, smart):
    """Adapt the upstream CircuitBreaker panels to this app's native metric contract."""
    dashboard = replace_datasource(deepcopy(original))
    dashboard.pop("__inputs", None)
    dashboard["id"] = None
    dashboard["uid"] = "r4j-local-classic"
    dashboard["title"] = "Resilience4j — Circuit breaker"
    dashboard["description"] = "Classic CircuitBreaker metrics, adapted from the upstream Resilience4j dashboard."
    dashboard["version"] = 1
    dashboard["refresh"] = smart["refresh"]
    dashboard["time"] = deepcopy(smart["time"])
    dashboard["timepicker"] = deepcopy(smart["timepicker"])
    dashboard["templating"] = deepcopy(smart["templating"])
    dashboard["tags"] = ["resilience4j", "local-lab", "classic"]
    dashboard["links"] = [{"title": "Smart recovery", "type": "link",
                            "url": "/d/r4j-local-lab", "includeVars": True,
                            "keepTime": True, "targetBlank": False}]
    dashboard["links"].extend(deepcopy(smart["links"]))
    source_panels = {panel["id"]: panel for panel in replace_datasource(original)["panels"]}
    selector = 'job="${job}",instance="${instance}",name="${circuitbreaker}"'

    def metric(name):
        return "resilience4j_circuitbreaker_" + name + "{" + selector + "}"

    def panel(source, identifier, title, x, y, width, height, targets):
        result = deepcopy(source)
        result.update(id=identifier, title=title,
                      gridPos={"x": x, "y": y, "w": width, "h": height}, targets=targets)
        result["datasource"] = dict(DATASOURCE)
        result.pop("pluginVersion", None)
        return result

    timeline = deepcopy(next(panel for panel in smart["panels"] if panel["id"] == 11))
    timeline.update(id=1, title="Circuit breaker — public state history")
    public_state = panel(next(panel for panel in smart["panels"] if panel["id"] == 1),
                         2, "Public state: ${circuitbreaker}", 0, 5, 8, 5,
                         [target("max by (state) (" + metric("state") + " == 1)",
                                 legend="{{state}}", instant=True)])
    failure_rate = panel(source_panels[2], 3, "Failure rate", 8, 5, 8, 5,
                         [target(metric("failure_rate"), legend="Failure rate", instant=True)])
    slow_rate = panel(source_panels[2], 4, "Slow-call rate", 16, 5, 8, 5,
                      [target(metric("slow_call_rate"), legend="Slow call rate", instant=True)])
    for rate_panel in (failure_rate, slow_rate):
        # Active controls can change both normal thresholds and independent recovery
        # health bands. A fixed upstream 40/50 color scale would misclassify them.
        rate_panel["fieldConfig"]["defaults"]["thresholds"] = {
            "mode": "absolute", "steps": [{"color": "blue", "value": None}]}
        rate_panel["description"] = (
            "Observed rate in the native circuit's current outcome window, not a health verdict. "
            "Interpret it using the active demo controls: gradual HALF_OPEN has independent "
            "advance maxima and regression thresholds; normal CLOSED and standard HALF_OPEN "
            "use normal thresholds. The 100% phase closes only when smart and ordinary bounds pass; "
            "after minimum duration a failed final validation applies the failure strategy. "
            "Phase changes start a fresh recovery window. "
            "N/A means that the minimum sample requirement is not yet met.")
    calls = panel(source_panels[23], 5, "Calls per second: outcomes and rejections", 0, 10, 12, 8, [
        target("rate(" + metric("calls_seconds_count") + "[$__rate_interval])"),
        target("rate(" + metric("not_permitted_calls_total") + "[$__rate_interval])", "B"),
    ])
    calls["fieldConfig"]["defaults"]["unit"] = "ops"
    calls["fieldConfig"]["defaults"]["min"] = 0
    calls["fieldConfig"]["overrides"] = [
        {"matcher": {"id": "byName", "options": kind},
         "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]}
        for kind, color in [("successful", "green"), ("failed", "red"),
                            ("ignored", "blue"), ("not_permitted", "orange")]]
    rates = panel(source_panels[4], 6, "Failure and slow-call rates over time", 12, 10, 12, 8, [
        target(metric("failure_rate") + " >= 0", legend="Failure rate"),
        target(metric("slow_call_rate") + " >= 0", "B", "Slow call rate"),
    ])
    rates["fieldConfig"]["defaults"].update(unit="percent", min=0, max=100)
    buffered = panel(source_panels[11], 7, "Calls in the outcome window", 0, 18, 12, 8,
                     [target(metric("buffered_calls"))])
    buffered["fieldConfig"]["defaults"].update(unit="short", min=0)
    slow_calls = panel(source_panels[11], 8, "Slow calls in the outcome window", 12, 18, 12, 8,
                       [target(metric("slow_calls"))])
    slow_calls["fieldConfig"]["defaults"].update(unit="short", min=0)
    duration_sum = "rate(" + metric("calls_seconds_sum") + "[$__rate_interval])"
    duration_count = "rate(" + metric("calls_seconds_count") + "[$__rate_interval])"
    average = panel(source_panels[25], 9, "Average call duration", 0, 26, 12, 8, [
        target(duration_sum + " / " + duration_count),
        target("sum(" + duration_sum + ") / sum(" + duration_count + ")", "B", "total"),
    ])
    average["fieldConfig"]["defaults"].update(unit="s", min=0)
    maximum = panel(source_panels[25], 10, "Recent maximum call duration", 12, 26, 12, 8,
                    [target(metric("calls_seconds_max"))])
    maximum["fieldConfig"]["defaults"].update(unit="s", min=0)
    maximum["description"] = "Native Micrometer timer maximum over its rolling expiry window; not a lifetime maximum."
    notes = {"id": 11, "title": "Reading the classic metrics", "type": "text",
             "gridPos": {"x": 0, "y": 34, "w": 24, "h": 5},
             "options": {"mode": "markdown", "content":
                 "This view adapts the CircuitBreaker panels from the original Resilience4j dashboard. "
                 "It shows the same native metrics for the standard and smart circuits.\n\n"
                 "**successful / failed / ignored** count completed calls; **not_permitted** counts "
                 "rejected attempts. Durations cover executed calls. Calls per second and average durations "
                 "use a time range; failure and slow-call percentages describe the native current outcome window. "
                 "**N/A** means that there are not enough samples yet.\n\n"
                 "Failure and slow-rate stats use neutral colors: interpret their observed values against "
                 "the **active demo controls**. Gradual HALF_OPEN has separate advance maxima and regression "
                 "thresholds; the intermediate band holds targets below 100%. At 100%, full samples and minimum "
                 "duration lead to CLOSED only when smart and ordinary health criteria pass; otherwise the failure "
                 "strategy applies on a fresh completion. Normal CLOSED and standard HALF_OPEN "
                 "keep their ordinary thresholds. Phase transitions reset the recovery outcome window.\n\n"
                 "State history comes from the public gauge scraped every second. Standard HALF_OPEN "
                 "can complete its three probes between scrapes; the demo controls show the complete events. "
                 "Open **Smart recovery** to inspect phases, target and observed admission."}}

    dashboard["panels"] = [timeline, public_state, failure_rate, slow_rate, calls, rates,
                           buffered, slow_calls, average, maximum, notes]
    return dashboard


def replace_datasource(value):
    if isinstance(value, dict):
        return {key: replace_datasource(item) for key, item in value.items()}
    if isinstance(value, list):
        return [replace_datasource(item) for item in value]
    if isinstance(value, str):
        return value.replace("${DS_PROMETHEUS}", "r4j-prometheus")
    return value


def prepare(source, output, classic_source=CLASSIC_SOURCE):
    raw = source.read_bytes()
    original = json.loads(raw)
    dashboard = replace_datasource(original)
    dashboard.pop("__inputs", None)
    dashboard["id"] = None
    dashboard["uid"] = "r4j-local-lab"
    dashboard["title"] = "Resilience4j — Local validation lab"
    dashboard["version"] = 1
    dashboard["refresh"] = "2s"
    dashboard.setdefault("timepicker", {})["refresh_intervals"] = ["1s", "2s", "5s", "10s", "30s", "1m"]
    dashboard["time"] = {"from": "now-5m", "to": "now"}
    dashboard["editable"] = True
    selections = {
        "job": "resilience4j-demo",
        "instance": "app:8080",
        "circuitbreaker": "DemoService.process",
    }
    for variable in dashboard["templating"]["list"]:
        value = selections[variable["name"]]
        variable["current"] = {"selected": True, "text": value, "value": value}
        variable["options"] = [{"selected": True, "text": value, "value": value}]
    # This lab-only panel compares both real state histories; the importable dashboard stays intact.
    states = [("closed", 0), ("open", 1), ("half_open", 2),
              ("forced_open", 3), ("disabled", 4), ("metrics_only", 5)]
    selector = 'job="${job}",instance="${instance}"'
    terms = [
        f'(resilience4j_circuitbreaker_state{{{selector},state="{state}"}} * {code})'
        for state, code in states
    ]
    colors = ["green", "red", "yellow", "red", "gray", "blue"]
    mappings = {str(code): {"text": state.upper(), "color": colors[code]}
                for state, code in states}
    for panel in dashboard["panels"]:
        panel["gridPos"]["y"] += 5
    dashboard["panels"].insert(0, {
        "id": 11,
        "title": "Standard and smart — public state history",
        "description": "Both circuits on the selected job/instance, regardless of the circuit dropdown. "
                       "The smart circuit remains HALF_OPEN throughout its gradual recovery.",
        "type": "state-timeline",
        "datasource": {"type": "prometheus", "uid": "r4j-prometheus"},
        "gridPos": {"x": 0, "y": 0, "w": 24, "h": 5},
        "fieldConfig": {
            "defaults": {
                "mappings": [{"type": "value", "options": mappings}],
                # State-timeline legends use value mappings only with fixed or palette colors.
                "color": {"mode": "fixed", "fixedColor": "gray"},
                "custom": {"lineWidth": 0, "fillOpacity": 80, "spanNulls": False},
                "noValue": "No data",
            },
            "overrides": [],
        },
        "options": {
            "mergeValues": True, "showValue": "auto", "alignValue": "left", "rowHeight": 0.8,
            "legend": {"showLegend": True, "displayMode": "list", "placement": "bottom"},
            "tooltip": {"mode": "single", "sort": "none"},
        },
        "targets": [{
            "datasource": {"type": "prometheus", "uid": "r4j-prometheus"},
            "refId": "A", "editorMode": "code", "format": "time_series",
            "expr": "sum by (name) (" + " or ".join(terms) + ")",
            "legendFormat": "{{name}}", "range": True, "instant": False,
        }],
    })
    app_port = int(os.environ.get("LAB_APP_PORT", "8080"))
    dashboard.setdefault("links", []).append({
        "title": "Demo controls",
        "type": "link",
        "url": f"http://localhost:{app_port}/demo/lab/",
        "targetBlank": True,
    })
    classic_raw = classic_source.read_bytes()
    classic = classic_dashboard(json.loads(classic_raw), dashboard)
    # Keep the upstream smart panels intact and append the usual native metrics locally.
    bottom = max(panel["gridPos"]["y"] + panel["gridPos"]["h"] for panel in dashboard["panels"])
    dashboard["panels"].append({
        "id": 100, "title": "Standard Resilience4j metrics", "type": "row",
        "gridPos": {"x": 0, "y": bottom, "w": 24, "h": 1},
        "collapsed": False, "panels": [],
    })
    for offset, classic_panel in enumerate(panel for panel in classic["panels"] if 3 <= panel["id"] <= 10):
        copied = deepcopy(classic_panel)
        copied["id"] = 101 + offset
        copied["gridPos"] = {
            "x": 12 * (offset % 2),
            "y": bottom + 1 + (0 if offset < 2 else 5 + ((offset - 2) // 2) * 8),
            "w": 12, "h": 5 if offset < 2 else 8,
        }
        dashboard["panels"].append(copied)
    dashboard["links"].append({"title": "Classic metrics", "type": "link",
                               "url": "/d/r4j-local-classic", "includeVars": True,
                               "keepTime": True, "targetBlank": False})
    # Load-generation metrics are lab-only and have a fixed pair of circuit labels.
    load_bottom = max(panel["gridPos"]["y"] + panel["gridPos"]["h"] for panel in dashboard["panels"])
    dashboard["panels"].append({
        "id": 110, "title": "Load generator: requested and achieved traffic", "type": "row",
        "gridPos": {"x": 0, "y": load_bottom, "w": 24, "h": 1}, "collapsed": False, "panels": [],
    })
    selector = '{job="${job}",instance="${instance}",name="${circuitbreaker}"}'
    load_panels = [
        (111, "Requested and dispatched requests per second", "ops", [
            ("lab_traffic_requested" + selector, "Requested"),
            ("rate(lab_traffic_dispatched_total" + selector + "[$__rate_interval])", "Dispatched"),
        ]),
        (112, "Requests currently in flight", "short", [("lab_traffic_in_flight" + selector, "In flight")]),
        (113, "Requests skipped at the concurrency limit", "ops", [
            ("rate(lab_traffic_skipped_total" + selector + "[$__rate_interval])", "Skipped"),
        ]),
        (114, "Completed HTTP requests per second", "ops", [
            ("rate(lab_traffic_completed_total" + selector + "[$__rate_interval])", "Completed"),
        ]),
    ]
    for offset, (identifier, title, unit, expressions) in enumerate(load_panels):
        template = deepcopy(next(panel for panel in classic["panels"] if panel["id"] == 5))
        template.update(id=identifier, title=title,
                        description="Actual lab HTTP traffic. Dispatched traffic can stay below the requested rate when the concurrency limit or this machine's capacity is reached. Completion rate includes HTTP fallback responses; transport errors are reported by the controls.",
                        gridPos={"x": 12 * (offset % 2), "y": load_bottom + 1 + 8 * (offset // 2), "w": 12, "h": 8})
        template["fieldConfig"]["defaults"]["unit"] = unit
        template["fieldConfig"]["overrides"] = []
        template["targets"] = [{
            "refId": chr(65 + index), "datasource": {"type": "prometheus", "uid": "r4j-prometheus"},
            "editorMode": "code", "format": "time_series", "range": True, "instant": False,
            "expr": expression, "legendFormat": legend,
        } for index, (expression, legend) in enumerate(expressions)]
        dashboard["panels"].append(template)
    dashboard_dir = output / "dashboards"
    dashboard_dir.mkdir(parents=True, exist_ok=True)
    (dashboard_dir / "dashboard.json").write_text(
        json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (dashboard_dir / "classic.json").write_text(
        json.dumps(classic, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "import-dashboard.json").write_bytes(raw)
    manifest = {
        "source": str(source.resolve()),
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "provisioned_uid": dashboard["uid"],
        "importable_uid": original["uid"],
        "datasource_uid": "r4j-prometheus",
        "variables": selections,
        "classic_source": str(classic_source.resolve()),
        "classic_source_sha256": hashlib.sha256(classic_raw).hexdigest(),
        "classic_provisioned_uid": classic["uid"],
        "provisioned_files": ["dashboards/dashboard.json", "dashboards/classic.json"],
    }
    (output / "dashboard-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Smart dashboard: {dashboard_dir / 'dashboard.json'}")
    print(f"Classic dashboard: {dashboard_dir / 'classic.json'}")
    print(f"Importable dashboard: {output / 'import-dashboard.json'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--classic-source", type=Path, default=CLASSIC_SOURCE)
    parser.add_argument("--output-dir", type=Path, default=LAB_DIR / "generated")
    args = parser.parse_args()
    prepare(args.source, args.output_dir, args.classic_source)


if __name__ == "__main__":
    main()
