"""Pass inert command strings to guard-dangerous.sh through its normal JSON input.

Run: PYTHONDONTWRITEBYTECODE=1 python3 -I .claude/hooks/test_guard_dangerous.py
Every command here is data for the hook; none is executed.
"""

import json
import os
from pathlib import Path
import subprocess
import unittest


HOOK = Path(__file__).with_name("guard-dangerous.sh")


ROOT = HOOK.resolve().parents[2]


def run_hook(command, agent=None, env=None, key="command", cwd=None):
    payload = {"tool_input": {key: command}}
    if agent:
        payload["agent_type"] = agent
    if cwd:
        payload["cwd"] = str(cwd)
    e = dict(os.environ)
    e.pop("SCHOLARCHAIN_ROLE", None)
    if env:
        e.update(env)
    return subprocess.run(
        [HOOK], input=json.dumps(payload), capture_output=True, text=True,
        check=False, env=e,
    )


class GuardCase(unittest.TestCase):
    def check(self, cases, **kw):
        for command, expected in cases:
            with self.subTest(command=command, **kw):
                result = run_hook(command, **kw)
                self.assertEqual(result.returncode, expected, result.stderr)


class GuardPunctuationTest(GuardCase):
    def test_operator_positions(self):
        self.check([
            ("printf %s ';' git push --forc", 0),
            ('printf %s "||" git push --forc', 0),
            (r"printf %s \; git push --forc", 0),
            # Each -o consumes one argument, including punctuation or flag-like text.
            ('git push -o ">" origin -o --forc', 0),
            (r"git push -o \< origin -o --forc", 0),
            ("printf %s sample; git push --forc", 2),
            ("git push > sample --forc", 2),
        ])


class TestMarkerTest(GuardCase):
    """Defect 2: only sbt check (markTestRun inside the sbt JVM) writes the marker."""

    M = ".claude/.last-test-run"

    def test_shell_writes_blocked(self):
        m = self.M
        self.check([
            (f"touch {m}", 2),
            (f"echo x > {m}", 2),
            (f"echo x >> {m}", 2),
            (f"date >| {m}", 2),
            (f"cp /dev/null {m}", 2),
            (f"mv /tmp/x {m}", 2),
            (f"echo x | tee {m}", 2),
            (f"echo x | tee -a {m}", 2),
            (f"ln -sf /tmp/x {m}", 2),
            (f"install -m 644 /dev/null {m}", 2),
            (f"dd if=/dev/null of={m}", 2),
            (f"truncate --size=0 {m}", 2),
            (f"rm -f {m}", 2),
            (f"python3 -c \"open('{m}','w')\"", 2),
            (f"bash -c 'touch {m}'", 2),
            ("cd .claude && touch .last-test-run", 2),
            ("touch .claude/.l*", 2),
            (f"echo {m} | xargs touch", 2),
            (f"find .claude -name .last-test-run -exec touch {{}} +", 2),
            (f"python3 - <<'EOF'\nopen('{m}', 'w').close()\nEOF", 2),
        ])

    def test_marker_task_by_name_blocked(self):
        self.check([
            ("sbt markTestRun", 2),
            ('sbt "compile; markTestRun"', 2),
            ("sbt check", 0),
        ])

    def test_reads_allowed(self):
        m = self.M
        self.check([
            (f"cat {m}", 0),
            (f"stat -c %Y {m} 2>/dev/null", 0),
            (f"test -f {m} && echo fresh", 0),
            (f"[ -f {m} ] || echo missing", 0),
            (f"ls -la {m}", 0),
            (f"find . -newer {m} -name '*.scala'", 0),
            (f"git status --short {m}", 0),
            ("ls .claude/hooks/*.sh", 0),
        ])


