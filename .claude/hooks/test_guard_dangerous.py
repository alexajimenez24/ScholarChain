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
        # Round 4 (fail closed): every heredoc body is scanned as commands, globs too.
        self.check([
            ("bash <<EOF\ncat .e*\nEOF", 2),
            ("cat > /tmp/notes.md <<'EOF'\nsee cred* and .e* in prose\nEOF", 2),
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


class SbtArbitraryCodeTest(GuardCase):
    """Phase 11 B-1: sbt forms that run arbitrary code are blocked; the forms the roles,
    aliases and module notes use stay allowed."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("sbt 'eval scala.sys.process.Process(Seq(\"terraform\",\"apply\")).!'", 2),
            ("sbt 'eval scala.sys.process.Process(Seq(\"rm\",\"-rf\",\"datasets/gold\")).!'", 2),
            ("sbt 'eval new java.io.File(\".claude/.last-\" + \"test-run\").setLastModified(0L)'", 2),
            ("sbt 'eval scala.io.Source.fromFile(\".e\" + \"nv\").mkString'", 2),
        ])

    def test_code_running_commands_blocked(self):
        self.check([
            ("sbt console", 2),
            ('sbt "core/consoleQuick"', 2),
            ("sbt consoleProject", 2),
            ("sbt 'core / Test / console'", 2),
            ("sbt 'set ThisBuild/version := \"x\"'", 2),
            ("sbt 'set every fork := false' test", 2),
            ('sbt "reload plugins" compile', 2),
            ("sbt 'alias x=eval 1' x", 2),
            ("sbt 'addCommandAlias(\"x\", \"eval 1\")'", 2),
            ('sbt "apply -cp /tmp/x Evil"', 2),
            ("sbt '< /tmp/cmds.txt'", 2),
            ('sbt "projects add file:/tmp/x"', 2),
            ('sbt "session save"', 2),
            ('sbt "onFailure eval 1" check', 2),
            ('sbt "bgRun"', 2),
            ('sbt "~test"', 2),
            ("sbt '!!'", 2),
            ('sbt "hadoop/Test/runMain org.apache.hadoop.fs.FsShell -rm -r datasets"', 2),
            ('sbt "runMain"', 2),
            ("sbtn eval 1", 2),
            ("sbt --client 'eval 1'", 2),
            ("java -jar ~/.sbt/launchers/1.10/sbt-launch.jar 'eval 1'", 2),
            ("timeout -s KILL 45m sbt 'eval 1'", 2),
            ("env -S 'sbt eval 1'", 2),
            ("bash -c \"sbt 'eval 1'\"", 2),
        ])

    def test_stdin_shell_and_launcher_options_blocked(self):
        self.check([
            ("sbt", 2),
            ("echo eval 1 | sbt", 2),
            ("sbt -v", 2),
            ("sbt <<EOF\neval 1\nEOF", 2),
            ("sbt -sbt-dir /tmp/evil check", 2),
            ("sbt -sbt-jar /tmp/x.jar check", 2),
            ("sbt -java-home /tmp/jdk check", 2),
            ("sbt -Dsbt.global.base=/tmp/evil check", 2),
            ("sbt -J-javaagent:/tmp/a.jar check", 2),
            ("sbt -J-XX:OnOutOfMemoryError=x check", 2),
            ("sbt @/tmp/args check", 2),
            ("SBT_OPTS=-J-javaagent:/tmp/a.jar sbt check", 2),
            ("export JAVA_TOOL_OPTIONS=-javaagent:/tmp/a.jar; sbt check", 2),
        ])

    def test_legitimate_forms_allowed(self):
        self.check([
            ("sbt check", 0),
            ("timeout 45m sbt check", 0),
            ("timeout --foreground 45m sbt check", 0),
            ("timeout 30m sbt storage/test", 0),
            ('timeout 30m sbt "adversarialTests/testOnly *FilteredRankGateSuite *Workflow*Suite"', 0),
            ('sbt "core/testOnly scholarchain.core.ConfigSuite -- -z set"', 0),
            ("sbt fmt", 0),
            ("sbt packageJobs", 0),
            ("sbt dockerLocal", 0),
            ("sbt compile Test/compile", 0),
            ("sbt scalafmtCheck", 0),
            ("sbt hadoop/scalafmtCheck hadoop/Test/scalafmtCheck", 0),
            ("sbt rag/scalafmt rag/Test/scalafmt rag/test", 0),
            ('sbt "compile; test"', 0),
            ('sbt -no-colors "sparkAnalytics/Test/runMain scholarchain.spark.LocalPipelineRun --capture /tmp/c"', 0),
            ('timeout 30m sbt "entityResolution/runMain scholarchain.er.eval.EvaluateMain'
             ' --gold datasets/gold/er-gold-v1.csv --decisions /tmp/d --out /tmp/o"', 0),
            ('sbt "hadoop/Test/run"', 0),
            ("sbt --version", 0),
            ("env -u FOO sbt compile", 0),
            ("sbt reload", 0),
            ("sbt projects", 0),
        ])


class ParseFailureTest(GuardCase):
    """Phase 11 C01 (Phase 01 R3-CRITICAL-1): a command that cannot be parsed fails
    closed; a comment with an apostrophe no longer hides a secret read."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("cat .env #'", 2),
            ("cat .env # don't print this", 2),
            ("cat ~/.aws/credentials #'", 2),
            ("cat ~/.ssh/id_rsa #'", 2),
            ("cat .env\ncat <<EOF\nit's a note\nEOF", 2),
            ("grep -vf /dev/null .env #'", 2),
            ("cat infra/x.tfvars #'", 2),
            ("head -100 config/prod.env # it's fine", 2),
            ("cat .e* #'", 2),
            ("cat ~/.aws/cred* #'", 2),
            ('cat .en? "', 2),
        ])

    def test_harmless_comments_and_prose_allowed(self):
        self.check([
            ("git log --oneline -3 # it's fine", 0),
            ("git status # don't worry", 0),
            ("cat > /tmp/notes.md <<'EOF'\nsee cred* and .e* in prose, it's fine\nEOF", 2),  # round 4: every heredoc body is commands
            ("ls *.md # it's a listing", 0),
        ])


