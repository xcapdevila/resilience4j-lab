#!/usr/bin/env python3
"""Build, run and inspect the repository's local CircuitBreaker lab."""
import argparse
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.request import ProxyHandler, Request, build_opener

LAB = Path(__file__).resolve().parent
PROJECT = LAB.parent
CACHE = PROJECT / ".lab-cache"
VERSION = os.environ.get("PHASED_VERSION", "3.0.0-phased-SNAPSHOT")
LIBRARY_URL = os.environ.get("PHASED_LIBRARY_URL", "https://github.com/xcapdevila/resilience4j.git")
LIBRARY_REF = os.environ.get("PHASED_LIBRARY_REF", "12a8bc6b6487e50af35a25759a20693965616ddf")
APPLICATION_JAVA_MAJOR = 25
LIBRARY_JAVA_MAJOR = 21
LIBRARY_MODULES = ("core", "circularbuffer", "annotations", "consumer", "circuitbreaker", "bulkhead",
                   "ratelimiter", "retry", "timelimiter", "micrometer", "framework-common", "spring6", "spring-boot4")
REPOSITORY = Path(os.environ.get("PHASED_MAVEN_REPO", str(CACHE / "maven/repository"))).expanduser().resolve()
OPENER = build_opener(ProxyHandler({}))


def run(command, *, cwd=PROJECT, env=None, capture=False):
    return subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True,
                          text=True, stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None)


def java_environment(*, library=False):
    """Select each build's JDK independently; never run the native Gradle build on 25."""
    env = os.environ.copy()
    for name in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    major = LIBRARY_JAVA_MAJOR if library else APPLICATION_JAVA_MAJOR
    setting = "PHASED_LIBRARY_JAVA_HOME" if library else "PHASED_JAVA_HOME"
    purpose = "the Resilience4j library build" if library else "the application"
    override = env.get(setting)
    candidates = [Path(override).expanduser() / "bin/java"] if override else []
    if not override:
        if env.get("JAVA_HOME"):
            candidates.append(Path(env["JAVA_HOME"]) / "bin/java")
        if sys.platform == "darwin":
            located = subprocess.run(["/usr/libexec/java_home", "-v", str(major)], capture_output=True, text=True, env=env)
            if located.returncode == 0:
                candidates.append(Path(located.stdout.strip()) / "bin/java")
        if shutil.which("java"):
            candidates.append(Path(shutil.which("java")).resolve())
        if sys.platform.startswith("linux"):
            for directory in (Path("/usr/lib/jvm"), Path("/usr/java")):
                candidates.extend(sorted(directory.glob("*/bin/java")))
    for candidate in candidates:
        if not candidate.is_file() or not (candidate.parent / "javac").is_file():
            continue
        version = subprocess.run([str(candidate), "-version"], capture_output=True, text=True, env=env)
        compiler = subprocess.run([str(candidate.parent / "javac"), "-version"], capture_output=True, text=True, env=env)
        if (version.returncode == 0 and compiler.returncode == 0
                and re.search(rf'version "{major}(?:\.|\")', version.stdout + version.stderr)
                and re.search(rf'\bjavac {major}(?:\.|\s|$)', compiler.stdout + compiler.stderr)):
            env["JAVA_HOME"] = str(candidate.parent.parent)
            env["PATH"] = str(candidate.parent) + os.pathsep + env.get("PATH", "")
            break
    else:
        raise RuntimeError(f"JDK {major} is required for {purpose}. Set {setting} to a JDK {major} directory.")
    env["MAVEN_SKIP_RC"] = "true"
    env["MAVEN_USER_HOME"] = str(CACHE / "maven")
    return env


def maven(goal, extra=()):
    command = [PROJECT / "mvnw", "--settings", LAB / "maven-settings.xml",
               "--global-settings", LAB / "maven-settings.xml",
               f"-Dmaven.repo.local={REPOSITORY}", f"-Dresilience4j.version={VERSION}"]
    if goal == "package":
        command.append("-DskipTests")
    run([*command, goal, *extra], env=java_environment())