class ScaleCapTest(GuardCase):
    """Defect 4: general cap 4 for every caller; incident-responder bound 1 to 2."""

    ECS = "aws ecs update-service --cluster c --service s --desired-count {}"

    def test_general_cap(self):
        self.check([
            (self.ECS.format(3), 0),
            (self.ECS.format(4), 0),
            (self.ECS.format(5), 2),
            (self.ECS.format("99999999999999999999"), 2),
            ("aws emr modify-instance-groups --instance-groups"
             " InstanceGroupId=ig-1,InstanceCount=9", 2),
        ])

    def test_incident_responder_bound(self):
        self.check([
            (self.ECS.format(1), 0),
            (self.ECS.format(2), 0),
            (self.ECS.format(0), 2),
            (self.ECS.format(3), 2),
            (self.ECS.format(4), 2),
            ("aws emr modify-instance-groups --instance-groups"
             " InstanceGroupId=ig-1,InstanceCount=3", 2),
        ], agent="incident-responder")

    def test_incident_responder_by_role_env(self):
        self.check([(self.ECS.format(3), 2), (self.ECS.format(2), 0)],
                   env={"SCHOLARCHAIN_ROLE": "incident-responder"})

    def test_incident_responder_through_call_aws(self):
        self.check([(self.ECS.format(3), 2), (self.ECS.format(2), 0)],
                   agent="incident-responder", key="cli_command")

    def test_other_roles_keep_general_cap(self):
        self.check([(self.ECS.format(3), 0), (self.ECS.format(5), 2)],
                   agent="deploy-engineer")


class SecretReadTest(GuardCase):
    """Defects 5 and 8: shell reads of secret files are blocked."""

    def test_new_secret_paths_blocked(self):
        self.check([
            ("cat config/prod.env", 2),
            ("cat .envrc", 2),
            ("cat services/.envrc", 2),
            ("cat infra/stacks/app/prod.tfvars", 2),
            ("cat infra/stacks/app/prod.tfvars.json", 2),
            ("cat ~/.ssh/config", 2),
            ("ls ~/.ssh", 2),
            ("cat ~/.aws/sso/cache/token.json", 2),
            ("cat ~/.aws/cli/cache/abc.json", 2),
            ("cat ~/.claude/.credentials.json", 2),
            ("cat ~/.config/gh/hosts.yml", 2),
            ("cat ~/.codex/auth.json", 2),
        ])

    def test_existing_secret_paths_still_blocked(self):
        self.check([
            ("cat .env", 2),
            ("cat .env.local", 2),
            ("cat < .env", 2),
            ("source .env", 2),
            ("cat ~/.aws/credentials", 2),
            ("cat secrets/db.txt", 2),
            ("cat deploy/id_rsa", 2),
            ("cat state/terraform.tfstate", 2),
            ("cat ~/.netrc", 2),
        ])

    def test_grep_reading_a_secret_file_blocked(self):
        self.check([
            ("grep TOKEN .env", 2),
            ("grep -n PASSWORD config/prod.env", 2),
            ("grep -f .env notes.txt", 2),
            ("grep -e x .envrc", 2),
            ("grep -rn x --include=*.env .", 2),
            ("rg -n TOKEN infra/prod.tfvars", 2),
        ])

    def test_grep_pattern_is_not_a_read(self):
        self.check([
            ('grep -n ".env" docs/architecture.md', 0),
            (r"grep -rn '\.env' .claude/hooks", 0),
            ("grep -rn -e .envrc README.md", 0),
            ('rg -n "prod.tfvars" docs/', 0),
            ("grep -n 'id_rsa' docs/architecture.md", 0),
            ("grep -c .ssh/ docs/architecture.md", 0),
        ])


