#!/usr/bin/env python3
"""Offline launcher checks, using disposable local Git sources and caches."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("recovery_lab_launcher", Path(__file__).with_name("lab.py"))
LAB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAB)


class LauncherPortabilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / "public-lab"
        self.project.mkdir()
        self.cache = self.project / ".lab-cache"
        self.environment = patch.dict(os.environ, {
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_AUTHOR_NAME": "Test Author", "GIT_AUTHOR_EMAIL": "test@example.invalid",
            "GIT_COMMITTER_NAME": "Test Author", "GIT_COMMITTER_EMAIL": "test@example.invalid",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        for name in ("PHASED_LIBRARY_DIR", "PHASED_MAVEN_REPO", "PHASED_JAVA_HOME",
                     "PHASED_LIBRARY_JAVA_HOME", "PHASED_GRADLE_HOME", "JAVA_HOME"):
            os.environ.pop(name, None)
        self.constants = patch.multiple(LAB, PROJECT=self.project, CACHE=self.cache,
                                       REPOSITORY=self.cache / "maven/repository")
        self.constants.start()
        self.addCleanup(self.constants.stop)

    def git(self, source, *arguments):
        return subprocess.run(["git", "-c", f"core.hooksPath={os.devnull}", *arguments], cwd=source,
                              check=True, capture_output=True, text=True).stdout.strip()

    def source(self):
        source = self.root / "source"
        source.mkdir()
        self.git(source, "init", "--initial-branch=main")
        (source / "gradlew").write_text("#!/bin/sh\nexit 0\n")
        self.git(source, "add", "gradlew")
        self.git(source, "commit", "-m", "Pinned fixture")
        pinned = self.git(source, "rev-parse", "HEAD")
        (source / "later.txt").write_text("later\n")
        self.git(source, "add", "later.txt")
        self.git(source, "commit", "-m", "Later fixture")
        return source, pinned

    def jdk(self, major, compiler_major=None, reject_jvm_options=False):
        home = self.root / f"jdk-{major}"
        binaries = home / "bin"
        binaries.mkdir(parents=True)
        prefix = "#!/bin/sh\n"
        if reject_jvm_options:
            prefix += ('if [ -n "${JAVA_TOOL_OPTIONS-}${JDK_JAVA_OPTIONS-}${_JAVA_OPTIONS-}'
                       '${JAVA_OPTS-}${GRADLE_OPTS-}" ]; then exit 23; fi\n')
        (binaries / "java").write_text(prefix + f'printf \'%s\\n\' \'openjdk version "{major}.0.1"\' >&2\n')
        (binaries / "javac").write_text(prefix + f'printf \'%s\\n\' \'javac {compiler_major or major}.0.1\' >&2\n')
        for executable in (binaries / "java", binaries / "javac"):
            executable.chmod(0o755)
        return home

    def artifacts(self, modules=None):
        for module in modules or LAB.LIBRARY_MODULES:
            base = LAB.REPOSITORY / f"io/github/resilience4j/resilience4j-{module}/{LAB.VERSION}"
            base.mkdir(parents=True, exist_ok=True)
            for extension in ("jar", "pom"):
                (base / f"resilience4j-{module}-{LAB.VERSION}.{extension}").write_text("fixture")
        return base

    def test_fresh_clone_uses_project_cache_and_exact_commit(self):
        source, pinned = self.source()
        with patch.multiple(LAB, LIBRARY_URL=str(source), LIBRARY_REF=pinned):
            checkout = LAB.library_source()
        self.assertEqual(checkout, self.cache / "sources/resilience4j")
        self.assertEqual(self.git(checkout, "rev-parse", "HEAD"), pinned)
        self.assertFalse((checkout / "later.txt").exists())

    def test_explicit_checkout_is_never_reset_or_cleaned(self):
        source, pinned = self.source()
        original = self.git(source, "rev-parse", "HEAD")
        with patch.dict(os.environ, {"PHASED_LIBRARY_DIR": str(source)}), patch.object(LAB, "LIBRARY_REF", pinned):
            with self.assertRaisesRegex(RuntimeError, "will not reset"):
                LAB.library_source()
            self.assertEqual(self.git(source, "rev-parse", "HEAD"), original)
            (source / "gradlew").write_text("preserve my changes\n")
            with self.assertRaisesRegex(RuntimeError, "tracked changes"):
                LAB.library_source()
            self.assertEqual((source / "gradlew").read_text(), "preserve my changes\n")

    def test_incomplete_explicit_artifact_cache_is_not_treated_as_ready(self):
        with patch.dict(os.environ, {"PHASED_MAVEN_REPO": str(LAB.REPOSITORY)}):
            base = self.artifacts()
            with patch.object(LAB, "build_library") as build:
                LAB.ensure_library()
                build.assert_not_called()
                (base / f"resilience4j-{LAB.LIBRARY_MODULES[-1]}-{LAB.VERSION}.pom").unlink()
                LAB.ensure_library()
                build.assert_called_once_with()

    def test_sibling_jdk_is_not_discovered(self):
        sibling = self.root / ".runtimes/jdk-21.0.13+9/Contents/Home/bin"
        sibling.mkdir(parents=True)
        (sibling / "java").write_text("fixture")
        (sibling / "javac").write_text("fixture")
        with patch.object(LAB.sys, "platform", "linux"), patch.object(LAB.shutil, "which", return_value=None), \
                patch.object(LAB.Path, "glob", return_value=[]):
            for library, major in ((False, 25), (True, 21)):
                with self.subTest(library=library), self.assertRaisesRegex(RuntimeError, f"JDK {major} is required"):
                    LAB.java_environment(library=library)

    def test_application_and_library_builds_use_separate_jdks(self):
        app_jdk, library_jdk = self.jdk(25), self.jdk(21)
        library = self.root / "library"
        original_path = os.environ.get("PATH", "")
        with patch.dict(os.environ, {"PHASED_JAVA_HOME": str(app_jdk),
                                     "PHASED_LIBRARY_JAVA_HOME": str(library_jdk),
                                     "JAVA_HOME": str(library_jdk), "JAVA_OPTS": "ignored",
                                     "GRADLE_OPTS": "ignored", "JAVA_TOOL_OPTIONS": "ignored"}), \
                patch.object(LAB, "library_source", return_value=library), \
                patch.object(LAB, "library_artifacts_present", return_value=True), patch.object(LAB, "run") as run:
            LAB.maven("test")
            LAB.build_library()
            app, native = run.call_args_list
            self.assertEqual(app.args[0][0], self.project / "mvnw")
            self.assertEqual(native.args[0][0], library / "gradlew")
            self.assertEqual(app.kwargs["env"]["JAVA_HOME"], str(app_jdk))
            self.assertEqual(native.kwargs["env"]["JAVA_HOME"], str(library_jdk))
            self.assertEqual(app.kwargs["env"]["PATH"].split(os.pathsep)[0], str(app_jdk / "bin"))
            self.assertEqual(native.kwargs["env"]["PATH"].split(os.pathsep)[0], str(library_jdk / "bin"))
            for call in (app, native):
                for option in ("JAVA_OPTS", "GRADLE_OPTS", "JAVA_TOOL_OPTIONS"):
                    self.assertNotIn(option, call.kwargs["env"])
            self.assertIn(":resilience4j-spring-boot4:publishToMavenLocal", native.args[0])
            self.assertNotIn(":resilience4j-spring-boot3:publishToMavenLocal", native.args[0])
            self.assertEqual(os.environ["JAVA_HOME"], str(library_jdk))
            self.assertEqual(os.environ.get("PATH", ""), original_path)
        stamp = json.loads((self.cache / "native-build.json").read_text())
        self.assertEqual(stamp["javaMajor"], 21)
        self.assertIn("spring-boot4", stamp["modules"])

    def test_wrong_explicit_jdk_never_falls_back_to_another_home(self):
        app_jdk, library_jdk = self.jdk(25), self.jdk(21)
        for library, setting, wrong, fallback, major in (
                (False, "PHASED_JAVA_HOME", library_jdk, app_jdk, 25),
                (True, "PHASED_LIBRARY_JAVA_HOME", app_jdk, library_jdk, 21)):
            with self.subTest(library=library), patch.dict(os.environ, {setting: str(wrong), "JAVA_HOME": str(fallback)}):
                with self.assertRaisesRegex(RuntimeError, f"JDK {major} is required.*{setting}"):
                    LAB.java_environment(library=library)

    def test_library_can_discover_java_home_21_while_application_override_is_25(self):
        app_jdk, library_jdk = self.jdk(25), self.jdk(21)
        with patch.dict(os.environ, {"PHASED_JAVA_HOME": str(app_jdk), "JAVA_HOME": str(library_jdk)}), \
                patch.object(LAB.sys, "platform", "linux"), patch.object(LAB.shutil, "which", return_value=None), \
                patch.object(LAB.Path, "glob", return_value=[]):
            self.assertEqual(LAB.java_environment()["JAVA_HOME"], str(app_jdk))
            self.assertEqual(LAB.java_environment(library=True)["JAVA_HOME"], str(library_jdk))

    def test_mismatched_compiler_is_not_accepted_as_jdk_25(self):
        home = self.jdk(25, compiler_major=21)
        with patch.dict(os.environ, {"PHASED_JAVA_HOME": str(home)}):
            with self.assertRaisesRegex(RuntimeError, "JDK 25 is required"):
                LAB.java_environment()

    def test_jdk_probes_ignore_poisoned_inherited_jvm_options(self):
        app_jdk, library_jdk = self.jdk(25, reject_jvm_options=True), self.jdk(21, reject_jvm_options=True)
        poisoned = {name: "-XX:InvalidInheritedOption" for name in (
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS")}
        with patch.dict(os.environ, {**poisoned, "PHASED_JAVA_HOME": str(app_jdk),
                                     "PHASED_LIBRARY_JAVA_HOME": str(library_jdk)}):
            for library, expected in ((False, app_jdk), (True, library_jdk)):
                with self.subTest(library=library):
                    env = LAB.java_environment(library=library)
                    self.assertEqual(env["JAVA_HOME"], str(expected))
                    for name, value in poisoned.items():
                        self.assertNotIn(name, env)
                        self.assertEqual(os.environ[name], value)

    def test_macos_jdk_discovery_receives_sanitized_environment(self):
        app_jdk, library_jdk = self.jdk(25), self.jdk(21)
        original_run = subprocess.run
        discovery = []

        def probe(command, **kwargs):
            self.assertNotIn("JAVA_TOOL_OPTIONS", kwargs["env"])
            if command[0] == "/usr/libexec/java_home":
                discovery.append(command)
                home = library_jdk if command[-1] == "21" else app_jdk
                return subprocess.CompletedProcess(command, 0, stdout=str(home) + "\n", stderr="")
            return original_run(command, **kwargs)

        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "-XX:InvalidInheritedOption"}), \
                patch.object(LAB.sys, "platform", "darwin"), patch.object(LAB.shutil, "which", return_value=None), \
                patch.object(LAB.subprocess, "run", side_effect=probe):
            self.assertEqual(LAB.java_environment()["JAVA_HOME"], str(app_jdk))
            self.assertEqual(LAB.java_environment(library=True)["JAVA_HOME"], str(library_jdk))
            self.assertEqual(os.environ["JAVA_TOOL_OPTIONS"], "-XX:InvalidInheritedOption")
        self.assertEqual([command[-1] for command in discovery], ["25", "21"])

    def test_boot3_only_artifacts_cannot_satisfy_boot4_application(self):
        self.artifacts(tuple("spring-boot3" if module == "spring-boot4" else module for module in LAB.LIBRARY_MODULES))
        with patch.dict(os.environ, {"PHASED_MAVEN_REPO": str(LAB.REPOSITORY)}), patch.object(LAB, "build_library") as build:
            LAB.ensure_library()
            build.assert_called_once_with()

    def test_native_cache_identity_tracks_adapter_set_and_java_major(self):
        self.artifacts()
        stamp = self.cache / "native-build.json"
        identity = LAB.library_build_identity()
        for obsolete in ({key: value for key, value in identity.items() if key not in ("modules", "javaMajor")},
                         {**identity, "modules": ["spring-boot3"]}, {**identity, "javaMajor": 25}):
            with self.subTest(obsolete=obsolete):
                stamp.write_text(json.dumps(obsolete))
                with patch.object(LAB, "build_library") as build:
                    LAB.ensure_library()
                    build.assert_called_once_with()
        stamp.write_text(json.dumps(identity))
        with patch.object(LAB, "build_library") as build:
            LAB.ensure_library()
            build.assert_not_called()


if __name__ == "__main__":
    unittest.main()
