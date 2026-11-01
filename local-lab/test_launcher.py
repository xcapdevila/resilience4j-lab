#!/usr/bin/env python3
"""Offline launcher checks, using disposable local Git sources and caches."""
import importlib.util
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
        for name in ("PHASED_LIBRARY_DIR", "PHASED_MAVEN_REPO", "PHASED_JAVA_HOME", "JAVA_HOME"):
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
            for module in LAB.LIBRARY_MODULES:
                base = LAB.REPOSITORY / f"io/github/resilience4j/resilience4j-{module}/{LAB.VERSION}"
                base.mkdir(parents=True)
                for extension in ("jar", "pom"):
                    (base / f"resilience4j-{module}-{LAB.VERSION}.{extension}").write_text("fixture")
            with patch.object(LAB, "build_library") as build:
                LAB.ensure_library()
                build.assert_not_called()
                (base / f"resilience4j-{module}-{LAB.VERSION}.pom").unlink()
                LAB.ensure_library()
                build.assert_called_once_with()

    def test_sibling_jdk_is_not_discovered(self):
        sibling = self.root / ".runtimes/jdk-21.0.13+9/Contents/Home/bin"
        sibling.mkdir(parents=True)
        (sibling / "java").write_text("fixture")
        (sibling / "javac").write_text("fixture")
        with patch.object(LAB.sys, "platform", "linux"), patch.object(LAB.shutil, "which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "Java 21 is required"):
                LAB.java_environment()


if __name__ == "__main__":
    unittest.main()