class NestedGlobTest(GuardCase):
    """Phase 11 C02 (Phase 01 R3-MAJOR-1): glob words inside sh -c, bash -c, eval and
    find patterns are analysed like top-level words."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("bash -c 'cat .e*'", 2),
            ('sh -c "cat ~/.aws/cred*"', 2),
            ("eval 'cat .e*'", 2),
            ("find . -maxdepth 1 -name '.e*' -exec cat {} +", 2),
            ("find ~/.aws -name 'cred*' -exec cat {} +", 2),
        ])

    def test_other_nested_forms_blocked(self):
        self.check([
            ("find . -name '.e*' | xargs cat", 2),
            ("bash -c \"eval 'cat .e*'\"", 2),
            ("find . -iname '.ENV*' -exec cat {} +", 2),
        ])

    def test_ordinary_nested_globs_allowed(self):
        self.check([
            ("find . -name '*.scala'", 0),
            ("find . -name '.e*'", 0),
            ("find modules -name '*.scala' -exec grep -l Config {} +", 0),
            ("bash -c 'ls *.md'", 0),
            ("bash -c 'cat .e\\*'", 0),
        ])


class DisguisedTerraformAndPushTest(GuardCase):
    """Phase 11 C03 (Phase 01 MAJOR-4): disguised terraform apply and force-push forms."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("ter\\raform apply #'", 2),
            ("git push --mirr origin #'", 2),
            ("git -c remote.origin.push=+refs/heads/main:refs/heads/main push origin", 2),
            ("t=terraform; $t apply", 2),
            ("$(echo terraform) apply", 2),
        ])

    def test_other_disguises_blocked(self):
        self.check([
            ("echo #'\nter\\raform apply\n'", 2),
            ("`echo terraform` destroy", 2),
            ('"${TF}" -chdir=infra apply', 2),
            ("echo apply | xargs terraform", 2),
            ("terraform $(echo apply)", 2),
            ("g=git; $g push --force", 2),
            ("git -c remote.origin.mirror=true push origin", 2),
            ("git --config-env=remote.origin.push=X push origin", 2),
            ("git config remote.origin.mirror true && git push", 2),
            ("GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=remote.origin.push"
             " GIT_CONFIG_VALUE_0=+main git push", 2),
            ('git push origin "$BRANCH"', 2),
        ])

    def test_ordinary_forms_allowed(self):
        self.check([
            ("terraform plan -out=p", 0),
            ("terraform -chdir=$D validate", 0),
            ("git config --get remote.origin.push", 0),
            ("git log --oneline -3", 0),
        ])


class GoldAncestorTest(GuardCase):
    """Phase 11 C04: writes to and deletions of datasets/gold through its ancestors, find
    -delete and interpreter code."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("rm -rf datasets", 2),
            ("mv datasets /tmp/ds", 2),
            ("find datasets -name '*.csv' -delete", 2),
            ("python3 -c \"import shutil; shutil.rmtree('datasets/'+'gold')\"", 2),
        ])

    def test_other_ancestor_forms_blocked(self):
        self.check([
            ("python3 -c \"import shutil; shutil.rmtree('datasets')\"", 2),
            ("rm -rf .", 2),
            ("rm -rf ..", 2),
            ("rm -rf *", 2),
            ('rm -rf "$PWD/datasets"', 2),
            ("chmod -R 000 datasets", 2),
            ("cp -r /tmp/gold datasets/", 2),
            ("rsync -a /tmp/x/ datasets/", 2),
            ("git checkout -- datasets", 2),
            ("git restore .", 2),
            ("timeout -s KILL 10m rm -rf datasets/gold", 2),
            ("find . -name '*.csv' -exec rm {} +", 2),
            ("find datasets -path '*gold*' -delete", 2),
            ("python3 - <<'EOF'\nimport shutil\nshutil.rmtree('datasets/' + 'gold')\nEOF", 2),
            ("scala -e 'os.remove(\"datasets/gold/x\")'", 2),
            ('sbt "entityResolution/runMain scholarchain.er.review.ReviewQueueMain'
             ' --run-root /tmp/r --out datasets/gold/x.csv"', 2),
        ])

    def test_other_paths_allowed(self):
        self.check([
            ("rm -rf datasets/*.tmp", 0),
            ("find datasets -name '*.tmp' -delete", 0),
            ("find . -name '*.pyc' -delete", 0),
            # Repair round 3 (human, build inputs): this also deletes project/target and
            # project/project/target, a shell write under project/, so it is blocked now.
            ("find . -type d -name target -exec rm -rf {} +", 2),
            ("find modules -type d -name target -exec rm -rf {} +", 0),
            ("find datasets -name '*.json' -exec cat {} +", 0),
            ("rm -rf target", 0),
            ('rm -rf "$tmp"', 0),
            ("mv a.txt b.txt", 0),
            ("cp x.json datasets/", 0),
            ("git checkout main", 0),
            ("python3 datasets/scripts/fetch_samples.py > /tmp/log", 0),
        ])


class MarkerFalsePositiveTest(GuardCase):
    """Phase 11 C05: harmless text naming the test marker or its sbt task is allowed;
    every write onto the marker and every run of the task stays blocked."""

    M = ".claude/.last-test-run"

    def test_reviewer_false_positives_allowed(self):
        m = self.M
        self.check([
            (f"cat > /tmp/r.md <<'EOF'\nmarker {m} is not refreshed\nEOF", 2),  # round 4: every heredoc body is commands
            ("cat > /tmp/r.md <<'EOF'\nmarkTestRun did not run; it's stale\nEOF", 2),  # round 4: every heredoc body is commands
            ("timeout 45m sbt check; grep -c markTestRun build.sbt", 0),
            (f"bash x.sh; ls {m}", 0),
            (f"sed -n 1,5p {m}", 0),
            (f"cp {m} /tmp/m", 0),
        ])

    def test_other_harmless_text_allowed(self):
        m = self.M
        self.check([
            ("grep -rn markTestRun build.sbt", 0),
            ("git log --oneline --grep markTestRun", 0),
            (f"echo 'the marker {m} is stale' > /tmp/r.md && bash -n x.sh", 0),
            (f"tee /tmp/r.md <<'EOF'\nsee {m}\nEOF", 2),  # round 4: every heredoc body is commands
            (f"cat {m} # it's there", 0),
        ])

    def test_writes_and_task_runs_still_blocked(self):
        m = self.M
        self.check([
            ("sbt markTestRun", 2),
            ('sbt "check; markTestRun"', 2),
            ('sbt "mark""TestRun"', 2),
            ("sbtn markTestRun", 2),
            ("x=markTestRun; sbt $x", 2),
            (f"bash <<EOF\ntouch {m}\nEOF", 2),
            ("bash <<EOF\nsbt markTestRun\nEOF", 2),
            ("python3 - <<'EOF'\nimport os; os.system('sbt markTestRun')\nEOF", 2),
            (f"cp /tmp/x {m}", 2),
            ("cp /tmp/.last-test-run .claude/", 2),
            (f"cp -l {m} /tmp/m", 2),
            (f"ln {m} /tmp/m", 2),
            (f"sed -i s/a/b/ {m}", 2),
            (f"sed -n 'w {m}' /dev/null", 2),
            (f"f={m}; touch $f", 2),
            (f"for f in {m}; do touch $f; done", 2),
            (f"while read f; do touch \"$f\"; done <<EOF\n{m}\nEOF", 2),
            (f"touch $(echo {m})", 2),
            (f"echo 'x > {m}", 2),
        ])


class PollingLoopTest(GuardCase):
    """Phase 11 C06: self-matching polling loops are blocked; a bare pgrep is allowed."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ("while pgrep -f sbt; do sleep 5; done", 2),
            ("until ! pgrep java >/dev/null; do sleep 10; done", 2),
        ])

    def test_other_loops_blocked(self):
        self.check([
            ("for i in 1 2 3; do sleep 2; done", 2),
            ("while true; do ps aux | grep sbt; done", 2),
            ("while :; do pidof java; done", 2),
            ("watch -n 5 pgrep sbt", 2),
            ("bash -c 'while pgrep sbt; do sleep 1; done'", 2),
            ("timeout 600 bash -c 'until test -f done.txt; do sleep 5; done'", 2),
        ])

    def test_ordinary_commands_allowed(self):
        self.check([
            ("pgrep -f sbt", 0),
            ("ps aux | grep sbt", 0),
            ('while read -r f; do echo "$f"; done < list.txt', 0),
            ("for f in a b; do echo $f; done; sleep 1", 0),
        ])


