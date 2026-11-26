"""Exercise verify-tests-ran.sh (the Stop and SubagentStop gate) in throwaway git repos.

Run: PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_verify_tests_ran.py
Phase 11 C08: the main session (Stop) may finish while a live sbt process works in this
repository; everything else keeps the stale-marker refusal. The "live sbt" process is a
short sleep run through a symlink named java with an sbt launcher argument; each test
starts it in the foreground of the test and kills it before returning.
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


HOOK = Path(__file__).with_name("verify-tests-ran.sh")
SLEEP = "import time; time.sleep(60)"


class StopHookCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name, "repo")
        self.repo.mkdir()
        git = ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        (self.repo / "README.md").write_text("x\n")
        subprocess.run(git + ["add", "README.md"], cwd=self.repo, check=True)
        subprocess.run(git + ["commit", "-qm", "init"], cwd=self.repo, check=True)
        (self.repo / ".claude").mkdir()
        self.procs = []

    def tearDown(self):
        for p in self.procs:
            p.kill()
            p.wait()
        self.tmp.cleanup()

    def change_source(self):
        (self.repo / "A.scala").write_text("object A\n")

    def marker(self, age):
        m = self.repo / ".claude" / ".last-test-run"
        m.write_text("")
        t = time.time() - age
        os.utime(m, (t, t))

    def fake(self, name, args, cwd):
        # A process whose /proc comm is `name`: a symlink to the Python interpreter.
        link = Path(self.tmp.name, name)
        if not link.exists():
            link.symlink_to(os.path.realpath(sys.executable))
        p = subprocess.Popen([str(link), "-c", SLEEP] + args, cwd=cwd,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.procs.append(p)
        # Popen returns after exec, so /proc already shows the new name and cwd.
        self.assertEqual(Path(f"/proc/{p.pid}/comm").read_text().strip(), name)
        return p

    def hook(self, payload, env=None):
        e = dict(os.environ)
        e.pop("SCHOLARCHAIN_STOP_SBT_MAX_AGE", None)
        e.update(env or {})
        data = payload if isinstance(payload, str) else json.dumps(payload)
        return subprocess.run([str(HOOK)], input=data, capture_output=True, text=True,
                              cwd=self.repo, env=e, check=False)

    def assertRc(self, result, rc):
        self.assertEqual(result.returncode, rc, result.stderr)


class RefusalUnchangedTest(StopHookCase):
    def test_no_source_change_passes(self):
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 0)

    def test_stale_marker_refused_without_sbt(self):
        self.change_source()
        self.marker(age=3600)
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 2)
        self.assertRc(self.hook({"hook_event_name": "SubagentStop"}), 2)

    def test_missing_marker_refused_without_sbt(self):
        self.change_source()
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 2)

    def test_fresh_marker_passes(self):
        self.change_source()
        os.utime(self.repo / "A.scala", (time.time() - 60, time.time() - 60))
        self.marker(age=0)
        self.assertRc(self.hook({"hook_event_name": "SubagentStop"}), 0)

    def test_stop_hook_active_passes(self):
        self.change_source()
        self.assertRc(self.hook({"hook_event_name": "Stop", "stop_hook_active": True}), 0)

    def test_non_json_payload_refused(self):
        self.change_source()
        self.assertRc(self.hook("not json"), 2)


class LiveSbtTest(StopHookCase):
    def setUp(self):
        super().setUp()
        self.change_source()
        self.marker(age=3600)

    def test_main_session_passes_while_sbt_runs_here(self):
        self.fake("java", ["-jar", "/x/sbt-launch.jar", "check"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 0)

    def test_process_named_sbt_counts(self):
        self.fake("sbt", ["check"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 0)

    def test_subagent_stop_still_refused(self):
        self.fake("java", ["xsbt.boot.Boot", "check"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "SubagentStop"}), 2)

    def test_sbt_in_another_directory_refused(self):
        other = Path(self.tmp.name, "other")
        other.mkdir()
        self.fake("java", ["xsbt.boot.Boot", "check"], other)
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 2)

    def test_java_without_sbt_launcher_refused(self):
        self.fake("java", ["-jar", "/x/metals.jar"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 2)

    def test_text_match_is_not_a_process(self):
        # pgrep -f sbt would match this; the hook needs the process name, not its text.
        self.fake("python3", ["sbt", "xsbt.boot.Boot", "sbt-launch"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 2)

    def test_too_old_refused(self):
        self.fake("java", ["xsbt.boot.Boot", "check"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "Stop"},
                                env={"SCHOLARCHAIN_STOP_SBT_MAX_AGE": "0"}), 2)

    def test_age_bound_cannot_be_raised(self):
        # The variable only lowers the 45-minute bound; a huge value is clamped to it.
        self.fake("java", ["xsbt.boot.Boot", "check"], self.repo)
        self.assertRc(self.hook({"hook_event_name": "Stop"},
                                env={"SCHOLARCHAIN_STOP_SBT_MAX_AGE": "999999"}), 0)
        self.assertRc(self.hook({"hook_event_name": "Stop"},
                                env={"SCHOLARCHAIN_STOP_SBT_MAX_AGE": "junk"}), 2)

    def test_non_json_payload_refused_while_sbt_runs(self):
        self.fake("java", ["xsbt.boot.Boot", "check"], self.repo)
        self.assertRc(self.hook("not json"), 2)

    def test_no_event_name_refused(self):
        self.fake("java", ["xsbt.boot.Boot", "check"], self.repo)
        self.assertRc(self.hook({}), 2)

    def test_stopped_process_no_longer_counts(self):
        p = self.fake("java", ["xsbt.boot.Boot", "check"], self.repo)
        p.kill()
        p.wait()
        self.assertRc(self.hook({"hook_event_name": "Stop"}), 2)


if __name__ == "__main__":
    unittest.main()
