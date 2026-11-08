"""Source checks only; this does not run Gradle or archive decoders."""

from pathlib import Path
import re
import unittest


SOURCE = Path(__file__).resolve().parents[2]
SCRIPTS = SOURCE / "scripts"


class FixtureReportWiringChecks(unittest.TestCase):
    def test_test_jvm_forwarding_is_limited_to_existing_fixture_properties(self):
        build = (SOURCE / "app/build.gradle").read_text(encoding="utf-8")
        block = build.split(
            "tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {", 1
        )[1].split("\n\n", 1)[0]
        self.assertEqual(
            {
                "textview.externalArchiveFixtureDir",
                "textview.archiveFixtureReportOutDir",
                "textview.rarFixtureReportOutDir",
                "textview.archiveFixturePassword",
                "textview.rar3SolidCbrFixture",
            },
            set(re.findall(r"'(textview\.[^']+)'", block)),
        )
        self.assertIn("providers.systemProperty(propertyName).orNull", block)
        self.assertIn("if (value != null) systemProperty(propertyName, value)", block)
        self.assertNotIn("System.properties", block)
        self.assertNotIn("System.getProperties", block)

    def test_powershell_failure_stops_before_completion_message(self):
        for name in ("archive", "rar"):
            with self.subTest(name=name):
                script = (SCRIPTS / f"generate_{name}_fixture_reports.ps1").read_text()
                self.assertIn("$ErrorActionPreference = 'Stop'", script)
                call = script.index("& (Join-Path $projectRoot 'gradlew.bat')")
                guard = script.index("if ($LASTEXITCODE -ne 0)", call)
                failure = script.index('throw "', guard)
                message = script.index('Write-Host "Fixture test task completed.')
                self.assertLess(call, guard)
                self.assertLess(guard, failure)
                self.assertLess(failure, message)

    def test_powershell_resolves_paths_before_forked_test_reads_them(self):
        for name, output in (("archive", "$OutDir"), ("rar", "$OutputDir")):
            with self.subTest(name=name):
                script = (SCRIPTS / f"generate_{name}_fixture_reports.ps1").read_text()
                self.assertIn("Test-Path -LiteralPath $FixtureDir -PathType Container", script)
                self.assertIn("$FixtureDir = (Resolve-Path -LiteralPath $FixtureDir).ProviderPath", script)
                self.assertIn(f"GetUnresolvedProviderPathFromPSPath({output})", script)

    def test_shell_reports_keep_failure_propagation_and_absolute_paths(self):
        for name in ("archive", "rar"):
            with self.subTest(name=name):
                script = (SCRIPTS / f"generate_{name}_fixture_reports.sh").read_text()
                self.assertIn("set -euo pipefail", script)
                self.assertIn('FIXTURE_DIR="$(cd -- "$FIXTURE_DIR" && pwd -P)"', script)
                self.assertIn('OUT_DIR="$(cd -- "$OUT_DIR" && pwd -P)"', script)
                self.assertLess(script.index('"$PROJECT_DIR/gradlew" --project-dir'),
                                script.index('echo "Fixture test task completed.'))

    def test_wrapper_and_project_are_anchored_without_changing_caller_paths(self):
        for name in ("archive", "rar"):
            with self.subTest(name=name):
                ps = (SCRIPTS / f"generate_{name}_fixture_reports.ps1").read_text()
                self.assertIn("$projectRoot = Split-Path -Parent $PSScriptRoot", ps)
                self.assertIn("--project-dir $projectRoot testDebugUnitTest", ps)
                self.assertLess(ps.index("GetUnresolvedProviderPathFromPSPath"),
                                ps.index("$projectRoot ="))
                self.assertNotIn("Set-Location", ps)
                sh = (SCRIPTS / f"generate_{name}_fixture_reports.sh").read_text()
                self.assertIn('dirname -- "${BASH_SOURCE[0]}"', sh)
                self.assertIn('"$PROJECT_DIR/gradlew" --project-dir "$PROJECT_DIR"', sh)
                self.assertLess(sh.index('OUT_DIR="$(cd --'), sh.index('PROJECT_DIR="$(cd --'))

    def test_all_scripts_distinguish_skipped_fixtures_from_passes(self):
        for name in ("archive", "rar"):
            for suffix in ("ps1", "sh"):
                with self.subTest(name=name, suffix=suffix):
                    script = (SCRIPTS / f"generate_{name}_fixture_reports.{suffix}").read_text()
                    self.assertIn("a skipped fixture does not establish coverage", script)
                    self.assertNotIn("reports written", script)


if __name__ == "__main__":
    unittest.main()