class WholeEnvironmentTest(GuardCase):
    """Phase 11, B-18 and K02 folded in: every way to print the whole environment or a
    password variable is blocked."""

    def test_blocked(self):
        self.check([
            ("/usr/bin/env", 2),
            ("busybox env", 2),
            ("env -u PATH", 2),
            ("export -p", 2),
            ("export", 2),
            ("declare -x", 2),
            ("declare -p", 2),
            ("set", 2),
            ("printenv DB_PASSWORD", 2),
            ('printenv "$NAME"', 2),
            ("cat /proc/self/environ", 2),
            ("strings /proc/$$/environ", 2),
        ])

    def test_allowed(self):
        self.check([
            ("export FOO=1", 0),
            ("declare -f", 0),
            ("set -euo pipefail", 0),
            ("printenv PWD", 0),
            ("env FOO=1 sbt check", 0),
        ])


class HeredocFedToShellTest(GuardCase):
    """Phase 11 round 2, R-1: a heredoc piped (directly or through cat or tee) into a
    shell, interpreter, eval, source or xargs, inside $(...) or <(...), in a piped
    subshell or group, or written to a script that the same command runs, is commands."""

    M = ".claude/.last-test-run"
    G = "datasets/gold"

    def test_reviewer_probes_blocked(self):
        m, g = self.M, self.G
        self.check([
            (f"cat <<'X' | bash\ntouch {m}\nX", 2),
            ("cat <<'X' | bash\nsbt markTestRun\nX", 2),
            (f"cat <<'X' | python3\nopen('{m}','a')\nX", 2),
            (f"tee /dev/stdout <<'X' | bash\ntouch {m}\nX", 2),
            (f"cat <<'X' | bash\nrm -rf {g}\nX", 2),
            ("cat <<'X' | bash\nsbt 'eval 1'\nX", 2),
            ("cat <<'X' | bash\nwhile pgrep -f sbt; do sleep 5; done\nX", 2),
        ])

    def test_sibling_shapes_blocked(self):
        m, g = self.M, self.G
        self.check([
            (f"cat <<'X' |& bash\ntouch {m}\nX", 2),
            (f"cat <<'X' | cat | tee /dev/null | sh -s\ntouch {m}\nX", 2),
            (f"cat <<'X' | xargs -I{{}} sh -c '{{}}'\ntouch {m}\nX", 2),
            (f"cat <<'X' | xargs touch\n{m}\nX", 2),
            (f"cat <<'X' | env -S 'bash -s'\nrm -rf {g}\nX", 2),
            (f"cat <<'X' | $SH\ntouch {m}\nX", 2),
            (f"cat <<'X' | python3 -\nimport shutil; shutil.rmtree('{g}')\nX", 2),
            (f"<<'X' bash\ntouch {m}\nX", 2),
            (f"cat <<'X' |\ntouch {m}\nX\nbash", 2),
            (f"bash <(cat <<'X'\ntouch {m}\nX\n)", 2),
            (f"source <(cat <<'X'\nrm -rf {g}\nX\n)", 2),
            (f"$(cat <<'X'\ntouch {m}\nX\n)", 2),
            ("eval \"$(cat <<'X'\nsbt 'eval 1'\nX\n)\"", 2),
            (f"( cat <<'X'\ntouch {m}\nX\n) | bash", 2),
            (f"{{ cat <<'X'\nrm -rf {g}\nX\n}} | bash", 2),
            (f"cat > /tmp/x.sh <<'X'\ntouch {m}\nX\nbash /tmp/x.sh", 2),
            (f"cat > /tmp/x.sh <<'X'\nrm -rf {g}\nX\nbash /tmp/x.sh", 2),
            ("cat > /tmp/x.sh <<'X'\nsbt 'eval 1'\nX\nbash /tmp/x.sh", 2),
            (f"cat <<'X' > /tmp/t.sh\ntouch {m}\nX", 2),
            (f"X=$(cat <<'E'\ntouch {m}\nE\n); eval \"$X\"", 2),
            ("cat <<'X' | bash\nsudo rm -rf /tmp/x\nX", 2),
            ("cat <<'X' | bash\ncat .e*\nX", 2),
            ("cat > /tmp/x <<'EOF'\nhi\nEOF\ncat .e*", 2),
        ])

    def test_herestrings_and_hand_offs_blocked(self):
        # HEAD blocked these (every segment had to be a reader); round 1 did not.
        m, g = self.M, self.G
        self.check([
            (f'bash <<< "touch {m}"', 2),
            (f"python3 <<< \"open('{m}','a')\"", 2),
            (f'cat <<< "touch {m}" | bash', 2),
            (f'bash <<< "rm -rf {g}"', 2),
            (f"xargs touch < <(echo {m})", 2),
            (f"echo {m} > /tmp/l; xargs touch < /tmp/l", 2),
            (f'while read f; do touch "$f"; done < <(echo {m})', 2),
            (f"printf '%s' {m} > /tmp/l && touch $(cat /tmp/l)", 2),
            (f"ls {m} | xargs touch", 2),
            ("find .claude -name '.last-*' -exec touch {} +", 2),
            ("find .claude -name '.last-*' > /tmp/l; xargs touch < /tmp/l", 2),
        ])

    def test_harmless_heredocs_allowed(self):
        m = self.M
        self.check([
            ("python3 - <<'EOF'\nprint('hello')\nEOF", 0),
            ("bash <<'X'\necho hello\nX", 0),
            (f"cat {m} | head -1", 0),
            (f"cat {m} > /tmp/copy; diff /tmp/copy /tmp/other", 0),
        ])

    def test_report_heredocs_blocked_round4(self):
        # Round 4 (human, fail closed): every heredoc body and here-string word is
        # commands, and a shell never reads a pipe; report text goes through the Write tool.
        m, g = self.M, self.G
        self.check([
            (f"cat > /tmp/r.md <<'EOF'\nmarker {m} is fresh after sbt check; it's fine\nEOF", 2),
            (f"cat > /tmp/r.md <<'EOF'\nThe labels in {g}/er-gold-v1.csv were not touched\nEOF", 2),
            (f"cat <<'EOF' | grep -c fresh\n{m} is fresh\nEOF", 2),
            (f"mkdir -p /tmp/r && cat > /tmp/r/a.md <<'EOF'\nmarkTestRun ran\nEOF\nwc -l /tmp/r/a.md", 2),
            ("cat <<'X' | bash\necho hello\nX", 2),
            ("git log --oneline -3 && cat > /tmp/n.md <<'EOF'\nsbt check passed; it's green\nEOF", 2),
            (f'grep -c x <<< "{m}"', 2),
        ])