def library_source():
    """Prepare the pinned public source, without changing an explicit user checkout."""
    if not re.fullmatch(r"[0-9a-fA-F]{40}", LIBRARY_REF):
        raise RuntimeError("PHASED_LIBRARY_REF must be a full 40-digit Git commit.")
    explicit = os.environ.get("PHASED_LIBRARY_DIR")
    library = Path(explicit).expanduser().resolve() if explicit else CACHE / "sources/resilience4j"
    fresh = not library.exists()
    if fresh:
        if explicit:
            raise RuntimeError("PHASED_LIBRARY_DIR must point to an existing Resilience4j Git checkout.")
        library.parent.mkdir(parents=True, exist_ok=True)
        print(f"Cloning public Resilience4j source at {LIBRARY_REF}…", flush=True)
        run(["git", "clone", "--no-checkout", "--filter=blob:none", "--no-tags", LIBRARY_URL, library])
    if not (library / ".git").exists():
        raise RuntimeError(f"Resilience4j source is not a Git checkout: {library}")
    dirty = "" if fresh else run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=library, capture=True).stdout.strip()
    if dirty:
        raise RuntimeError(f"Resilience4j checkout has tracked changes: {library}. Preserve or commit them before building.")
    head = run(["git", "rev-parse", "HEAD"], cwd=library, capture=True).stdout.strip()
    if fresh or head.lower() != LIBRARY_REF.lower():
        if explicit:
            raise RuntimeError(f"PHASED_LIBRARY_DIR is at {head}; expected {LIBRARY_REF}. The launcher will not reset your checkout.")
        run(["git", "fetch", "--depth", "1", "origin", LIBRARY_REF], cwd=library)
        run(["git", "checkout", "--detach", LIBRARY_REF], cwd=library)
    actual = run(["git", "rev-parse", "HEAD"], cwd=library, capture=True).stdout.strip()
    if actual.lower() != LIBRARY_REF.lower() or not (library / "gradlew").is_file():
        raise RuntimeError("The pinned Resilience4j checkout could not be verified.")
    return library


def library_artifacts_present():
    return all((REPOSITORY / f"io/github/resilience4j/resilience4j-{module}/{VERSION}/resilience4j-{module}-{VERSION}.{extension}").is_file()
               for module in LIBRARY_MODULES for extension in ("jar", "pom"))


def library_build_identity():
    return {"source": os.environ.get("PHASED_LIBRARY_DIR", LIBRARY_URL), "commit": LIBRARY_REF.lower(),
            "version": VERSION, "repository": str(REPOSITORY),
            "modules": list(LIBRARY_MODULES), "javaMajor": LIBRARY_JAVA_MAJOR}


def ensure_library():
    stamp = CACHE / "native-build.json"
    if library_artifacts_present():
        if os.environ.get("PHASED_MAVEN_REPO"):
            if os.environ.get("PHASED_LIBRARY_DIR"):
                library_source()
            return
        try:
            if json.loads(stamp.read_text()) == library_build_identity():
                return
        except (OSError, ValueError):
            pass
    build_library()


def build_library(extra=()):
    library = library_source()
    env = java_environment(library=True)
    for key in ("GPG_KEY", "GPG_PASSPHRASE"):
        env.pop(key, None)
    env["GRADLE_USER_HOME"] = str(Path(os.environ.get("PHASED_GRADLE_HOME", str(CACHE / "gradle"))).expanduser().resolve())
    run([library / "gradlew", "--no-daemon", "--max-workers=2",
         *[f":resilience4j-{module}:publishToMavenLocal" for module in LIBRARY_MODULES],
         f"-Dmaven.repo.local={REPOSITORY}", f"-Pversion={VERSION}", *extra], cwd=library, env=env)
    if not library_artifacts_present():
        raise RuntimeError("The native build did not produce all required Resilience4j artifacts.")
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / "native-build.json").write_text(json.dumps(library_build_identity(), indent=2) + "\n")