class GoldSetTest(GuardCase):
    """Defect 14, narrowed in repair round 2: only writes that resolve under
    datasets/gold/ are blocked; a bare datasets token or a glob directly under
    datasets/ is not matched."""

    def test_work_order_cases(self):
        self.check([
            ("python3 datasets/scripts/fetch_samples.py > /tmp/log", 0),
            ("cd datasets && ./scripts/x.sh > log", 0),
            ("cp x datasets/gold/labels.csv", 2),
            ("echo x > datasets/gold/a.csv", 2),
        ])

    def test_shell_writes_blocked(self):
        self.check([
            ("rm -rf datasets/gold", 2),
            ("rm -rf datasets/gold/", 2),
            ("echo x > datasets/gold/labels.csv", 2),
            ("cp /tmp/l.csv datasets/gold/labels.csv", 2),
            ("sed -i s/a/b/ datasets/gold/labels.csv", 2),
            ("touch datasets/gold/labels.csv", 2),
            ("mv datasets/gold/a.csv /tmp/", 2),
            ("echo x | tee -a datasets/gold/a.csv", 2),
            ("dd if=/dev/zero of=datasets/gold/x", 2),
            ("cp -t datasets/gold x", 2),
            ("install -Dm644 x --target-directory=datasets/gold", 2),
            ("find datasets/gold -name '*.csv' -delete", 2),
            ("git -C datasets checkout gold/labels.csv", 2),
        ])

    def test_globs_and_braces_blocked(self):
        self.check([
            ("rm datasets/gold/*.csv", 2),
            ("rm datasets/*/labels.csv", 2),
            ("rm */gold/labels.csv", 2),
            ("rm -rf datasets/g*", 2),
            ("rm datasets/{gold,x}/a.csv", 2),
        ])

    def test_relative_paths_blocked(self):
        self.check([
            ("echo x > ./datasets/gold/x", 2),
            ("touch datasets/./gold/x", 2),
            ("echo x > datasets/x/../gold/y", 2),
            ("cd datasets && rm -rf gold", 2),
            ("cd datasets && cp /tmp/l.csv gold/labels.csv", 2),
            ("cd datasets && echo x >gold/a", 2),
            ("cd datasets/gold && echo x > a.csv", 2),
            ("cd datasets && cd gold && rm a.csv", 2),
            ("cd datasets/gold; rm *", 2),
            ("pushd datasets/gold && touch x", 2),
            ('rm "$PWD/datasets/gold/a.csv"', 2),
            (f"rm {ROOT}/datasets/gold/a.csv", 2),
        ])

    def test_payload_cwd(self):
        self.check([("touch x", 2), ("echo x > a.csv", 2), ("wc -l a.csv", 0)],
                   cwd=ROOT / "datasets" / "gold")
        self.check([("echo x > log", 0), ("rm -rf gold", 2)], cwd=ROOT / "datasets")

    def test_indirect_writes_blocked(self):
        self.check([
            ("echo datasets/gold/a.csv | xargs rm", 2),
            ("bash -c 'rm datasets/gold/a.csv'", 2),
            ("echo $(rm datasets/gold/a.csv)", 2),
            ("""python3 -c "open('datasets/gold/a.csv', 'w')" """, 2),
            ("""awk '{print > "datasets/gold/x"}' f""", 2),
            ("python3 - <<'EOF'\nopen('datasets/gold/x', 'w')\nEOF", 2),
            ("bash <<EOF\nrm datasets/gold/x\nEOF", 2),
            # unparsable quoting falls back to the literal check (fails closed)
            ('echo "x > datasets/gold/a', 2),
        ])

    def test_reads_allowed(self):
        self.check([
            ("cat datasets/gold/labels.csv", 0),
            ("head datasets/gold/labels.csv", 0),
            ("head -n 5 datasets/gold/labels.csv | wc -l", 0),
            ("wc -l datasets/gold/labels.csv", 0),
            ("wc -l datasets/gold/labels.csv 2>&1", 0),
            ("grep -c x datasets/gold/labels.csv 2>/dev/null", 0),
            ("cat datasets/gold/labels.csv > /tmp/copy.csv", 0),
            ("cd datasets/gold && wc -l *.csv > /tmp/n", 0),
            ("awk -F, '{print $1}' datasets/gold/labels.csv", 0),
            ("git diff datasets/gold", 0),
            ("ls datasets/", 0),
            ("bash -n datasets/scripts/fetch-sample.sh", 0),
        ])

    def test_other_datasets_paths_allowed(self):
        self.check([
            ("rm datasets/*.tmp", 0),
            ("touch datasets/manifest.json", 0),
            ("cd datasets && rm -rf tmp", 0),
            ("echo x > datasets/golden.csv", 0),
            ("rm -rf datasets/gold.bak", 0),
        ])