class SbtLauncherOptionTest(GuardCase):
    """Phase 11 round 2, R-2: sbt launcher options are allow-listed in any position and
    inside quotes, also through timeout; environment overrides for sbt are refused except
    the heap settings the repository uses. R-6: later words after a test pattern."""

    def test_reviewer_probes_blocked(self):
        self.check([
            ('timeout 30m sbt "core/testOnly * " "--addPluginSbtFile=/tmp/p.sbt"', 2),
            ('timeout 30m sbt "core/testOnly * " "-addPluginSbtFile=/tmp/p.sbt"', 2),
            ('timeout 30m sbt "core/testOnly *" "-Duser.home=/tmp/e"', 2),
            ('timeout 30m sbt "core/testOnly *" -J-Duser.home=/tmp/e', 2),
            ('timeout 30m sbt "core/testOnly *" -Dsbt.coursier.home=/tmp/c', 2),
            ('sbt "core/testOnly *; all core/test core/console"', 2),
        ])

    def test_other_options_and_environment_blocked(self):
        self.check([
            ('sbt " --addPluginSbtFile=/tmp/p.sbt" check', 2),
            ('sbt "compile --addPluginSbtFile=/tmp/p.sbt"', 2),
            ("sbt -J-Xmx2g -J-javaagent:/tmp/a.jar check", 2),
            ("sbt -mem /tmp check", 2),
            ("sbt -- check", 2),
            ("SBT_OPTS=-Duser.home=/tmp/e sbt check", 2),
            ("SBT_OPTS='-Xmx2g -Duser.home=/tmp/e' timeout 45m sbt check", 2),
            ("JAVA_OPTS=-Duser.home=/tmp/e sbt check", 2),
            ("HOME=/tmp/e timeout 45m sbt check", 2),
            ("XDG_CONFIG_HOME=/tmp/e sbt check", 2),
            ("JAVA_HOME=/tmp/jdk sbt check", 2),
            ("JAVACMD=/tmp/evil sbt check", 2),
            ("BASH_ENV=/tmp/x.sh sbt check", 2),
            ("env HOME=/tmp/e sbt check", 2),
            ("export HOME=/tmp/e; sbt check", 2),
            ("java -Duser.home=/tmp/e -jar ~/.sbt/launchers/sbt-launch.jar check", 2),
            ("printf 'eval 1' | xargs sbt check", 2),
            ('sbt "core/testOnly * core/console"', 2),
            ('sbt "core/test core/eval"', 2),
        ])

    def test_legitimate_forms_allowed(self):
        self.check([
            ("sbt -batch -no-colors check", 0),
            ("sbt -mem 1536 check", 0),
            ("sbt -Dsbt.server.forcestart=true check", 0),
            ("sbt -Dsbt.color=false -batch check", 0),
            ("sbt -J-Xmx2g check", 0),
            ('SBT_OPTS="-Xmx2560m -Xss4m" timeout 3000 sbt -batch check', 0),
            ('SBT_OPTS="-Xmx2g -Xss4m" sbt -batch "adversarialTests/testOnly *BenchmarkWorkflowSuite"', 0),
            ('sbt "all core/test rag/test"', 0),
            ("sbt --script-version", 0),
        ])


