"""Pass inert file-tool payloads to guard-ownership.sh, as Claude Code sends them.

Run: PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_ownership.py
The hook only reads docs/agents.md and checks paths; no file is created.
"""

import json
import os
from pathlib import Path
import subprocess
import unittest


HOOK = Path(__file__).with_name("guard-ownership.sh")
ROOT = HOOK.resolve().parents[2]


def run_hook(path, agent=None, tool="Write", env=None):
    payload = {"tool_name": tool, "cwd": str(ROOT), "tool_input": {"file_path": path}}
    if agent:
        payload["agent_type"] = agent
    e = dict(os.environ)
    e.pop("SCHOLARCHAIN_ROLE", None)
    if env:
        e.update(env)
    return subprocess.run(
        [HOOK], input=json.dumps(payload), capture_output=True, text=True,
        check=False, env=e,
    )


class OwnershipCase(unittest.TestCase):
    def check(self, cases, **kw):
        for path, agent, expected in cases:
            with self.subTest(path=path, agent=agent, **kw):
                result = run_hook(path, agent, **kw)
                self.assertEqual(result.returncode, expected, result.stderr)


class GoldSetTerritoryTest(OwnershipCase):
    """Defect 14: datasets/gold/** is human-owned; dataset-engineer keeps the rest."""

    def test_no_agent_writes_gold_labels(self):
        gold = "datasets/gold/labels.csv"
        self.check([
            (gold, "dataset-engineer", 2),
            (gold, "entity-resolution-engineer", 2),
            (gold, "experiment-engineer", 2),
            (gold, "factory-engineer", 2),
            (gold, None, 2),                      # orchestrator
            ("datasets/gold/sub/pairs.jsonl", "dataset-engineer", 2),
        ])
        self.check([(gold, "dataset-engineer", 2)], tool="Edit")
        self.check([(gold, None, 2)],
                   env={"SCHOLARCHAIN_ROLE": "entity-resolution-engineer"})

    def test_dataset_engineer_keeps_datasets(self):
        self.check([
            ("datasets/dataset-manifest.yaml", "dataset-engineer", 0),
            ("datasets/samples/dblp-sample.xml", "dataset-engineer", 0),
            ("datasets/golden-notes.md", "dataset-engineer", 0),
            ("datasets/dataset-manifest.yaml", "entity-resolution-engineer", 2),
        ])


class MarkerTerritoryTest(OwnershipCase):
    """Defect 2, file-tool half: no agent writes the test marker."""

    def test_marker_blocked_for_every_caller(self):
        m = ".claude/.last-test-run"
        self.check([
            (m, "factory-engineer", 2),
            (m, "build-engineer", 2),
            (m, None, 2),
        ])


class ExistingTerritoryTest(OwnershipCase):
    """Regression: rows this change did not touch behave as before."""

    def test_unchanged_rows(self):
        self.check([
            ("build.sbt", "build-engineer", 0),
            (".sbtopts", "build-engineer", 0),
            (".jvmopts", "build-engineer", 0),
            ("build.sbt", "service-engineer", 2),
            ("tests/module-unit/hadoop/JobItSuite.scala", "hadoop-engineer", 0),
            ("tests/integration/LoadSuite.scala", "test-engineer", 0),
            ("tests/integration/LoadSuite.scala", "hadoop-engineer", 2),
            ("docs/agent-runs/phase-01/run-log.md", None, 0),
            ("docs/agent-runs/phase-01/human-gate.md", None, 2),
            ("docs/agent-runs/README.md", None, 2),
            ("README.md", "code-reviewer", 2),
            (".claude/agents/x.md", "factory-engineer", 2),
            ("AGENTS.md", "factory-engineer", 0),
        ])


if __name__ == "__main__":
    unittest.main()
