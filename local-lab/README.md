# Local recovery lab guide

This companion application sends real HTTP requests through two independent native
Resilience4j circuit breakers to a simulated dependency. The lab includes its source,
traffic generator, controls, dashboards and local Prometheus/Grafana stack.

## Start the lab

Follow the [project quick start](../README.md#quick-start) to build the pinned public
library sources, then run from the project root:

```sh
./local-lab/lab.sh start
```

Use Java 21, Python 3.9+, Git and a running local Docker engine with Compose. The
launcher targets macOS, Linux and WSL with a local Docker Unix socket. It obtains
Maven through the checked-in wrapper. `bootstrap` prepares the pinned public library,
application and dashboards without starting containers. `start` also prepares missing
prerequisites, then starts the services. The first start downloads build dependencies
and container images; later starts reuse project-local caches and service data volumes.

| Service | Default address |
|---|---|
| Application controls | http://localhost:8080/demo/lab/ |
| Combined classic and smart dashboard | http://localhost:3000/d/r4j-local-lab |
| Classic dashboard | http://localhost:3000/d/r4j-local-classic |
| Prometheus | http://localhost:9090 |
| Application metrics | http://localhost:8080/demo/actuator/prometheus |

The **Automatic cycle** repeats an outage and recovery. Other scenarios select a
healthy dependency, an outage, errors during recovery or paused traffic. All failures
and latency are synthetic; the application does not call a payment provider or other
external business service.

## Source and build settings

The default library source is the public `xcapdevila/resilience4j` fork at the full
commit pinned in the [project quick start](../README.md#quick-start). The custom
artifact version is `3.0.0-phased-SNAPSHOT`.

| Setting | Default / purpose |
|---|---|
| `PHASED_JAVA_HOME` | Optional JDK 21 directory; otherwise use a detected Java 21 installation |
| `PHASED_LIBRARY_URL` | `https://github.com/xcapdevila/resilience4j.git` |
| `PHASED_LIBRARY_REF` | Full 40-character commit ID; defaults to the pinned public revision |
| `PHASED_LIBRARY_DIR` | Optional existing library checkout instead of managed `.lab-cache/sources/resilience4j` |
| `PHASED_VERSION` | Custom library artifact version, default `3.0.0-phased-SNAPSHOT` |
| `PHASED_MAVEN_REPO` | Maven artifact repository, default `.lab-cache/maven/repository` |
| `PHASED_GRADLE_HOME` | Gradle cache, default `.lab-cache/gradle` |

The launcher uses project-local Maven wrapper storage and explicit Maven settings.
It does not modify global Maven or Gradle configuration. `bootstrap`, `start`, `build`
and `test` prepare missing library artifacts; `build-library` explicitly rebuilds
them. A selected prebuilt Maven repository can supply all required library JARs and
POMs without a source build. The packaged application is
`target/resilience4j-lab.jar`.

The managed source cache is checked out at the selected commit. The launcher refuses
dirty tracked source files. An explicit `PHASED_LIBRARY_DIR` is never reset and must
already be clean at the selected commit. For example, to use a committed local
library change:

```sh
export PHASED_LIBRARY_DIR=/path/to/resilience4j
export PHASED_LIBRARY_REF="$(git -C "$PHASED_LIBRARY_DIR" rev-parse HEAD)"
./local-lab/lab.sh build-library
./local-lab/lab.sh start
```

Keep the same overrides for later commands. `stop` only stops the lab's Compose
project, `r4j-lab`; it keeps service data volumes.

## Configure a trial

The page separates settings that can change **live** from settings that need a **fresh recovery trial**. The active settings remain visible below the scenario controls. Edited fields are an unapplied draft; polling does not overwrite them.

### Traffic and simulated service: apply live

Use **Apply traffic and service live** to change traffic during a recovery. This does not reset the circuit state, current phase, samples or admission counters. It lets you see the effect of changing offered load on the time needed to collect samples. Each circuit has its own rate and concurrency budget.

| Control | Accepted values | Meaning |
|---|---|---|
| Requests per second, per circuit | Integer 0–1,000 | Target offered request rate for each circuit; 0 generates no requests |
| Maximum in-flight requests per circuit | Integer 1–64 | Bound on concurrent HTTP requests; excess demand is skipped rather than queued |
| Healthy / recovery success | Integer 0–100% | Backend success ratio while healthy or recovering |
| Outage success | Integer 0–100% | Backend success ratio during the outage portion of a scenario |
| Recovery-error success | Integer 0–100% | Backend success ratio for the failure pulse used to compare strategies |
| Baseline latency | Integer 0–5,000 ms | Delay inside each actual circuit-protected call, including failures |
| Calls receiving extra latency | Integer 0–100% | Share of calls that receive an additional delay |
| Extra latency for selected calls | Integer 0–5,000 ms | Additional delay; baseline plus extra delay must not exceed 5,000 ms |
| Healthy part of cycle | 0.1–3,600 seconds | Healthy period before an automatic outage |
| Outage duration | 0.1–3,600 seconds | Outage period before restoring the dependency |
| Minimum recovery hold | 0–3,600 seconds | Minimum recovery period before cycling; recovery must also reach `CLOSED` |

Success ratios and latency mixes between 0% and 100% are probabilistic. Ratios 0% and 100% provide deterministic failure and success outcomes. The target RPS is not a throughput guarantee: actual offered RPS can be lower because of scheduling, latency, the concurrency cap or local capacity. The page and Grafana show actual traffic, in-flight requests, skipped demand and completions separately.

### Recovery and circuit policy: apply and restart

Use **Apply and restart trial** to apply the complete draft. Recovery changes create a fresh smart circuit and restart the selected scenario. Shared circuit-policy changes also rebuild the standard circuit so the comparison uses the selected thresholds. Paused traffic stays paused. Smart admission counters restart from zero; Grafana keeps its history.

The status API identifies the process, trial revision and `trialStartedAtEpochMillis` boundary. A live traffic update preserves that boundary. The verifier uses it to exclude earlier trials even when their Prometheus history and transition events remain visible.

| Control | Accepted values | Meaning |
|---|---|---|
| Traffic step | Integer 1–100 percentage points | Increment between recovery phases |
| Minimum phase duration | 0.1–3,600 seconds | Minimum time before promotion or final 100% validation |
| Minimum samples per phase | Integer 1–100,000 | Size of the completed-call outcome window required for evaluation |
| Failure strategy | `OPEN` or `STEP_BACK` | Response to severe recovery health, or a failed final 100% validation |
| Initial target | Integer 0–100% | 0 uses the traffic step; 1–100 specifies the first admitted percentage |
| Advance: maximum failures | 0–100%, below regression | Smart recovery can advance only at or below this failure rate (default 5%) |
| Regress: failures at or above | Above advance maximum, up to 100% | Smart recovery applies its failure strategy at or above this rate (default 20%) |
| Advance: maximum slow calls | 0–100%, below regression | Smart recovery can advance only at or below this slow-call rate (default 10%) |
| Regress: slow calls at or above | Above advance maximum, up to 100% | Smart recovery applies its failure strategy at or above this rate (default 50%) |
| Normal failure-rate threshold | 0.1–100% | Ordinary CLOSED / standard HALF_OPEN failure threshold; also guards final 100% closure |
| Normal slow-call rate threshold | 0.1–100% | Ordinary CLOSED / standard HALF_OPEN slow-call threshold; also guards final 100% closure |
| Slow-call duration threshold | Integer 1–60,000 ms | Duration beyond which an executed call is classified as slow |
| OPEN wait duration | 0.1–60 seconds | Wait before another recovery attempt |
| HALF_OPEN timeout | 0–3,600 seconds | Maximum total recovery episode; 0 disables the timeout |
| Closed-state window | Integer 1–100,000 calls | Count-based outcome window while closed |
| Closed-state minimum samples | Integer 1–window size | Samples required before evaluating the closed-state window |
| Standard HALF_OPEN permitted calls | Integer 1–100,000 | Finite probe allowance for the standard circuit |
| Automatic OPEN → HALF_OPEN transition | On / off | Whether the circuit's timer can enter HALF_OPEN without an arriving request |

Completed successes and failures contribute samples. Rejected permission attempts and ignored outcomes do not. Smart recovery has independent advance and regression bands; changing them does not change the normal circuit thresholds. The slow-call duration still classifies calls in both modes.

Every health decision requires a full rolling phase window of `minimumNumberOfCalls`. Below 100%, advancement requires **both** rates at or below their advance maxima, the minimum phase duration, and a newly completed recorded outcome. A window between health bands holds that intermediate target with continuous admissions. A severe window, with **either** rate at or above its recovery regression threshold, applies the failure strategy as soon as the window is full, even before minimum duration expires.

The 100% phase has a final validation instead of an indefinite hold band. Once its full sample window and minimum duration are satisfied, a fresh recorded completion closes the circuit only when both smart advance maxima pass **and** both rates are strictly below the normal failure/slow thresholds. Otherwise it applies the configured failure strategy: `STEP_BACK` retries the preceding phase, or reopens when no preceding phase exists; `OPEN` reopens directly. Normal CLOSED and standard HALF_OPEN evaluation remain unchanged.

Minimum duration starts at phase entry. It does not require an uninterrupted healthy interval, and an intermediate hold does not restart it. Time alone never changes a phase: a fresh recorded completion must evaluate the window after the duration has elapsed. Without enough samples or new completions, even 100% can remain pending until the configured global HALF_OPEN timeout, if enabled.

For example, with 100 samples, failure advance maximum 5% and regression threshold 20%, 5 failures still allow advancement below 100%, 6–19 failures hold an intermediate phase, and 20 failures invoke `STEP_BACK` or `OPEN` as soon as the full window is available. At 100%, 8 failures out of 100 keep validation pending before minimum duration; after that duration, a fresh completion evaluates the window and applies the failure strategy if it still fails the closure criteria. A 25-point ramp with `STEP_BACK` returns to 75%. Slow-call health must also pass before advancing or closing and can independently trigger regression. If the normal failure threshold is stricter, for example 1%, even a 3% failure window that passes the smart 5% maximum cannot close. With only 10 samples, one failure is already 10%; sample size therefore matters as much as the percentages. Zero is supported as an advance maximum to require a clean observed window, while the regression threshold must remain higher. These are sample-based health rules, not a guarantee of the backend's true failure probability. At a low admission percentage, collecting a large window can take much longer than the minimum duration.

The smart card shows the actual completed sample count, current failure and slow-call rates, the sampled health band, and the earliest promotion or final validation time. The estimate uses actual offered traffic, the active admission target, and the phase's minimum duration. It is not a timer or deadline: an intermediate phase can keep holding, while a sufficiently sampled 100% phase validates on a fresh completion after minimum duration and either closes or applies its failure strategy. Latency, pending outcomes, failures, concurrency limits and rate changes can alter the estimate. The health-band label is a sampled view of the window; native transitions are decided when recorded calls complete. No offered traffic means that an estimate may be unavailable.

Settings stay in memory until the application restarts. Use JSON export to retain a trial configuration.

### Repeatable presets and saved configurations

**Load draft** fills the controls without applying them. Available presets are:

- **Defaults:** project defaults, including 100 requested requests/s per circuit and a 25% recovery step.
- **Quick recovery:** four short phases with enough offered traffic to collect samples quickly.
- **Low traffic / sample limited:** 5 requested requests/s, a 10% first target and 20 samples. At a healthy stable first phase, sample collection alone takes about 40 seconds.
- **Fast PSP demo:** short illustrative phases after a mostly failing dependency. The dependency is a simplified success/latency simulation.
- **PSP recovery review:** 25-point steps, 100 samples, 60 seconds per phase, `STEP_BACK`, failure advance/regression 5/20%, slow advance/regression 10/50%, and a 2-second slow-call duration. It requests 100 calls/s with a 64-request concurrency budget and 400 ms backend latency. A cycle has 60 seconds healthy traffic, a 120-second outage, a 30-second OPEN wait, and at least 300 seconds of recovery hold. The four healthy phases alone require at least four minutes. This preset validates real-time phase and health gates. It does not model provider queues, caller deadlines or an independently changing capacity curve. Loading the preset only edits the draft; apply it when ready.
- **Slow-call failures:** successful responses can still trip the circuit because enough calls exceed the slow-call threshold. Reduce the extra-latency percentage live to allow recovery.
- **Concurrency / backpressure:** high requested RPS, latency and a small concurrency budget expose the difference between requested and actual offered load.

**Export draft JSON** downloads the current form, including unapplied edits. **Import JSON** accepts only known settings, validates types and ranges, and loads an unapplied draft. Partial imports merge with the current active configuration. Files are limited to 64 KiB. For example:

```json
{
  "schemaVersion": 1,
  "settings": {
    "requestsPerSecond": 20,
    "trafficPercentageStep": 10,
    "minimumPhaseDurationMillis": 1000,
    "minimumNumberOfCalls": 20,
    "failureStrategy": "OPEN",
    "recoveryMaxFailureRateForAdvance": 5,
    "recoveryFailureRateThreshold": 20,
    "recoveryMaxSlowCallRateForAdvance": 10,
    "recoverySlowCallRateThreshold": 50
  }
}
```

Use **Use active values** in either group to discard that group's pending edits. Applying live traffic does not accidentally apply a separate recovery draft.

### Compare failure strategies

**Errors during recovery** uses the same failure scenario for both strategies. After the initial outage, it restores the dependency, waits for the first phase beyond the initial target, and injects the configured recovery-error outcomes:

- `OPEN` reopens the circuit when the phase becomes unhealthy.
- `STEP_BACK` retries the preceding phase; if none exists, it reopens.

If the initial target is 100%, there is only one phase: both strategies reopen on severe health after a full window, or after a failed final validation once its minimum duration is reached. The simulated dependency becomes healthy again after the lab observes the circuit's response. A mild failure pulse between the health bands holds phases below 100%; at 100% it leads to a failed validation after the minimum duration if the current window still fails the closure criteria. Severe recovery thresholds can exercise the response sooner. The automatic cycle waits for actual `CLOSED`, so small steps, long durations and large sample windows can finish.

## Read the dashboards

Select `DemoService.process` for gradual recovery or `DemoService.processStandard` for standard recovery. The gradual circuit keeps the public state `half_open`; the phase, target and observed admission show its progress. Observed admission uses actual counters over a time window and takes a few seconds to reflect a change. An inactive phase or target of zero means that no recovery is in progress. Smart-only panels have no data for the standard circuit because it does not export those metrics.

The main dashboard combines the smart detail with **Standard Resilience4j metrics**: failure and slow-call rates, calls by outcome, rejected attempts, outcome windows, and average and recent maximum durations. These panels adapt the CircuitBreaker section of the original Resilience4j dashboard and query the same native metrics for both circuits. **Classic metrics** provides a separate view with public state history. Header links retain the selected circuit and time range when switching views.

State history samples the public gauge every second. Standard `HALF_OPEN` can complete between scrapes; the application controls also show the complete transition events. Durations cover executed calls. The maximum is the recent Micrometer timer maximum. Slow-call panels become active when the simulated latency exceeds the selected slow-call duration threshold. The added load panels compare requested and actual offered RPS, in-flight work, skipped demand and completions. Failure-rate and slow-call-rate stats use neutral colors because their thresholds are configurable. Read the current outcome-window percentages against the active controls: smart recovery has separate advance and regression health bands, while normal CLOSED and standard HALF_OPEN keep the ordinary thresholds. A phase transition starts a fresh recovery window.

## Import the original smart dashboard

The provisioned main dashboard uses UID `r4j-local-lab`. The importable source keeps UID `r4j-gradual-recovery`, so it can be imported separately:

1. Use **Download dashboard** in the controls, or select `local-lab/generated/import-dashboard.json`.
2. Sign in to Grafana with **admin / local-demo**, the credentials for this local lab.
3. Open **Dashboards → New → Import** and upload the JSON file.
4. Select **Prometheus** as the data source and choose **Import**.
5. Select job `resilience4j-demo`, instance `app:8080` and circuit `DemoService.process`.

If that UID already exists, update the existing import or use a different UID for another copy. The generator preserves the original import JSON and adapts separate copies for local provisioning. Run `start` again after changing the source dashboards. Grafana polls the generated files every 30 seconds, which also works with shared folders in Rancher Desktop and Docker Desktop.

## Inspect, test and stop

```sh
./local-lab/lab.sh status
./local-lab/lab.sh scenario outage
./local-lab/lab.sh scenario healthy
./local-lab/lab.sh scenario step-back
./local-lab/lab.sh scenario cycle
./local-lab/lab.sh scenario pause
./local-lab/lab.sh logs
./local-lab/lab.sh test
./local-lab/lab.sh verify
./local-lab/lab.sh stop
```

`pause` stops generating requests while the services and circuit timers continue. The `step-back` scenario command runs **Errors during recovery** with the selected failure strategy. `stop` shuts down this lab's Compose project and preserves its data volumes. `start` starts it again.

`verify` only reads the services. It waits up to three minutes for a completed recovery in the **current trial**, writes `generated/live-verification.json`, and exits with `INCOMPLETE` if that evidence is unavailable. For a repeatable check, select **Automatic cycle** with healthy recovery outcomes and nonzero traffic. Large sample windows, long phases, an outage, paused traffic or zero requested RPS can legitimately need longer or prevent recovery; `verify` does not change those settings to make a check pass.

The report guards the raw Prometheus scrape timestamp against the trial boundary and excludes admission-rate windows that cross that boundary. Native transition/phase events supplement one-second scrapes when a short phase is missed. A configuration starting at 100% does not require an intermediate target. A trial restart or process replacement during verification invalidates the result. The report states sampled percentage coverage separately: native recovery events prove the transition, but cannot prove an observed admission percentage that was never scraped. Idle intervals have no percentage. Counter snapshots are independent reads and are not compared as an atomic cohort.

Run the offline verifier regression fixtures without any services:

```sh
python3 -m unittest discover -s local-lab -p 'test_*.py'
```

All three ports bind to `127.0.0.1`. Grafana supports anonymous viewing; importing or editing dashboards requires signing in. Application controls and the traffic generator are enabled by the Spring `lab` profile.

If a port is already in use, choose another one before starting and retain those values for later commands:

```sh
export LAB_APP_PORT=18080
export LAB_PROMETHEUS_PORT=19090
export LAB_GRAFANA_PORT=13000
./local-lab/lab.sh start
```

## Versioned project files

- `lab.sh`: project-local launcher, build and test entry point.
- `lab.py`: launcher implementation using an isolated Maven repository and explicit settings.
- `verify-lab.py`: checks the running application, Prometheus and Grafana.
- `test_verify_lab.py`: offline fixtures for trial isolation, fast phases, initial 100%, idle traffic and counter-scrape timing.
- `compose.yaml`: the three services and this project's volumes.
- `prometheus.yml`: one-second scrapes, with retention bounded to six hours and 512 MB.
- `grafana/provisioning/`: data source and dashboard provisioning.
- `dashboards/recovery.json` and `dashboards/classic.json`: tracked source dashboards.
- `prepare-dashboard.py`: prepares both views and records source hashes in `generated/dashboard-manifest.json`.
- `generated/dashboards/`: only the two provisioned JSON files read by Grafana.
- `generated/`: ignored build output; the import JSON and manifest stay outside the provisioned dashboard directory.

The dashboard sources come from the public Resilience4j fork: `grafana_recovery_dashboard.json` is tracked as `dashboards/recovery.json`, and `grafana_dashboard.json` as `dashboards/classic.json`. See [NOTICE](../NOTICE) for attribution. Dashboard generation reads these checked-in copies and records their hashes in its manifest. Review and refresh the source copies explicitly when updating dashboards. `prepare-dashboard.py --source PATH --classic-source PATH` can generate a trial from other source files.

The container images use fixed multi-architecture digests with ARM64 support: Grafana 13.2.4, Prometheus 3.15.1 and Eclipse Temurin Java 21 JRE. Sources: [Grafana](https://github.com/grafana/grafana/releases/tag/v13.2.4), [Prometheus](https://github.com/prometheus/prometheus/releases/tag/v3.15.1) and [official Temurin image](https://hub.docker.com/_/eclipse-temurin).