class GoldGlobAndVariableTest(GuardCase):
    """Phase 11 round 2, R-3: a delete or move whose glob under datasets can match gold, or
    an xargs deleter fed with such names, is blocked; $HOME resolves like ~. R-5: a plain
    variable plus a slash is treated like a bare variable."""

    R = os.path.relpath(ROOT, os.environ.get("HOME") or "/")   # the repository under $HOME

    def test_reviewer_probes_blocked(self):
        self.check([
            ("rm -rf datasets/*", 2),
            ("cd datasets && rm -rf *", 2),
            ("rm -rf ./datasets/*", 2),
            ("rm -r datasets/*/", 2),
            (f'rm -rf "$HOME/{self.R}/datasets"', 2),
            ("ls datasets/* | xargs rm -rf", 2),
        ])

    def test_other_forms_blocked(self):
        self.check([
            ("rm -rf ${HOME}/" + self.R + "/datasets/*", 2),
            ('rm -rf "$HOME"', 2),
            ("find . -type f | xargs rm -f", 2),
            ("git ls-files | xargs rm -f", 2),
            ("mv datasets/* /tmp/", 2),
            ("rm -rf datasets/[a-h]*", 2),
            ('rm -rf "$PWD"', 2),
            ('rm -rf "$PWD/"', 2),
            ('rm -rf "$(pwd)/"', 2),
            ('rm -rf "$S"/*', 2),
            ('rm -rf "$S/datasets"', 2),
            ('rm -rf "$S/../"', 2),
            (f'cp -r /tmp/gold "$HOME/{self.R}/datasets/"', 2),
        ])

    def test_paths_that_cannot_reach_gold_allowed(self):
        self.check([
            ("rm -rf datasets/*.tmp", 0),
            ("rm -f datasets/*.json.bak", 0),
            ("find . -name '*.pyc' | xargs rm -f", 0),
            ("ls -la datasets/*", 0),
            ('cp "$S/a.md" "$S/b.md" "$D/"', 0),
            ('cp -p "$S/a" "$D/"', 0),
            ('mv "$A" "$B/"', 0),
            ('rsync -a /tmp/x/ "$D/"', 0),
            ('rm -rf "$S/"', 0),
        ])


class HeredocQuotedEndMarkerTest(GuardCase):
    """Phase 11 repair round 3, S-1, superseded by round 4 (human, fail closed): every
    heredoc body is commands, whatever its end marker, and an end marker the guard cannot
    parse with certainty blocks the command."""

    M = ".claude/.last-test-run"
    G = "datasets/gold"

    def test_reviewer_unquoted_bodies_blocked(self):
        m, g = self.M, self.G
        self.check([
            (f"cat > /tmp/r.md <<X\n$(touch {m})\nX", 2),
            (f"cat > /tmp/r.md <<X\n`touch {m}`\nX", 2),
            (f"cat > /tmp/r.md <<X\n$(cp /tmp/f {m})\nX", 2),
            ("cat > /tmp/r.md <<X\n$(sbt markTestRun)\nX", 2),
            ("cat > /tmp/r.md <<X\n`sbt markTestRun`\nX", 2),
            (f"cat > /tmp/r.md <<X\n$(python3 -c \"open('{m}','a')\")\nX", 2),
            (f"cat > /tmp/r.md <<X\n${{Y:-$(touch {m})}}\nX", 2),
            (f"cat > /tmp/r.md <<-X\n\t$(touch {m})\n\tX", 2),
            ("cat <<X\n`cat .env`\nX", 2),
            ("cat <<X\n$(cat .env)\nX", 2),
            ("cat <<X\n${A:-`cat .env`}\nX", 2),
            (f"cat > /tmp/r.md <<X\n$(rm -rf {g})\nX", 2),
            (f"echo ${{Y:-$(touch {m})}}", 2),
            ("echo ${A:-$(cat .env)}", 2),
        ])

    def test_reviewer_misread_end_markers_blocked(self):
        m, g = self.M, self.G
        self.check([
            (f"cat > /tmp/r.md <<'END-R'\nnotes\nEND-R\ntouch {m}", 2),
            (f"cat > /tmp/r.md <<END.R\nnotes\nEND.R\ntouch {m}", 2),
            (f"cat > /tmp/r.md <<X'Y'\nnotes\nXY\ntouch {m}", 2),
            ("cat > /tmp/r.md <<'END-R'\nnotes\nEND-R\nsbt 'eval 1'", 2),
            ("cat > /tmp/r.md <<END.R\nnotes\nEND.R\nsbt 'eval 1'", 2),
            ("cat > /tmp/r.md <<X'Y'\nnotes\nXY\nwhile pgrep -f sbt; do sleep 5; done", 2),
            ("cat > /tmp/r.md <<END.R\nnotes\nEND.R\nprintenv", 2),
            (f"cat > /tmp/r.md <<X'Y'\nnotes\nXY\nrm -rf {g}", 2),
            (f"cat > /tmp/r.md <<\\X\nnotes\nX\ntouch {m}", 2),
            (f"cat > /tmp/r.md <<'X\nnotes\nX\ntouch {m}", 2),
        ])

    def test_sibling_shapes_blocked(self):
        m = self.M
        self.check([
            (f"echo \"<<'X'\"\ntouch {m}\nX", 2),
            (f"echo '<<X' # <<'Y'\ntouch {m}\nY", 2),
            (f"cat > /tmp/r.md <<'X'\nabc\\\nX\ntouch {m}\nX", 2),
            (f"cat > /tmp/r.md <<X\nfoo\\\nX\ncat <<'Y'\n$(touch {m})\nY\nX", 2),
            ("cat <<'X' | rbash\nsbt 'eval 1'\nX", 2),
            ("cat <<'X' | ash\nsbt 'eval 1'\nX", 2),
            ("cat <<'X' | fish\nsbt 'eval 1'\nX", 2),
            (f"cat <<'X' | script -qc bash\ntouch {m}\nX", 2),
            (f"cat <<'X' | ssh host\ntouch {m}\nX", 2),
            (f"{{ cat <<'X'; }} | bash\ntouch {m}\nX", 2),
            (f"read -r -d '' V <<'X'\ntouch {m}\nX\neval \"$V\"", 2),
            (f"cat > /tmp/r.md <<'X'\ntouch {m}\nX\nbash /tmp/r.md", 2),
            (f"cat > /tmp/r.md <<'X'\ntouch {m}\nX\nsh < /tmp/r.md", 2),
            (f"python3 - <<'EOF'\nopen('{m}','a')\nEOF", 2),
            (f"perl <<'EOF'\nopen(F,'>','{m}')\nEOF", 2),
            ("cat <<'X' | xargs -0 printenv\nGITHUB_PAT\nX", 2),
        ])

    def test_safe_probes(self):
        # Never executed: a recursive S3 delete, a polling loop, a harmless report.
        self.check([
            ("aws s3 rm s3://example-bucket/prefix --recursive", 2),
            ("while pgrep -f sbt; do sleep 5; done", 2),
            (f"cat > /tmp/r.md <<'EOF'\nThe marker {self.M} is fresh after sbt check; markTestRun wrote it.\nEOF", 2),  # round 4: every heredoc body is commands
        ])

    def test_quoted_report_heredocs_blocked_round4(self):
        # Round 3 allowed these as data; round 4 (human) reads every body as commands.
        m, g = self.M, self.G
        self.check([
            (f"cat > /tmp/r.md <<\"EOF\"\nmarker {m}; {g} untouched; it's fine\nEOF", 2),
            (f"cat > /tmp/r.md <<'END-R'\nmarker {m} fresh\nEND-R", 2),
            (f"cat > /tmp/r.md <<'EOF'\nlabels in {g}/x.csv unchanged\nEOF", 2),
            (f"mkdir -p /tmp/r && cat >> /tmp/r/a.md <<'EOF'\n{m} and {g}\nEOF\nwc -l /tmp/r/a.md", 2),
            (f"cat <<'EOF' | grep -c fresh\n{m} is fresh\nEOF", 2),
        ])

    def test_unquoted_report_naming_the_marker_blocked(self):
        # Accepted false block (human): an unquoted body is commands.
        self.check([(f"cat > /tmp/r.md <<EOF\nthe marker {self.M} is fresh\nEOF", 2)])