class TfvarsVarFileTest(GuardCase):
    """Repair round 2: -var-file=<name>.tfvars is allowed on terraform plan, validate
    and console and on scripts/plan.sh; every other tfvars read stays blocked."""

    def test_var_file_allowed(self):
        self.check([
            ("terraform plan -var-file=prod.tfvars", 0),
            ("terraform -chdir=infra/stacks/app plan -var-file=dev.tfvars", 0),
            ("terraform validate -var-file=dev.tfvars", 0),
            ("terraform console -var-file=dev.tfvars", 0),
            ("scripts/plan.sh app -var-file=prod.tfvars", 0),
            ("terraform plan -var-file prod.tfvars", 0),
            ("./scripts/plan.sh app -var-file infra/env/dev.tfvars", 0),
            ("bash scripts/plan.sh app -var-file=dev.tfvars", 0),
            ('terraform plan -var-file="prod.tfvars"', 0),
            ("terraform plan -var-file=a.tfvars -var-file=b.tfvars -out=plan.bin", 0),
        ])

    def test_reads_still_blocked(self):
        self.check([
            ("cat prod.tfvars", 2),
            ("head infra/stacks/app/dev.tfvars", 2),
            ("less dev.tfvars", 2),
            ("more dev.tfvars", 2),
            ("tail dev.tfvars", 2),
            ("bat dev.tfvars", 2),
            ("strings dev.tfvars", 2),
            ("xxd dev.tfvars", 2),
            ("od -c dev.tfvars", 2),
            ("sed -n 1p dev.tfvars", 2),
            ("awk 1 dev.tfvars", 2),
            ("grep -n x dev.tfvars", 2),
            ("cp prod.tfvars /tmp/x", 2),
            ("base64 prod.tfvars", 2),
        ])

    def test_chained_and_substituted_reads_blocked(self):
        self.check([
            ("terraform plan -var-file=prod.tfvars && cat prod.tfvars", 2),
            ("terraform plan -var-file=prod.tfvars; less prod.tfvars", 2),
            ("terraform plan -var-file=prod.tfvars | tee dev.tfvars", 2),
            ("terraform plan -var-file=$(cat x.tfvars)", 2),
            ("terraform plan -var-file=`cat x.tfvars`", 2),
            ("terraform plan -var-file=prod.tfvars < prod.tfvars", 2),
            ("echo 'file(\"prod.tfvars\")' | terraform console -var-file=prod.tfvars", 2),
            ("bash -c 'terraform plan -var-file=x.tfvars'", 2),
        ])

    def test_other_mentions_and_subcommands_blocked(self):
        self.check([
            ("terraform plan -var-file=prod.tfvars -out=prod.tfvars", 2),
            ("terraform plan -var-file=prod.tfvars dev.tfvars", 2),
            ("terraform plan -var-file=../secrets/prod.tfvars", 2),
            ("terraform plan -var-file=.env.tfvars", 2),
            ("terraform plan -var-file=prod.tfvars.json", 2),
            ("terraform fmt -var-file=prod.tfvars", 2),
            ("tofu plan -var-file=prod.tfvars", 2),
            ("terraform apply -var-file=prod.tfvars", 2),
            ("terraform -chdir=infra apply -var-file=prod.tfvars", 2),
            ("terraform destroy -var-file=prod.tfvars", 2),
            ("terraform import -var-file=prod.tfvars aws_s3_bucket.b b", 2),
        ])


class GrepPatternFileTest(GuardCase):
    """Repair round 3, CRITICAL-1: a file operand that is a secret is a read, whatever
    the flags. -f and --file supply the patterns, so no file operand is a pattern."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("grep -vf /dev/null .env", 2),
            ("grep --file=/dev/null -v .env", 2),
            ("rg -f /dev/null -v .env", 2),
            ("grep -v -f /dev/null ~/.ssh/id_rsa", 2),
            ("grep -vf /dev/null ~/.aws/credentials", 2),
            ("grep -vf /dev/null config/prod.env", 2),
            ("grep -vf /dev/null infra/x.tfvars", 2),
        ])

    def test_other_pattern_option_spellings_blocked(self):
        self.check([
            ("grep --file /dev/null -v .env", 2),
            ("rg --file /dev/null -v .env", 2),
            ("grep -rf /dev/null .env", 2),
            ("grep -f/dev/null -v .env", 2),
            # GNU grep accepts unambiguous abbreviations of long options.
            ("grep --fil=/dev/null -v .env", 2),
            ("grep --reg=x .env", 2),
            ("grep --regex x .env", 2),
        ])

    def test_ordinary_searches_allowed(self):
        self.check([
            ("grep -rn foo src", 0),
            ("grep -f patterns.txt src/a.scala", 0),
            ("rg -n -f patterns.txt modules", 0),
            ("grep --regex=.env docs/x.md", 0),
            ("grep -vf /dev/null README.md", 0),
        ])


class GitOutputTest(GuardCase):
    """Repair round 3, MAJOR-1: git diff, log and show --output write a file, so the
    test marker, human-gate.md, the gold set and secret paths are blocked targets."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("git diff --output=.claude/.last-test-run", 2),
            ("git diff --output=docs/agent-runs/phase-01/human-gate.md", 2),
            ("git diff --output=datasets/gold/labels.csv", 2),
            ("git log -1 --output=datasets/gold/labels.csv", 2),
            ("git show --output=docs/agent-runs/phase-01/human-gate.md HEAD", 2),
        ])

    def test_other_output_spellings_blocked(self):
        self.check([
            ("git diff --output .claude/.last-test-run", 2),
            ("cd .claude && git diff --output=.last-test-run", 2),
            ("git --no-pager log --output=.claude/.last-test-run", 2),
            ("git diff --output datasets/gold/labels.csv", 2),
            ("git -C datasets diff --output=gold/labels.csv", 2),
            ("cd datasets && git log --output=gold/x.csv", 2),
            ("git format-patch -o datasets/gold HEAD~1", 2),
            ("git diff --output=.env", 2),
            ("git diff --output infra/x.tfvars", 2),
            ("git show --output=~/.ssh/config HEAD", 2),
        ])

    def test_ordinary_git_reads_allowed(self):
        # --output to a scratch file stays allowed by the hook; the settings.json deny
        # rule Bash(git * --output*) stops it at the permission layer after ratification.
        self.check([
            ("git diff --stat", 0),
            ("git diff --cached --name-only", 0),
            ("git log --oneline -5", 0),
            ("git diff --output=/tmp/x.patch", 0),
            ("git diff datasets/gold", 0),
            ("git show HEAD:docs/agent-runs/phase-01/human-gate.md", 0),
            ("git status --short .claude/.last-test-run", 0),
            ("git diff > /tmp/x.patch", 0),
        ])