def build_app(extra=()):
    ensure_library()
    maven("package", extra)
    run([sys.executable, LAB / "prepare-dashboard.py"])


def ports():
    result = {}
    for name, default in (("APP", 8080), ("PROMETHEUS", 9090), ("GRAFANA", 3000)):
        value = int(os.environ.get(f"LAB_{name}_PORT", default))
        if not 1 <= value <= 65535:
            raise RuntimeError(f"LAB_{name}_PORT must be between 1 and 65535.")
        result[name] = value
    return result


def docker():
    executable = shutil.which("docker")
    if not executable and (Path.home() / ".rd/bin/docker").is_file():
        executable = str(Path.home() / ".rd/bin/docker")
    if not executable:
        raise RuntimeError("Docker Compose is required. Start Docker Desktop or Rancher Desktop.")
    endpoint = os.environ.get("DOCKER_HOST") or run(
        [executable, "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], capture=True).stdout.strip()
    if not endpoint.startswith("unix://"):
        raise RuntimeError("This lab requires a local Docker Unix socket. Select a local Docker context.")
    return executable


def compose(*arguments):
    run([docker(), "compose", "--project-name", "r4j-lab", "--file", LAB / "compose.yaml", *arguments])


def urls(values):
    return {"app": f"http://localhost:{values['APP']}/demo/lab/",
            "prometheus": f"http://localhost:{values['PROMETHEUS']}",
            "grafana": f"http://localhost:{values['GRAFANA']}/d/r4j-local-lab"}


def wait_ready(url):
    deadline = time.monotonic() + 60
    while True:
        try:
            with OPENER.open(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, HTTPException):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Service is not ready: {url}. Run ./local-lab/lab.sh logs.")
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["bootstrap", "start", "stop", "status", "logs", "scenario", "build", "build-library", "test", "verify"])
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    values = ports()
    links = urls(values)
    if args.command in ("bootstrap", "build"):
        build_app(args.arguments)
    elif args.command == "test":
        ensure_library()
        maven("test", args.arguments)
    elif args.command == "build-library":
        build_library(args.arguments)
    elif args.command == "start":
        run([docker(), "info"], capture=True)
        build_app()
        compose("up", "--detach", "--force-recreate")
        print("Waiting for the app, Prometheus and Grafana…", flush=True)
        for url in (links["app"] + "api/status", links["prometheus"] + "/-/ready",
                    f"http://localhost:{values['GRAFANA']}/api/health"):
            wait_ready(url)
        for name, url in links.items():
            print(f"{name}: {url}")
        print("Grafana login: admin / local-demo (local development only).")
    elif args.command == "stop":
        compose("down")
    elif args.command == "status":
        compose("ps")
        for name, url in links.items():
            print(f"{name}: {url}")
    elif args.command == "logs":
        compose("logs", "--tail", "60", *args.arguments)
    elif args.command == "scenario":
        if len(args.arguments) != 1 or args.arguments[0] not in ("cycle", "outage", "healthy", "step-back", "pause"):
            parser.error("scenario requires one of: cycle, outage, healthy, step-back, pause")
        request = Request(
            links["app"] + "api/scenario/" + args.arguments[0], data=b"", method="POST")
        with OPENER.open(request, timeout=5) as response:
            state = json.load(response)
            print(json.dumps({key: state[key] for key in ("scenario", "stage", "running", "configuration")}, indent=2))
    elif args.command == "verify":
        run([sys.executable, LAB / "verify-lab.py", "--app-url", links["app"].removesuffix("/lab/"),
             "--prometheus-url", links["prometheus"], "--grafana-url", f"http://localhost:{values['GRAFANA']}", *args.arguments])


if __name__ == "__main__":
    if sys.version_info < (3, 9):
        sys.exit("Python 3.9 or newer is required.")
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Lab error: {error}", file=sys.stderr)
        sys.exit(1)