class BuildInputWriteTest(GuardCase):
    """Phase 11 repair round 3 (human): no shell write to project/, ~/.sbt or ~/.ivy2;
    project/ changes go only through the Write tool and its ownership check. Reads, a copy
    from them, git reads and sbt itself stay allowed."""

    def test_writes_blocked(self):
        home = os.environ.get("HOME") or "/root"
        self.check([
            ("echo x > project/evil.sbt", 2),
            ("echo x >> ./project/plugins.sbt", 2),
            (f"echo x > {ROOT}/project/evil.sbt", 2),
            ("printf x | tee -a project/build.properties", 2),
            ("cp /tmp/p.sbt project/", 2),
            ("cp -t project /tmp/p.sbt", 2),
            ("mv /tmp/a project/plugins.sbt", 2),
            ("mv project/plugins.sbt /tmp/a", 2),
            ("ln -s /tmp/a project/evil.sbt", 2),
            ("install -m 644 /tmp/a project/a.sbt", 2),
            ("rsync -a /tmp/x/ project/", 2),
            ("rm -f project/Versions.scala", 2),
            ("rm -rf project", 2),
            ("sed -i s/a/b/ project/Versions.scala", 2),
            ("perl -pi -e s/a/b/ project/Versions.scala", 2),
            ("mkdir -p project/extra", 2),
            ("touch project/x.sbt", 2),
            ("cd project && echo x > evil.sbt", 2),
            ("cd project; touch a.sbt", 2),
            ("echo x > ~/.sbt/1.0/global.sbt", 2),
            ("echo x > $HOME/.sbt/repositories", 2),
            ("echo x > ${HOME}/.sbt/repositories", 2),
            ('echo x > "$HOME/.ivy2/local/x"', 2),
            ("tee ~/.sbt/repositories < /tmp/r", 2),
            ("cp /tmp/p.sbt ~/.sbt/1.0/plugins/", 2),
            ("rsync -a /tmp/x/ ~/.ivy2/", 2),
            ("rm -rf ~/.ivy2/cache", 2),
            ("mkdir -p ~/.sbt/1.0/plugins", 2),
            ("cd ~/.sbt && touch x", 2),
            ("cd && cd .sbt && touch x", 2),
            (f"rm -rf {home}/.sbt", 2),
            ("ln -sf /tmp/evil ~/.sbt/1.0/plugins", 2),
            ("cat > project/evil.sbt <<'EOF'\naddSbtPlugin(x)\nEOF", 2),
            ("python3 -c \"open('project/evil.sbt','w').write('x')\"", 2),
            ("python3 - <<'EOF'\nimport shutil; shutil.copy('/tmp/x', 'project/evil.sbt')\nEOF", 2),
            ("sed -n 'w project/out' build.sbt", 2),
            ("find project -name '*.sbt' -delete", 2),
            ("ls project | xargs rm", 2),
            ("git checkout -- project/Versions.scala", 2),
            ("git diff --output=project/x.sbt", 2),
            ("cp -l project/Versions.scala /tmp/v", 2),
            ("cp --link project/Versions.scala /tmp/v", 2),
            ("ln project/Versions.scala /tmp/v", 2),
        ])

    def test_reads_and_other_paths_allowed(self):
        self.check([
            ("git status", 0),
            ("git diff -- project/ build.sbt", 0),
            ("git log --oneline -5 -- project/Versions.scala", 0),
            ("git diff project/", 0),
            ("ls project", 0),
            ("ls -la ~/.sbt ~/.ivy2", 0),
            ("cat project/build.properties", 0),
            ("cat build.sbt", 0),
            ("grep -n Versions project/*.scala build.sbt", 0),
            ("grep -rn addSbtPlugin project/plugins.sbt", 0),
            ("sed -n '1,40p' project/Versions.scala", 0),
            ("sed -n 1,20p build.sbt", 0),
            ("find project -name '*.scala'", 0),
            ("find ~/.sbt -maxdepth 2 -type d", 0),
            ("cp project/build.properties /tmp/bp", 0),
            ("cp -r project /tmp/project-copy", 0),
            ("wc -l project/*.scala", 0),
            ("head -5 ~/.sbt/repositories", 0),
            ("timeout 45m sbt check", 0),
            ("timeout 30m sbt storage/test", 0),
            ('SBT_OPTS="-Xmx2560m -Xss4m" timeout 45m sbt -batch check', 0),
            ("mkdir -p modules/x/myproject/tmp", 0),
            ("touch modules/x/myproject/a", 0),
            ("echo x > /tmp/project/x", 0),
            ("rm -rf target/streams", 0),
            ("cat > /tmp/r.md <<'EOF'\nproject/Versions.scala and ~/.sbt were read, not written\nEOF", 2),  # round 4: every heredoc body is commands
            ("git status --short && git diff --stat", 0),
        ])