class SecretGlobTest(GuardCase):
    """Repair round 3, MAJOR-6: an unquoted glob or brace word that can match a secret
    name is a secret read."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("cat .en?", 2),
            ("cat .e*", 2),
            ("cat ~/.aws/cred*", 2),
            ("cat ~/.a*/credentials", 2),
        ])

    def test_other_aimed_globs_blocked(self):
        self.check([
            ("cat .[e]nv", 2),
            ("cat config/*.en?", 2),
            ("cat infra/prod.tfv*", 2),
            ("cat ~/.s*/config", 2),
            ("cat secret?/db.txt", 2),
            ("cat ~/.c*/auth.json", 2),
            ("cat deploy/id_rs?", 2),
            ("head $HOME/.aws/cred*", 2),
            ("cat .{env,x}", 2),
            ("cat ~/.aws/{credentials,config}", 2),
        ])

    def test_ordinary_globs_allowed(self):
        self.check([
            ("cat '.e*'", 0),               # quoted: the shell does not expand it
            ("ls *.scala", 0),
            ("cat docs/*.md", 0),
            ("cat modules/*/MODULE.md", 0),
            ("ls modules/s*/src", 0),
            ("ls .claude/hooks/*.sh", 0),
            ("ls target/scala-3.*", 0),
            ("jq . experiments/*/raw/*.json", 0),
            ('sbt "testOnly *Spec"', 0),
            ("sbt testOnly scholarchain.core.*", 0),
            ("find . -name '*.scala'", 0),
            ("mkdir -p src/{main,test}/scala", 0),
            ("echo $((5*2))", 0),
            ("[ -f build.sbt ] && echo yes", 0),
            ("cat .envexample", 0),         # not a secret name in the deny list
        ])

    def test_heredoc_bodies(self):
        # The shell expands globs in a heredoc body only when the body feeds a shell.
        self.check([
            ("bash <<EOF\ncat .e*\nEOF", 2),
            ("cat > /tmp/notes.md <<'EOF'\nsee cred* and .e* in prose\nEOF", 0),
        ])

    def test_broad_glob_blocked_only_when_a_secret_exists(self):
        import tempfile
        with tempfile.TemporaryDirectory() as clean, tempfile.TemporaryDirectory() as dirty:
            for name in ("id_rsa", "prod.env", "credentials.json", "notes.txt"):
                Path(dirty, name).write_text("x\n")
            Path(clean, "notes.txt").write_text("x\n")
            self.check([("cat *", 2), ("jq . *.json", 2), ("grep -n x *", 2)], cwd=dirty)
            self.check([("cat *", 0), ("jq . *.json", 0), ("grep -n x *", 0)], cwd=clean)
            Path(dirty, ".aws").mkdir()
            Path(dirty, ".aws", "credentials").write_text("x\n")
            self.check([("cat ~/.aws/*", 2)], env={"HOME": dirty})
            self.check([("cat ~/.aws/*", 0)], env={"HOME": clean})


class HumanGateShellWriteTest(GuardCase):
    """Repair round 3, MINOR-2: the shell write shapes docs/agents.md list E names."""

    G = "docs/agent-runs/phase-01/human-gate.md"

    def test_reviewer_probes_blocked(self):
        self.check([
            ("find docs/agent-runs -name human-gate.md -delete", 2),
            (f"python3 -c \"open('{self.G}','a').write('ratified')\"", 2),
            (f"shred -u {self.G}", 2),
        ])

    def test_listed_shapes_blocked(self):
        g = self.G
        self.check([
            (f"echo x > {g}", 2),
            (f"touch {g}", 2),
            (f"unlink {g}", 2),
            (f"git checkout -- {g}", 2),
            (f"git restore {g}", 2),
            (f"find docs -name human-gate.md -exec rm {{}} +", 2),
            (f"awk '{{print > \"x\"}}' {g}", 2),
        ])

    def test_reads_allowed(self):
        g = self.G
        self.check([
            (f"cat {g}", 0),
            (f"grep -n SHA256 {g}", 0),
            (f"head -n 20 {g}", 0),
            (f"git log --oneline -- {g}", 0),
        ])


class SecretEnvironmentTest(GuardCase):
    """Repair round 3, MINOR-3: printing credential variables and minting AWS
    credentials are blocked, in the shell and through call_aws."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("printenv GITHUB_PAT", 2),
            ("aws sts get-session-token", 2),
            ("aws ecr get-authorization-token", 2),
        ])

    def test_other_forms_blocked(self):
        self.check([
            ("printenv AWS_SECRET_ACCESS_KEY", 2),
            ("printenv AWS_SESSION_TOKEN", 2),
            ("printenv HOME GITHUB_PAT", 2),
            ("printenv", 2),
            ("env", 2),
            ("env | grep -i pat", 2),
            ("bash -c 'printenv GITHUB_PAT'", 2),
            ("aws --region us-east-2 sts get-session-token --duration-seconds 900", 2),
            ("aws ecr-public get-authorization-token", 2),
        ])

    def test_call_aws_blocked(self):
        self.check([
            ("aws sts get-session-token", 2),
            ("aws ecr get-authorization-token", 2),
            ("aws sts get-caller-identity", 0),
        ], key="cli_command")

    def test_ordinary_environment_use_allowed(self):
        self.check([
            ("printenv PATH", 0),
            ("printenv HOME JAVA_HOME", 0),
            ("echo $PATH", 0),
            ("env FOO=1 sbt check", 0),
            ("env -u FOO sbt compile", 0),
            ("aws sts get-caller-identity", 0),
        ])