class FailClosedRound4Test(GuardCase):
    """Phase 11 repair round 4 (human decision): the guard fails closed. Every heredoc body
    is commands; a command the parser cannot fully parse is blocked (<< inside ${...} or
    $[...] too, review 4 T-1); test, [ and [[ with -v or other evaluating options are code
    (T-3); a scan over its size or time limit blocks, never allows (T-2)."""

    M = ".claude/.last-test-run"
    G = "datasets/gold"

    def payloads(self):
        m, g = self.M, self.G
        return [f"touch {m}", "sbt markTestRun", f"rm -rf {g}",
                "while pgrep -f sbt; do sleep 5; done", "printenv",
                "cp /tmp/x.sbt project/x.sbt", "echo a >> ~/.sbt/x", "cat .env",
                "terraform apply -auto-approve", "git push --force",
                "echo a >> project/plugins.sbt"]

    def test_t1_heredoc_inside_brace_or_arithmetic_blocked(self):
        # Review 4 T-1: bash reads << inside ${...} or $[...] as word text, so the next
        # line is a command; every such << is doubt, and doubt blocks.
        pre = ["cat ${x=|cat <<'EOF' }", "cat ${x=;cat <<'EOF' }", 'cat ${x=|cat <<"EOF" }',
               "cat $[x<<'EOF' ]", "cat $((x<<'EOF' ))", "cat \"${x=|cat <<'EOF' }\"",
               "echo ${x:-<<EOF}"]
        self.check([(p + "\n" + line + "\nEOF", 2) for p in pre for line in self.payloads()])

    def test_t3_evaluating_test_forms_blocked(self):
        # Review 4 T-3 and its variants: the operand of test -v (and printf -v) is
        # evaluated as a variable name, so a subscript in it runs commands.
        m = self.M
        self.check([
            (f"cat > /tmp/f.txt <<'EOF'\na[$(touch {m})]\nEOF\ntest -v \"$(cat /tmp/f.txt)\"", 2),
            (f"cat > /tmp/f.txt <<'EOF'\na[$(touch {m})]\nEOF\nprintf -v \"$(cat /tmp/f.txt)\" x", 2),
            (f"printf -v \"$(cat <<'EOF'\na[$(touch {m})]\nEOF\n)\" x", 2),
            ("cat > /tmp/f.txt <<'EOF'\na[$(cat .env >&2)]\nEOF\nprintf -v \"$(cat /tmp/f.txt)\" x", 2),
            ("test -v \"$(cat /tmp/f.txt)\"", 2),
            ("[ -v \"$x\" ]", 2),
            ("test -R \"$x\"", 2),
            ("[ -v 'a[$(id)]' ]", 2),
            ("x=$(cat /tmp/f.txt); [[ -v $x ]]", 2),
            ("[[ -R $x ]]", 2),
            ("x=$(cat /tmp/f.txt); [[ $x -eq 1 ]]", 2),
            ("[[ \"$x\" -lt 3 ]]", 2),
            ("[[ x -gt 0 ]]", 2),
            ("[[ 1 -ne $(cat /tmp/f.txt) ]]", 2),
            ("[[ -f a && $n -ge 2 ]]", 2),
            ("bash -c '[[ $1 -eq 1 ]]' _ x", 2),
        ])

    def test_other_evaluation_sites_blocked(self):
        self.check([
            ("x=$(cat /tmp/f.txt); (( x ))", 2),
            ("for ((i=0; i<3; i++)); do echo $i; done", 2),
            ("echo $(( $(cat /tmp/f.txt) + 1 ))", 2),
            ("echo $(( x + 1 ))", 2),
            ("echo $[ x ]", 2),
            ("let x=1", 2),
            ("echo \"${arr[$(cat /tmp/f.txt)]}\"", 2),
            ("echo \"${arr[i]}\"", 2),
            ("r=$(cat /tmp/f.txt); echo \"${!r}\"", 2),
            ("x=$(cat /tmp/f.txt); echo \"${x@P}\"", 2),
            ("echo \"${s:$n}\"", 2),
            ("arr[$(cat /tmp/f.txt)]=1", 2),
            ("arr['$(id)']=1", 2),
            ("a=([$x]=1)", 2),
            ("declare -i y=\"$x\"", 2),
            ("local -n r=x", 2),
            ("declare 'a[$(id)]=1'", 2),
            ("read \"$x\" <<< v", 2),
            ("read -a \"$x\" <<< v", 2),
            ("unset \"$x\"", 2),
            ("mapfile -C cb -c 1 a < /tmp/f.txt", 2),
            ("wait -p \"$x\"", 2),
            ("BASH_ENV=/tmp/x bash -c true", 2),
            ("export BASH_ENV=/tmp/x", 2),
            ("PS4='$(id)'; set -x; ls", 2),
            ("env PROMPT_COMMAND=id bash -i", 2),
            ("SHELLOPTS=xtrace bash -c ls", 2),
            ("trap 'id' EXIT", 2),
            ("alias ls='rm -rf /tmp/x'", 2),
            ("bind -x '\"\\C-x\": id'", 2),
            ("complete -C id ls", 2),
            ("enable -f /tmp/x.so x", 2),
            ("coproc cat /tmp/x", 2),
            ("fc -s", 2),
        ])

    def test_code_from_pipes_and_expansions_blocked(self):
        # Review 3 S-2 (text fed to a shell without a heredoc) and S-3 (exported functions
        # into sbt), closed by the same rule: code the guard cannot see is blocked.
        g = self.G
        self.check([
            (f"echo 'rm -rf {g}' | bash", 2),
            (f"echo 'rm -rf {g}' | sh -s", 2),
            (f"printf 'rm -rf {g}' | tee /dev/null | bash", 2),
            ("cat /tmp/x.sh | (bash)", 2),
            ("echo ls | { bash; }", 2),
            ("echo ls | bash -o pipefail", 2),
            ("echo ls | xargs bash", 2),
            ("echo ls | script -q /dev/null", 2),
            ("bash <(echo ls)", 2),
            ("bash < <(echo ls)", 2),
            ("source <(echo ls)", 2),
            (". /dev/stdin", 2),
            ("bash /dev/stdin", 2),
            ("bash \"$f\"", 2),
            ("bash < \"$f\"", 2),
            ("x='ls'; eval \"$x\"", 2),
            ("eval \"$(cat /tmp/x)\"", 2),
            ("sh -c \"$x\"", 2),
            ("bash -c \"$(cat /tmp/x)\"", 2),
            ("env -S \"$x\"", 2),
            ("java() { :; }; export -f java; timeout 45m sbt check", 2),
            ("declare -fx java", 2),
            ("env 'BASH_FUNC_java%%=() { :; }' sbt check", 2),
            ("find . -name x -exec sbt 'eval 1' \\;", 2),
            (f"function f {{ rm -rf {g}; }}; f", 2),
        ])

    def test_unparsable_commands_blocked(self):
        m = self.M
        self.check([
            ("echo $'\\x74ouch' x", 2),
            (f"$'\\164ouch' {m}", 2),
            ("echo $\"hello\"", 2),
            ("case x in a) echo a;; esac", 2),
            ("echo 'unbalanced", 2),
            ("echo \"unbalanced", 2),
            ("echo $(echo x", 2),
            ("echo )", 2),
            ("cat <<EOF\nno end marker", 2),
            ("cat <<'A B'\nx\nA B", 2),
            (f"cat <<X\nfoo\\\nX\ntouch {m}", 2),
            ("echo $(cat <<'EOF'\nx\nEOF\n)", 2),
            ("echo " + "$(echo " * 6 + "x" + ")" * 6, 2),
            ("echo $((1<<2))", 2),
            ("echo ${x:-$'a'}", 2),
            ("cat > /tmp/r.md <<'EOF'\nit's prose\nEOF", 2),
            ("echo " + "{a,b}" * 12, 2),
        ])

    def test_t2_size_and_time_bound(self):
        # Review 4 T-2: a long run of heredocs took 15 s (over the 10 s hook timeout, which
        # Claude Code 2.1.332 treats as a non-blocking error). Now the size cap, the
        # heredoc cap and the linear scan block it at once; every case must also end well
        # inside the hook timeout.
        import time
        m = self.M
        hd = "cat > /tmp/r.md <<'EOF'\nline\nEOF\n"
        hdl = "cat > /tmp/r.md <<'EOF'\n" + "x" * 200 + "\nEOF\n"
        for command in (hd * 500 + f"touch {m}", hd * 1000 + f"touch {m}",
                        hdl * 300 + f"touch {m}", hd * 17 + "echo done",
                        "echo " + "a" * 16400, "a;" * 8150, "ls " + "*.x " * 4000):
            with self.subTest(size=len(command), start=command[:30]):
                t = time.time()
                rc = run_hook(command).returncode
                spent = time.time() - t
                self.assertLess(spent, 8.0)
                if command.startswith(("cat", "echo a")):
                    self.assertEqual(rc, 2)

    def test_deadline_crash_and_bad_input_block(self):
        import shutil, tempfile
        # A deadline the scan cannot meet (a test may only lower it) is a block.
        self.check([("ls", 2)], env={"SCHOLARCHAIN_GUARD_DEADLINE": "0.05"})
        self.check([("ls", 0)], env={"SCHOLARCHAIN_GUARD_DEADLINE": "9"})
        with tempfile.TemporaryDirectory() as d:
            for tool in ("bash", "cat", "env", "timeout"):
                os.symlink(shutil.which(tool), f"{d}/{tool}")
            self.check([("ls", 2)], env={"PATH": d})          # no python3: no verdict
            os.unlink(f"{d}/timeout")
            self.check([("ls", 2)], env={"PATH": d})          # no timeout: status 127
        for raw in ("not json", "[1, 2]", "{"):
            with self.subTest(raw=raw):
                r = subprocess.run([HOOK], input=raw, capture_output=True, text=True)
                self.assertEqual(r.returncode, 2, r.stderr)

    def test_ordinary_commands_allowed(self):
        self.check([
            ("git status --short", 0),
            ("git diff --stat HEAD -- project build.sbt", 0),
            ("git log --oneline -5 --format='%h %s'", 0),
            ("git show HEAD:build.sbt | head -20", 0),
            ("ls -la modules/core", 0),
            ("cat build.sbt", 0),
            ("grep -rn 'def run(' modules/rag/src | head", 0),
            ("rg -n \"def \\w+\\(\" modules | wc -l", 0),
            ("sed -n '1,20p' build.sbt", 0),
            ("find modules -name '*.scala' -not -path '*/target/*' | wc -l", 0),
            ("find modules -name '*.scala' -exec grep -l Config {} +", 0),
            ("wc -l STATUS.md PROJECT_MEMORY.md", 0),
            ("head -5 build.sbt; tail -n 3 build.sbt", 0),
            ("test -f build.sbt && [ -d modules ] && [[ -e project ]] && echo yes", 0),
            ("test -v HOME && [[ -v PATH ]] && echo set", 0),
            ("[[ $? -eq 0 ]] && [[ $# -gt 0 || 1 -lt 2 ]] && echo ok", 0),
            ("echo $((60 * 25)) $[2+2]", 0),
            ("echo \"${HOME}/x ${#PATH} ${x:-default} ${arr[@]} ${s:0:3} ${!BASH*}\"", 0),
            ("timeout 45m sbt check", 0),
            ("timeout 30m sbt \"core/testOnly *\"", 0),
            ("SBT_OPTS=\"-Xmx2560m -Xss4m\" timeout 30m sbt storage/test 2>&1 | tail -40", 0),
            ("for f in modules/*/MODULE.md; do wc -l \"$f\"; done", 0),
            ("awk '{print $1}' build.sbt | sort | uniq -c | head", 0),
            ("ls # it's fine", 0),
            ("cat <<'EOF'\nhello world\nEOF", 0),
            ("bash -n .agents/render.sh && .agents/render.sh --check", 0),
            ("git ls-files | while IFS= read -r f; do echo \"$f\"; done | head", 0),
            ("git ls-files '*.md' | xargs wc -l | tail -1", 0),
            ("python3 -c 'import json; print(json.dumps({\"a\": 1}))'", 0),
            ("bash -c 'echo hi' && sh -c 'ls \"$1\"' _ x", 0),
            ("export PATH=\"$HOME/bin:$PATH\"; read -r a b <<< 'x y'; unset a", 0),
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