class PreCommitSecretFilterTest(unittest.TestCase):
    """Repair round 3, MINOR-5: the pre-commit file-name filter matches the Read deny
    list. The pattern is read from .agents/githooks/pre-commit and run with grep -E."""

    @staticmethod
    def staged(path):
        import re
        text = (ROOT / ".agents" / "githooks" / "pre-commit").read_text()
        pattern = re.search(r"^secret_files='(.*)'$", text, re.M).group(1)
        return subprocess.run(["grep", "-Eq", pattern], input=path + "\n", text=True,
                              check=False).returncode == 0

    def test_secret_names_blocked(self):
        for path in (".env", ".env.local", "config/prod.env", ".envrc", "services/.envrc",
                     "infra/stacks/app/prod.tfvars", "infra/x.tfvars.json", "secrets/db.txt",
                     "deploy/id_rsa", "certs/a.pem", "a.key", "state/terraform.tfstate",
                     "terraform.tfstate.backup", ".netrc", "home/.netrc",
                     "x/credentials.json", ".claude/.last-test-run"):
            with self.subTest(path=path):
                self.assertTrue(self.staged(path))

    def test_ordinary_names_allowed(self):
        for path in ("README.md", "infra/stacks/app/main.tf", "infra/env/dev.tfvars.example",
                     "docs/environment.md", ".envexample", "build.sbt", ".mcp.json",
                     "modules/core/src/main/scala/Env.scala", "docs/keys.md",
                     ".agents/githooks/pre-commit", "docs/tfvars.md"):
            with self.subTest(path=path):
                self.assertFalse(self.staged(path))


if __name__ == "__main__":
    unittest.main()
