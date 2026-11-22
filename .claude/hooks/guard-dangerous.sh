#!/usr/bin/env bash
# PreToolUse hook - the guard archetype. Harness-neutral: registered by
# .claude/settings.json and .codex/hooks.json for the shell tool (Bash) and for the
# aws-api MCP tool (mcp__aws-api__call_aws). Both harnesses send the command in
# tool_input.command (call_aws sends tool_input.cli_command) and treat exit 2 with stderr
# as "block, show the message to the model".
# A deterministic backstop under the permission rules: it blocks command shapes that must
# never run from an agent context, however the model was prompted, and shell reads of
# secret files (Codex has no per-file read deny rule).
# Owner: factory-engineer. Floor invariant: patterns may be added, never removed.
# Known false positive: patterns also match prose inside heredocs or messages; reword
# the prose, never weaken the list. Fails open if the payload cannot be parsed.
set -uo pipefail

payload=$(cat)
cmd=$(printf '%s' "$payload" | python3 -c '
import json, sys
d = json.load(sys.stdin)
ti = d.get("tool_input", {}) or {}
c = ti.get("command") or ti.get("cmd") or ti.get("cli_command") or ""
print(" ".join(map(str, c)) if isinstance(c, list) else c)
' 2>/dev/null || true)
[ -z "$cmd" ] && exit 0
# Caller identity, the same rule as guard-ownership.sh: the payload field agent_type, else
# SCHOLARCHAIN_ROLE, else the orchestrator (docs/agents.md section 2). Used only to apply
# the tighter incident-responder scale bound; every other check applies to every caller.
caller=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("agent_type") or "")
' 2>/dev/null || true)
caller=${caller:-${SCHOLARCHAIN_ROLE:-orchestrator}}
# A normalized copy closes spelling bypasses: backslash-newline joined, quotes removed,
# runs of spaces and tabs collapsed. Every check below matches the raw command OR this
# copy, so normalization only ever adds matches.
norm=$(printf '%s' "$cmd" | python3 -c '
import re, sys
c = re.sub(r"\\\r?\n", " ", sys.stdin.read())
c = c.replace(chr(34), "").replace(chr(39), "")
sys.stdout.write(re.sub(r"[ \t]+", " ", c))
' 2>/dev/null || printf '%s' "$cmd")

block() {
  echo "Blocked by guard-dangerous hook: $1." \
       "Agents never run this. If it is really needed, a human runs it manually;" \
       "the approved paths are in docs/agents.md section 6." >&2
  exit 2
}

# Structural pass: identify shell operators before shlex decodes words. The guard splits
# the command into segments on unquoted ; & | ( ) ` and newlines, drops redirections,
# strips wrappers and VAR=value prefixes, recurses into sh -c / bash -c strings and eval,
# and checks argv tokens: terraform or tofu with an apply or destroy token; git push with
# a force flag (-f in any short group, --force*, --mirror, +refspec); git commit with
# --no-verify or n in a short group (values of -m, -F and similar are skipped); env with no
# command, printenv with no name, and printenv of a secret-looking name (PAT, TOKEN,
# SECRET, KEY; repair round 3), because they print credential values. It also
# prints each segment in canonical form (quotes and escapes resolved; for aws, global
# options removed so service and operation are adjacent), and the pattern list below is
# matched against those too. Malformed quoting: the pass adds nothing and the raw and
# normalized checks still apply (it fails open, not closed, so odd commands still run).
structural=$(printf '%s' "$cmd" | python3 -c '
import os, re, shlex, sys
raw = sys.stdin.read()
lines, kept, i = raw.split("\n"), [], 0
while i < len(lines):
    kept.append(lines[i])
    delims = re.findall(r"<<-?\s*[\x27\"]?([A-Za-z_][A-Za-z0-9_]*)[\x27\"]?", lines[i])
    i += 1
    for d in delims:
        while i < len(lines) and lines[i].strip() != d:
            i += 1
        i += 1
SEP = set(";&|()`\n")
WRAP = {"command", "exec", "env", "nohup", "time", "nice", "timeout", "stdbuf", "xargs",
        "builtin", "then", "do", "else", "elif", "if", "while", "until", "!", "{", "}",
        "sudo", "doas"}
SHELLS = {"sh", "bash", "zsh", "dash", "ksh"}
AWS_NOVAL = {"--debug", "--no-verify-ssl", "--no-paginate", "--no-sign-request",
             "--no-cli-pager", "--cli-auto-prompt", "--no-cli-auto-prompt"}
PUSH_VAL = {"--push-option", "--repo", "--receive-pack", "--exec"}
COMMIT_VAL = {"--message", "--file", "--reuse-message", "--reedit-message", "--template",
              "--author", "--date", "--fixup", "--squash", "--cleanup", "--trailer",
              "--pathspec-from-file"}
GIT_GLOBAL_VAL = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--config-env",
                  "--super-prefix"}
# A variable name whose value is a credential: an underscore-separated part that is PAT,
# KEY (or ends in KEY), or contains TOKEN or SECRET. PATH, HOME and JAVA_HOME do not match.
SECRET_VAR = re.compile(r"(?:^|_)(?:[A-Z0-9]*(?:TOKEN|SECRET)[A-Z0-9]*|PATS?|[A-Z0-9]*KEYS?)(?:_|$)")
blocks, canon = [], []

# Consume quotes and escapes before recognizing a run of unquoted operators.
syntax = re.compile(
    r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 | (?P<op>[;&|()<>`\n]+)""",
    re.S | re.X,
)

def tokens(s):
    start = 0
    for match in syntax.finditer(s):
        if match.lastgroup == "op":
            for word in shlex.split(s[start:match.start()]):
                yield False, word
            yield True, match.group()
            start = match.end()
    for word in shlex.split(s[start:]):
        yield False, word

def segments(s):
    segs, cur, skip = [], [], False
    for operator, tok in tokens(s):
        if skip:
            skip = False; continue
        if operator and set(tok) <= set("<>&") and ("<" in tok or ">" in tok):
            skip = True; continue
        if operator and set(tok) <= SEP:
            segs.append(cur); cur = []; continue
        cur.append(tok)
    segs.append(cur)
    return [x for x in segs if x]

def flags(args, hit, vals, longval, longhit, optional=""):
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("--"):
            if longhit(a.split("=")[0]): return True
            if "=" not in a and a in longval: i += 1
        elif a.startswith("-") and len(a) > 1:
            for k, ch in enumerate(a[1:]):
                if ch == hit: return True
                if ch in optional: break
                if ch in vals:
                    if k == len(a) - 2: i += 1
                    break
        i += 1
    return False

def analyze(argv, depth):
    env = False
    while argv:
        a = argv[0]
        if a == "eval":
            run(" ".join(argv[1:]), depth + 1); return
        if os.path.basename(a) == "env": env = True
        if a in WRAP or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", a) or re.fullmatch(r"-\S*|\d+[smhd]?", a):
            argv = argv[1:]; continue
        break
    if not argv:
        # env with no command prints every variable, the secret ones included.
        if env: blocks.append("printing the whole environment (env with no command)")
        return
    name, args = os.path.basename(argv[0]), argv[1:]
    if name == "printenv":
        names = [a for a in args if not a.startswith("-")]
        if not names:
            blocks.append("printing the whole environment (printenv with no name)")
        elif any(SECRET_VAR.search(n.upper()) for n in names):
            blocks.append("printing a secret environment variable (a name with PAT, TOKEN, SECRET or KEY)")
    if name == "aws":
        pos, rest, i = [], [], 0
        while i < len(args):
            a = args[i]
            if len(pos) < 2 and a.startswith("--"):
                i += 1 if ("=" in a or a in AWS_NOVAL) else 2; continue
            (pos if len(pos) < 2 else rest).append(a); i += 1
        canon.append(" ".join(["aws"] + pos + rest))
    else:
        canon.append(" ".join([name] + args))
    if name in SHELLS:
        for j, a in enumerate(args):
            if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
                run(args[j + 1], depth + 1); break
    if name in ("terraform", "tofu") and any(a in ("apply", "destroy") for a in args):
        blocks.append("raw terraform apply or destroy (the only apply path is scripts/deploy.sh)")
    if name == "git":
        i = 0
        while i < len(args) and args[i].startswith("-"):
            i += 2 if args[i] in GIT_GLOBAL_VAL else 1
        sub, rest = (args[i], args[i + 1:]) if i < len(args) else ("", [])
        if sub == "push":
            force = lambda t: t.startswith("--force") or (len(t) >= 4 and ("--force".startswith(t) or "--mirror".startswith(t)))
            if flags(rest, "f", "o", PUSH_VAL, force) or any(r.startswith("+") and len(r) > 1 for r in rest):
                blocks.append("a force push")
        if sub == "commit":
            nov = lambda t: t == "--no-verify" or (len(t) >= 9 and "--no-verify".startswith(t))
            if flags(rest, "n", "mFCct", COMMIT_VAL, nov, optional="S"):
                blocks.append("a commit that skips the gate")

def run(s, depth):
    if depth > 4: return
    for seg in segments(s):
        analyze(seg, depth)

try:
    run(re.sub(r"\\\r?\n", " ", "\n".join(kept)), 0)
except ValueError:
    print("UNPARSED"); sys.exit(0)
print(("BLOCK " + blocks[0]) if blocks else "OK")
print("\n".join(canon))
' 2>/dev/null || true)
verdict=${structural%%$'\n'*}
canon=""
[[ "$structural" == *$'\n'* ]] && canon=${structural#*$'\n'}
[[ "$verdict" == "BLOCK "* ]] && block "${verdict#BLOCK }"

# Substring patterns, case-insensitive.
blocked_patterns=(
  # template floor: SQL, filesystem, git
  "DROP TABLE" "DROP DATABASE" "TRUNCATE " "--force-delete" "rm -rf /"
  "push --force" "push -f " "push --force-with-lease" "push --mirror"
  "--no-verify" "core.hooksPath" "filter-branch" "filter-repo" "reflog expire"
  "reset --hard" "update-ref -d"
  # Terraform state surgery
  "terraform destroy" "terraform apply" "tofu apply" "tofu destroy"
  "terraform state rm" "terraform state push" "terraform force-unlock"
  "terraform workspace delete"
  # S3 and S3 Vectors
  "s3 rb" "s3api delete-bucket" "s3api delete-objects" "delete-vector-bucket"
  "s3vectors delete-index" "s3vectors delete-vectors"
  # DynamoDB and Neptune (also through the rds alias)
  "dynamodb delete-table" "dynamodb delete-backup" "delete-db-cluster"
  "delete-db-instance" "delete-graph" "initiateDatabaseReset" "performDatabaseReset"
  "DETACH DELETE" "g.V().drop()" "g.E().drop()" "no-deletion-protection" "reset-graph"
  # ECR, KMS, Secrets Manager, credentials
  "ecr delete-repository" "ecr batch-delete-image" "kms schedule-key-deletion"
  "kms disable-key" "kms delete-alias" "delete-imported-key-material"
  "secretsmanager delete-secret" "secretsmanager get-secret-value"
  "batch-get-secret-value" "--with-decryption" "get-login-password"
  "export-credentials" "aws_secret_access_key"
  # credential-minting reads (repair round 3): they print a session token or a registry
  # password; get-authorization-token also covers ecr-public and codeartifact
  "sts get-session-token" "get-authorization-token"
  # EMR and ECS
  "emr terminate-clusters" "no-termination-protected" "ecs delete-service"
  "ecs delete-cluster" "deregister-task-definition" "delete-task-definitions"
  # other destructive AWS verbs
  "cloudformation delete-stack" "sqs delete-queue" "sqs purge-queue"
  "logs delete-log-group" "cloudtrail delete-trail" "cloudtrail stop-logging"
  "opensearch delete-domain" "aoss delete-collection" "ec2 terminate-instances"
  "elbv2 delete-load-balancer" "stepfunctions delete-state-machine"
  "budgets delete-budget" "iam delete-"
)
shopt -s nocasematch
for pat in "${blocked_patterns[@]}"; do
  [[ "$cmd" == *"$pat"* || "$norm" == *"$pat"* || "$canon" == *"$pat"* ]] \
    && block "the command matches forbidden pattern '$pat'"
done
shopt -u nocasematch

# Shape patterns (extended regex) for forms a substring cannot express.
s='[[:space:]]'
[[ "$cmd" =~ (terraform|tofu)($s+-[^[:space:]]+)*$s+(apply|destroy) ]] \
  && block "raw terraform apply or destroy (the only apply path is scripts/deploy.sh)"
[[ "$cmd" =~ s3$s+rm$s.*--recursive|--recursive.*s3$s+rm$s ]] \
  && block "recursive S3 deletion"
[[ "$cmd" =~ s3$s+sync$s.*--delete ]] && block "S3 sync with --delete"
[[ "$cmd" =~ git$s(.*$s)?push$s(.*$s)?\+[^[:space:]] ]] && block "a force push (+refspec)"
[[ "$cmd" =~ git$s+clean$s+-[a-zA-Z]*f ]] && block "git clean -f"
[[ "$cmd" =~ git$s+commit$s(.*$s)?-n($s|$) ]] && block "a commit that skips the gate"
# The same shapes on the normalized copy, with flags anywhere after the subcommand.
w='[^;&|]'                               # stays inside one command segment
g="git($s-[^[:space:]]+($s[^-[:space:]][^[:space:]]*)?)*$s"   # git plus global options
[[ "$norm" =~ (^|[^[:alnum:]_-])(terraform|tofu)$s($w*$s)?(apply|destroy)($s|$) ]] \
  && block "raw terraform apply or destroy (the only apply path is scripts/deploy.sh)"
[[ "$norm" =~ ${g}push($s$w*)?$s(-[a-zA-Z]*f[a-zA-Z]*|--force[^[:space:]]*|--mirror)($s|$) ]] \
  && block "a force push"
[[ "$norm" =~ ${g}push$s($w*$s)?\+[^[:space:]] ]] && block "a force push (+refspec)"
[[ "$norm" =~ ${g}commit($s$w*)?$s(-[a-zA-Z]*n[a-zA-Z]*|--no-verify)($s|$) ]] \
  && block "a commit that skips the gate"
[[ "$norm" =~ s3$s+rm$s.*--recursive|--recursive.*s3$s+rm$s ]] && block "recursive S3 deletion"
[[ "$norm" =~ s3$s+sync$s.*--delete ]] && block "S3 sync with --delete"
[[ "$canon" =~ s3$s+rm$s.*--recursive|--recursive.*s3$s+rm$s ]] && block "recursive S3 deletion"
[[ "$canon" =~ s3$s+sync$s.*--delete ]] && block "S3 sync with --delete"

# Privilege escalation (sudo, su, doas, pkexec), matched only where a command runs: at
# the start, after ; & | ( $( or a backtick or newline, inside a shell's -c string, or
# after wrappers such as env, exec, nohup, time, nice, timeout and xargs. Heredoc bodies
# and other quoted text are data, so a file that mentions the word is not blocked.
priv=$(printf '%s' "$cmd" | python3 -c '
import re, sys
lines, kept, i = sys.stdin.read().split("\n"), [], 0
while i < len(lines):
    kept.append(lines[i])
    delims = re.findall(r"<<-?\s*[\x27\"]?([A-Za-z_][A-Za-z0-9_]*)[\x27\"]?", lines[i])
    i += 1
    for d in delims:
        while i < len(lines) and lines[i].strip() != d:
            i += 1
        i += 1
c = re.sub(r"(?:^|\s)-c\s+[\x27\"]", ";", "\n".join(kept))
wrap = {"env", "exec", "nohup", "time", "command", "builtin", "nice", "xargs", "timeout",
        "stdbuf"}
for seg in re.split(r"[;&|()`\n]|\$\(", c):
    w = seg.split()
    while w and (w[0] in wrap or "=" in w[0] or re.fullmatch(r"-\S*|\d+[smhd]?", w[0])):
        w.pop(0)
    if w and w[0].rsplit("/", 1)[-1] in ("sudo", "su", "doas", "pkexec"):
        print(w[0]); break
' 2>/dev/null || true)
[ -n "$priv" ] && block "privilege escalation ('$priv'); tools install in user space without root"

# Scale caps, stated in docs/agents.md section 6 (expensive scale change):
# - SCALE_MAX, the general cap for every caller: no instance, task or capacity count
#   above it in an EMR, ECS or autoscaling command;
# - INCIDENT_SCALE_MIN..INCIDENT_SCALE_MAX, the separate and tighter incident-responder
#   bound: when the caller is incident-responder, every such count must lie inside it.
SCALE_MAX=4
INCIDENT_SCALE_MIN=1
INCIDENT_SCALE_MAX=2
if [[ "$cmd" =~ (emr|ecs|application-autoscaling|autoscaling) ]]; then
  counts=$(printf '%s' "$cmd" | grep -oiE \
    '(desired-count|instance-count|max-capacity|min-capacity|InstanceCount|DesiredCount|MaximumCapacityUnits|MaxCapacity|TargetOnDemandCapacity|TargetSpotCapacity)"?[[:space:]]*[=:]?[[:space:]]*"?[0-9]+' \
    | grep -oE '[0-9]+$' || true)
  for n in $counts; do
    # A count too long for shell arithmetic is over every cap (never fail open on it).
    [ "${#n}" -gt 9 ] && block "an EMR or ECS scale-out to $n (cap $SCALE_MAX)"
    n=$((10#$n))
    [ "$n" -gt "$SCALE_MAX" ] && block "an EMR or ECS scale-out to $n (cap $SCALE_MAX)"
    if [ "$caller" = "incident-responder" ] \
       && { [ "$n" -lt "$INCIDENT_SCALE_MIN" ] || [ "$n" -gt "$INCIDENT_SCALE_MAX" ]; }; then
      block "incident-responder scaling to $n (its bound is $INCIDENT_SCALE_MIN to $INCIDENT_SCALE_MAX tasks; anything else goes to the human)"
    fi
  done
fi

# scripts/cleanup.sh is human-only; agents may only inspect it.
if [[ "$cmd" == *cleanup.sh* ]]; then
  ok=1
  while IFS= read -r seg; do
    [[ "$seg" == *cleanup.sh* ]] || continue
    seg="${seg#"${seg%%[![:space:]]*}"}"
    [[ "$seg" =~ ^(bash$s+-n|sh$s+-n|shellcheck|cat|head|tail|grep|wc|ls|stat|git$s+(diff|log|show|status))$s ]] || ok=0
  done < <(printf '%s\n' "$cmd" | tr ';&|' '\n\n\n')
  [ "$ok" -eq 1 ] || block "running scripts/cleanup.sh (human only)"
fi

# Shell writes to human-gate.md (human only). A command that names human-gate.md
# literally is blocked when it also holds a write shape: a redirection, tee, sed -i, mv,
# cp, rm, truncate, dd, install, ln, touch, chmod, shred, unlink, find -delete, -exec or
# -ok, git --output, checkout or restore, or an interpreter (python, perl, ruby, node,
# php, awk). Round 3 added touch, chmod, shred, unlink, the find actions, the git forms,
# the interpreters, and perl without -i. A write that does not name the file
# literally (a glob, a variable, an ancestor directory) is not matched; the file-tool deny
# rules, git diff, the pre-commit gate and the human gate review are the backstop.
if [[ "$cmd" == *human-gate.md* || "$norm" == *human-gate.md* ]] \
   && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s|mv$s|cp$s|rm$s|truncate|dd$s|install$s|ln$s|touch$s|chmod$s|shred|unlink|-delete|-exec|$s-ok|--output|checkout$s|restore$s|python|ruby$s|node$s|php$s|awk$s) ]]; then
  block "a shell write to human-gate.md (written only by the human)"
fi

# Shell writes to the gold-set labels datasets/gold/** (human only, docs/agents.md
# section 1). Only a write whose target resolves under datasets/gold/ is blocked; a read
# (cat, head, wc, a redirection elsewhere) and any other datasets/ path stay allowed.
# Each write target (a redirection, tee, the operands of rm, cp, mv, touch, sed -i and
# similar, find with -delete or -exec, xargs feeding a writer) is resolved against the
# repository root: the payload cwd, then every cd or pushd in the command, ./ and ..
# segments, {a,b} braces, globs (datasets/*/x, */gold/x, datasets/g*), ~, absolute paths,
# and a leading $VAR/ or $(...)/ read as the repository root. Inline interpreter code
# (python -c, awk, sed scripts, interpreter heredocs) that names datasets/gold is
# blocked; sh -c, eval, $(...) and shell heredocs are checked recursively. The value of a
# git --output option (and -o of archive and format-patch) is a write target (round 3).
# Not matched by design (human decision, repair round 2): a bare datasets token and a
# glob directly under datasets/, such as datasets/* or datasets/*.tmp.
# Fails closed: if the parse fails and the command names datasets/gold with a write shape,
# it is blocked.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." 2>/dev/null && pwd)
goldv=$(printf '%s' "$payload" | python3 -c '
import fnmatch, json, os, posixpath, re, shlex, sys
top = posixpath.normpath(sys.argv[1])
roots = {top, posixpath.normpath(os.path.realpath(top))}
d = json.load(sys.stdin)
ti = d.get("tool_input", {}) or {}
c = ti.get("command") or ti.get("cmd") or ti.get("cli_command") or ""
c = " ".join(map(str, c)) if isinstance(c, list) else c
home = os.environ.get("HOME") or "/nonexistent"
SUB = "@@SUB@@"
GOLDTXT = re.compile(r"datasets/+(?:\./+)*gold(?![A-Za-z0-9_.-])")
SHELLS = {"sh", "bash", "zsh", "dash", "ksh"}
INTERP = {"python", "python2", "python3", "perl", "ruby", "node", "php", "lua", "awk",
          "gawk", "mawk", "nawk", "sed", "Rscript", "jq"}
WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout", "stdbuf",
        "then", "do", "else", "elif", "if", "while", "until", "!", "{", "}", "sudo", "doas"}
EVERY = {"rm", "rmdir", "unlink", "touch", "truncate", "mkdir", "chmod", "chown", "chgrp",
         "shred", "mv", "cp", "install", "ln", "rsync", "scp", "tee", "patch", "split",
         "csplit", "mkfifo", "mknod", "setfacl", "chattr", "sponge", "gzip", "gunzip",
         "bzip2", "bunzip2", "xz", "unxz", "zstd", "unzstd", "vi", "vim", "nvim", "nano",
         "emacs", "ed", "ex", "dd", "curl", "wget", "tar", "unzip"}
CWD_WRITERS = {"curl", "wget", "tar", "unzip"}   # may write into the working directory
FIND_WRITE = {"-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
              "-fprintf", "-fls"}
GIT_WRITE = {"rm", "mv", "checkout", "restore", "apply", "clean", "reset", "stash", "am",
             "cherry-pick", "revert", "merge", "pull", "switch"}
XARGS_VAL = {"-I", "-L", "-n", "-P", "-s", "-d", "-E", "-a", "--arg-file", "--delimiter",
             "--max-args", "--max-procs", "--max-lines", "--max-chars"}
SEPS = {"&&", "||", "|", "|&", ";", ";;", "&", "(", ")", "`", "\n"}
WOPS = {">", ">>", ">|", "&>", "&>>", "<>", ">&"}
syntax = re.compile(r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 |
    (?P<op>&>>|&>|>>|>\||>&|<<<|<<-|<<|<>|<&|&&|\|\||\|&|;;|[;&|()<>`\n])""", re.S | re.X)
HEREDOC = re.compile(r"(?<!<)<<-?\s*[\x27\"]?([A-Za-z_][A-Za-z0-9_]*)[\x27\"]?")
mentions = [bool(GOLDTXT.search(c))]

def heredocs(s):
    lines, kept, bodies, i = s.split("\n"), [], [], 0
    while i < len(lines):
        line = lines[i]; kept.append(line); i += 1
        for dl in HEREDOC.findall(line):
            body = []
            while i < len(lines) and lines[i].strip() != dl:
                body.append(lines[i]); i += 1
            i += 1
            bodies.append((line[:line.find("<<")], "\n".join(body)))
    return "\n".join(kept), bodies

def subst(s):
    # Replace $(...), backticks, ${...} and $NAME by SUB; return the inner commands.
    out, inner, i, q = [], [], 0, ""
    while i < len(s):
        ch = s[i]
        if q == "\x27":
            out.append(ch); i += 1
            if ch == "\x27": q = ""
            continue
        if ch == "\\" and i + 1 < len(s):
            out.append(s[i:i + 2]); i += 2; continue
        if ch == "\x27" and not q:
            q = "\x27"; out.append(ch); i += 1; continue
        if ch == "\"":
            q = "" if q else "\""; out.append(ch); i += 1; continue
        if ch == "$" and s[i + 1:i + 2] == "(":
            depth, j = 1, i + 2
            while j < len(s) and depth:
                depth += {"(": 1, ")": -1}.get(s[j], 0); j += 1
            inner.append(s[i + 2:j - 1]); out.append(SUB); i = j; continue
        if ch == "`":
            j = s.find("`", i + 1); j = len(s) if j < 0 else j
            inner.append(s[i + 1:j]); out.append(SUB); i = j + 1; continue
        if ch == "$" and s[i + 1:i + 2] == "{":
            j = s.find("}", i); j = len(s) if j < 0 else j
            out.append(SUB); i = j + 1; continue
        m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*|[0-9@*#?!$-])", s[i:])
        if m:
            out.append(SUB); i += len(m.group()); continue
        out.append(ch); i += 1
    return "".join(out), inner

def tokens(s):
    pos = 0
    for m in syntax.finditer(s):
        if m.lastgroup == "op":
            for w in shlex.split(s[pos:m.start()]): yield False, w
            yield True, m.group(); pos = m.end()
    for w in shlex.split(s[pos:]): yield False, w

def segments(s):
    segs, words, reds, it, i = [], [], [], list(tokens(s)), 0
    while i < len(it):
        op, t = it[i]
        if op and (t in SEPS or (t not in WOPS and not t.startswith("<"))):
            segs.append((words, reds)); words, reds = [], []
        elif op:
            if i + 1 < len(it) and not it[i + 1][0]:
                i += 1
                nxt = it[i][1]
                if t in WOPS and not (t == ">&" and re.fullmatch(r"\d+-?|-", nxt)):
                    reds.append(nxt)
        else:
            words.append(t)
        i += 1
    segs.append((words, reds))
    return [x for x in segs if x[0] or x[1]]

def braces(w, n=0):
    m = re.search(r"\{([^{}]*,[^{}]*)\}", w)
    if not m or n > 6: return [w]
    out = []
    for alt in m.group(1).split(","):
        out += braces(w[:m.start()] + alt + w[m.end():], n + 1)
    return out[:256]

def can(comp, name):
    return comp == name or (re.search(r"[*?\[]", comp) is not None and fnmatch.fnmatchcase(name, comp))

def specific(comp):
    return re.sub(r"\[[^\]]*\]|[*?]", "", comp) != ""

def gold_parts(p):
    if len(p) < 2 or not (can(p[0], "datasets") and can(p[1], "gold")): return False
    if len(p) == 2: return specific(p[1])        # datasets/gold itself; never datasets/*
    return specific(p[0]) or specific(p[1])

def under_gold(word, cwd):
    for w in braces(word):
        if w == "~" or w.startswith("~/"): w = home + w[1:]
        cands = []
        if w.startswith(SUB):
            rest = w[len(SUB):].lstrip("/").replace(SUB, "*")
            cands += [posixpath.join(r, rest) for r in roots]
            if cwd: cands.append(posixpath.join(cwd, rest))
        else:
            w = w.replace(SUB, "*")
            if w.startswith("/"): cands.append(w)
            elif cwd is None:
                cands += [posixpath.join(r, x, w) for r in roots for x in ("", "datasets")]
            else: cands.append(posixpath.join(cwd, w))
        for p in cands:
            p = posixpath.normpath(p)
            for r in roots:
                if p.startswith(r + "/") and gold_parts(p[len(r) + 1:].split("/")): return True
    return False

def chdir(args, cwd):
    a = [x for x in args if not re.fullmatch(r"-[LPe@]+|--", x)]
    if not a: return home
    t = a[0]
    if t == "-": return None
    if t == "~" or t.startswith("~/"): t = home + t[1:]
    if t.startswith(SUB):
        rest = t[len(SUB):]
        return posixpath.normpath(posixpath.join(top, rest.lstrip("/"))) if rest.startswith("/") and SUB not in rest else None
    if SUB in t: return None
    if t.startswith("/"): return posixpath.normpath(t)
    return None if cwd is None else posixpath.normpath(posixpath.join(cwd, t))

def every(args):
    out = []
    for a in args:
        if a.startswith("-"):
            if "=" in a: out.append(a.split("=", 1)[1])
            elif len(a) > 2 and not a.startswith("--"): out.append(a[2:])
        else:
            out.append(a)
            if "=" in a: out.append(a.split("=", 1)[1])
    return out

def strip(words):
    xa = False
    while words:
        w = words[0]
        if w == "xargs":
            xa, words = True, words[1:]
            while words and words[0].startswith("-"):
                o, words = words[0], words[1:]
                if o in XARGS_VAL and words: words = words[1:]
            continue
        if w in WRAP or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*|-\S*|\d+[smhd]?", w):
            words = words[1:]; continue
        break
    return words, xa

def analyze(words, reds, st, depth):
    cwd = st["cwd"]
    for r in reds:
        if under_gold(r, cwd): return "a redirection into " + r
    words, xa = strip(words)
    if not words: return None
    name, args = posixpath.basename(words[0]), words[1:]
    if name == "eval": return run(" ".join(args), st, depth + 1)
    if name in SHELLS:
        for j, a in enumerate(args):
            if re.fullmatch(r"-[a-zA-Z]*c[a-zA-Z]*", a) and j + 1 < len(args):
                return run(args[j + 1], st, depth + 1)
        return None
    if name in ("cd", "pushd"):
        st["cwd"] = chdir(args, cwd); return None
    if name == "popd":
        st["cwd"] = None; return None
    if name in INTERP:
        for a in args:
            if GOLDTXT.search(a) and not re.fullmatch(r"[A-Za-z0-9_./*?\[\]{},@=+:-]+", a):
                return name + " inline code that names datasets/gold"
    targets, tcwd = [], cwd
    inplace = any(re.fullmatch(r"-[A-Za-z]*i\S*|--in-place\S*", a) for a in args)
    if name in EVERY or (name in ("sed", "perl") and inplace):
        targets = every(args)
        if name in CWD_WRITERS: targets = [t for t in targets if "://" not in t] + ["x"]
    elif name == "find" and any(a in FIND_WRITE for a in args):
        targets = every(args)
    elif name == "sort":
        for j, a in enumerate(args):
            if a in ("-o", "--output") and j + 1 < len(args): targets.append(args[j + 1])
            elif a.startswith("--output="): targets.append(a.split("=", 1)[1])
            elif a.startswith("-o") and len(a) > 2: targets.append(a[2:])
    elif name == "uniq":
        pos = [a for a in args if not a.startswith("-")]
        targets = pos[1:2]
    elif name == "git":
        i = 0
        while i < len(args) and args[i].startswith("-"):
            if args[i] == "-C" and i + 1 < len(args):
                tcwd = chdir([args[i + 1]], tcwd); i += 2
            elif args[i] in ("-c", "--git-dir", "--work-tree", "--namespace"): i += 2
            else: i += 1
        if i < len(args) and args[i] in GIT_WRITE: targets = every(args[i + 1:])
        # --output (diff, log, show and every other subcommand; repair round 3) and the
        # -o and --output-directory of archive and format-patch name a file git writes.
        sub, rest = (args[i], args[i + 1:]) if i < len(args) else ("", [])
        for j, a in enumerate(rest):
            n = a.split("=", 1)[0]
            out = n.startswith("--output") or (len(n) >= 6 and "--output".startswith(n))
            if sub in ("archive", "format-patch") and re.fullmatch(r"-o.*", a):
                targets.append(a[2:] if len(a) > 2 else (rest[j + 1] if j + 1 < len(rest) else ""))
            elif out:
                targets.append(a.split("=", 1)[1] if "=" in a else (rest[j + 1] if j + 1 < len(rest) else ""))
    if xa and targets is not None and (name in EVERY or name in ("sed", "perl", "find")) and mentions[0]:
        return "xargs " + name + " with a datasets/gold path"
    for t in targets:
        if under_gold(t, tcwd): return name + " on " + t
    return None

def run(s, st, depth):
    if depth > 4: return None
    s = re.sub(r"\\\r?\n", " ", s)
    s, bodies = heredocs(s)
    s, inner = subst(s)
    for x in inner:
        r = run(x, dict(st), depth + 1)
        if r: return r
    for words, reds in segments(s):
        if any(under_gold(w, st["cwd"]) for w in words + reds): mentions[0] = True
        r = analyze(words, reds, st, depth)
        if r: return r
    for prefix, body in bodies:
        w = []
        try: w = shlex.split(re.split(r"[;&|()\n]", prefix)[-1])
        except ValueError: pass
        w, _ = strip(w)
        name = posixpath.basename(w[0]) if w else ""
        if name in SHELLS:
            r = run(body, st, depth + 1)
            if r: return r
        elif name in INTERP and GOLDTXT.search(body):
            return name + " heredoc code that names datasets/gold"
    return None

try:
    start = posixpath.normpath(d.get("cwd") or top)
    r = run(c, {"cwd": start}, 0)
except Exception:
    print("UNPARSED"); sys.exit(0)
print(("BLOCK " + r.replace(SUB, "$")) if r else "OK")
' "$root" 2>/dev/null || true)
case "$goldv" in
  OK) ;;
  "BLOCK "*) block "a shell write to the gold-set labels under datasets/gold/: ${goldv#BLOCK } (written only by the human)" ;;
  *) [[ "$cmd" =~ datasets/+(\./+)*gold ]] \
       && [[ "$cmd" =~ ([>]|tee|sed$s+-i|perl$s+-i|mv$s|cp$s|rm$s|truncate|dd$s|install$s|ln$s|touch$s|chmod$s|--output) ]] \
       && block "a shell write to the gold-set labels under datasets/gold/ that the guard could not parse (written only by the human)" ;;
esac

# Shell writes to the test marker .claude/.last-test-run. Only the sbt markTestRun task
# writes it, inside the sbt JVM at the end of 'sbt check', so no agent command may.
# - a redirection (> >> >| &>) into the marker is blocked;
# - running markTestRun by name through sbt is blocked ('sbt check' stays allowed);
# - a command that names the marker (or a glob directly under .claude/) may only use
#   read-only commands in every segment: cat, stat, test, ls and similar; touch, cp, mv,
#   tee, ln, install, dd, truncate, rm, interpreters and editors are blocked, and so is a
#   git read subcommand with --output (repair round 3).
# Heredoc bodies are checked too, because a heredoc can feed an interpreter.
# Fails closed: if the parse fails and the command names the marker, it is blocked.
marker=$(printf '%s' "$cmd" | python3 -c '
import os, re, sys
c = re.sub(r"\\\r?\n", " ", sys.stdin.read())
MARK = r"(?:\.last-test-run|\.claude/\.?[^\s/]*[*?\[])"
WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout", "stdbuf",
        "xargs", "then", "do", "else", "elif", "if", "while", "until", "!", "{", "}",
        "sudo", "doas", "fi", "done", "esac"}
READ = {"cat", "head", "tail", "less", "more", "stat", "ls", "test", "[", "[[", "wc",
        "file", "grep", "egrep", "fgrep", "rg", "diff", "cmp", "md5sum", "sha1sum",
        "sha256sum", "echo", "printf", "readlink", "realpath", "du", "date", "true",
        "false", "basename", "dirname", "cd", "pushd", "popd", "for", "case", "find",
        "git"}
GIT_READ = {"status", "diff", "log", "show", "ls-files", "check-ignore", "rev-parse",
            "blame", "grep"}
FIND_WRITE = {"-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0",
              "-fprintf", "-fls"}
segs = []
for seg in re.split(r"[;&|()`\n]|\$\(", c):
    w = [x.strip("\"\x27\\") for x in seg.split()]
    while w and (w[0] in WRAP or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*", w[0])
                 or re.fullmatch(r"-\S*|\d+[smhd]?", w[0])):
        w.pop(0)
    if w:
        segs.append((os.path.basename(w[0]), w[1:]))
if re.search(r"\bmarkTestRun\b", c, re.I) and any(n in ("sbt", "sbtn") for n, _ in segs):
    print("BLOCK running the markTestRun task by name; run sbt check"); sys.exit()
if not re.search(MARK, c):
    print("OK"); sys.exit()
if re.search(r">[>|]?\s*[\"\x27]?[^\s;&|<>\"\x27]*" + MARK, c):
    print("BLOCK a redirection into the test marker"); sys.exit()
for name, args in segs:
    ok = name in READ
    if name == "git":
        i = 0
        while i < len(args) and args[i].startswith("-"):
            i += 2 if args[i] in ("-C", "-c") else 1
        ok = i < len(args) and args[i] in GIT_READ
        # git diff, log and show write a file with --output (repair round 3).
        if any(a.split("=", 1)[0].startswith("--output") or
               (len(a.split("=", 1)[0]) >= 6 and "--output".startswith(a.split("=", 1)[0]))
               for a in args[i:]):
            ok = False
    if name == "find":
        ok = not any(a in FIND_WRITE for a in args)
    if not ok:
        print(f"BLOCK {name} in a command that names the test marker"); sys.exit()
print("OK")
' 2>/dev/null || true)
case "$marker" in
  OK) ;;
  "BLOCK "*) block "${marker#BLOCK } (.claude/.last-test-run is written only by sbt check)" ;;
  *) [[ "$cmd" == *last-test-run* || "$cmd" == *markTestRun* ]] \
       && block "a command naming the test marker that the guard could not parse" ;;
esac

# Secret-file reads through the shell: .env and .envrc files, *.tfvars, secrets/, keys,
# SSH, AWS SSO and CLI caches, credentials, harness and gh tokens, state.
# The text scanned is the command with grep and rg search patterns removed, because a
# pattern such as grep '.env' docs/x.md names no file; every file operand, -f pattern
# file, redirection and other word stays. If that scrub fails, the raw command is scanned.
# A file operand that is a secret is a read, whatever the flags (human, repair round 3):
# -f, --file and -e, --regexp (and the GNU abbreviations --fil, --reg, --rege, --regex)
# supply the patterns, so then no file operand is taken for a pattern.
# Glob and brace words (repair round 3): an unquoted word with * ? [ ] or {a,b} is
# matched against the secret names. A glob component aimed at a secret name (it can match
# the name with at least two of its literal characters on the name's distinctive part,
# and its * and ? stay inside one dot-separated piece: .e* .en? cred* .a* prod.tfv*) is
# blocked whether or not the file exists. Any other glob (*, *.json, x.*) is expanded
# against the file system from the payload cwd (bounded listing; HOME for ~), and a real
# secret match is blocked. {a,b} words are expanded and scanned. Quoted glob characters
# are literal, and a heredoc body is skipped unless it feeds a shell, because the shell
# expands neither. The glob analysis stops after 3 seconds (the hook timeout is 10).
# Exemption (human decision, repair round 2): the value of -var-file=<name>.tfvars or
# -var-file <name>.tfvars given to terraform plan, validate or console, or to
# scripts/plan.sh, is terraform input, not a shell read. Only that value is rewritten so
# the tfvars rule skips it (every other secret rule still sees it); any other tfvars word
# in the command, a tfvars read in another segment or inside $(...), and -var-file on any
# other subcommand (apply, destroy, import) are still blocked.
pcwd=$(printf '%s' "$payload" | python3 -c '
import json, sys
print(json.load(sys.stdin).get("cwd") or "")
' 2>/dev/null || true)
scan_out=$(printf '%s' "$cmd" | python3 -c '
import fnmatch, functools, itertools, os, posixpath, re, shlex, sys, time
raw = re.sub(r"\\\r?\n", " ", sys.stdin.read())
cwd = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else "."
home = os.environ.get("HOME") or "/nonexistent"
# Quoted or escaped glob and brace characters become sentinels, so a word still holding
# a real * ? [ { , } after shlex had it unquoted; the sentinels are restored for output.
Q = {"*": "\x01", "?": "\x02", "[": "\x03", "{": "\x04", "}": "\x05", ",": "\x06"}
UNQ = {v: k for k, v in Q.items()}
def protect(s):
    out, i, q = [], 0, ""
    while i < len(s):
        ch = s[i]
        if q == "\x27":
            if ch == "\x27": q = ""
            out.append(Q.get(ch, ch)); i += 1; continue
        if ch == "\\" and i + 1 < len(s):
            out.append(ch + Q.get(s[i + 1], s[i + 1])); i += 2; continue
        if q == "\"":
            if ch == "\"": q = ""
            out.append(Q.get(ch, ch)); i += 1; continue
        if ch in "\x27\"": q = ch
        out.append(ch); i += 1
    return "".join(out)
def restore(s):
    return "".join(UNQ.get(c, c) for c in s)
raw = protect(raw)
syntax = re.compile(r"""\\. | "(?:\\.|[^"\\])*" | \x27[^\x27]*\x27 | (?P<op>[;&|()<>`\n]+)""",
                    re.S | re.X)
toks, start = [], 0
for m in syntax.finditer(raw):
    if m.lastgroup == "op":
        toks += [(False, w) for w in shlex.split(raw[start:m.start()])]
        toks.append((True, m.group())); start = m.end()
toks += [(False, w) for w in shlex.split(raw[start:])]
GREP = {"grep", "egrep", "fgrep", "zgrep"}
RG = {"rg"}
# Options whose value is a separate word (a wrong entry here could hide a file operand,
# so only options known to take a value are listed). e and --regexp carry a pattern;
# f and --file name a pattern file, which stays scanned.
GREP_SHORT, GREP_LONG = set("efmABCdD"), {"--regexp", "--file", "--max-count",
    "--after-context", "--before-context", "--context", "--directories", "--devices",
    "--label", "--include", "--exclude", "--exclude-from", "--exclude-dir",
    "--group-separator", "--binary-files"}
RG_SHORT, RG_LONG = set("efgtTmABCjMrEd"), {"--regexp", "--file", "--glob", "--iglob",
    "--type", "--type-not", "--max-count", "--after-context", "--before-context",
    "--context", "--threads", "--max-columns", "--replace", "--encoding", "--max-depth",
    "--max-filesize", "--path-separator", "--sort", "--sortr", "--type-add",
    "--type-clear", "--pre", "--pre-glob", "--colors", "--color"}
WRAP = {"command", "builtin", "exec", "env", "nohup", "time", "nice", "timeout",
        "stdbuf", "xargs", "sudo", "doas", "!", "then", "do", "else", "if", "while",
        "until", "{"}
SHELLS = {"sh", "bash", "zsh", "dash", "ksh"}
TFVARS = r"([A-Za-z0-9_./-]*[A-Za-z0-9_-])\.tfvars"
def varfile(seg, i):
    # Rewrite each plain -var-file tfvars value from index i on; nothing else changes.
    repl = {}
    while i < len(seg):
        op, w = seg[i]
        if op:                                   # skip a redirection and its target
            i += 2 if ("<" in w or ">" in w) else 1; continue
        m = re.fullmatch(r"(--?var-file=)" + TFVARS, w)
        if m:
            repl[i] = m.group(1) + m.group(2) + ".var-file-arg"
        elif w in ("-var-file", "--var-file") and i + 1 < len(seg) and not seg[i + 1][0]:
            m = re.fullmatch(TFVARS, seg[i + 1][1])
            if m:
                repl[i + 1] = m.group(1) + ".var-file-arg"; i += 1
        i += 1
    return repl
def plan_sh(w):
    return w == "scripts/plan.sh" or w.endswith("/scripts/plan.sh")
def scrub(seg):
    words = [i for i, (op, _) in enumerate(seg) if not op]
    k = 0
    while k < len(words) and (seg[words[k]][1] in WRAP or
            re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=.*|-\S*|\d+[smhd]?", seg[words[k]][1])):
        k += 1
    if k >= len(words): return set(), {}
    name = os.path.basename(seg[words[k]][1])
    if name == "terraform":
        j = k + 1
        while j < len(words) and seg[words[j]][1].startswith("-"): j += 1
        if j < len(words) and seg[words[j]][1] in ("plan", "validate", "console"):
            return set(), varfile(seg, words[j] + 1)
        return set(), {}
    if plan_sh(seg[words[k]][1]):
        return set(), varfile(seg, words[k] + 1)
    if name in SHELLS:
        j = k + 1
        while j < len(words) and seg[words[j]][1].startswith("-"): j += 1
        if j < len(words) and plan_sh(seg[words[j]][1]):
            return set(), varfile(seg, words[j] + 1)
        return set(), {}
    if name in GREP: short, long_ = GREP_SHORT, GREP_LONG
    elif name in RG: short, long_ = RG_SHORT, RG_LONG
    else: return set(), {}
    drop, have_e, pos, i, ddash = set(), False, [], words[k] + 1, False
    while i < len(seg):
        op, w = seg[i]
        if op:                                   # a redirection and its target stay
            i += 2 if ("<" in w or ">" in w) else 1; continue
        if not ddash and w == "--": ddash = True
        elif not ddash and w.startswith("--"):
            name_ = w.split("=")[0]
            if len(name_) >= 5 and "--regexp".startswith(name_):
                have_e = True
                if "=" in w: drop.add(i)
                elif i + 1 < len(seg): drop.add(i + 1); i += 1
            elif len(name_) >= 5 and "--file".startswith(name_):
                have_e = True                    # the pattern file itself stays scanned
                if "=" not in w: i += 1
            elif "=" not in w and name_ in long_: i += 1
        elif not ddash and w.startswith("-") and len(w) > 1:
            for j, ch in enumerate(w[1:]):
                if ch in short:
                    attached = j < len(w) - 2
                    if ch in "ef": have_e = True
                    if ch == "e" and not attached and i + 1 < len(seg): drop.add(i + 1)
                    if not attached: i += 1
                    break
        else:
            pos.append(i)
        i += 1
    if not have_e and pos: drop.add(pos[0])
    return drop, {}
out, seg, idx = [], [], []
def flush():
    d, repl = scrub(seg)
    out.extend(repl.get(j, w) for j, (_, w) in enumerate(seg) if j not in d)
for op, w in toks:
    if op and set(w) & set(";&|()`\n") and not set(w) & set("<>"):
        flush(); seg = []; out.append(";")
    else:
        seg.append((op, w))
flush()

# Glob and brace words. SHAPES: a secret name pattern, the representative name that the
# scan regexes below recognize, and the distinctive text that an aimed glob shares.
SHAPES = [("*.env", ".env", ".env"), ("*.env.*", ".env.x", ".env"),
    ("*.envrc", ".envrc", ".envrc"), ("*.pem", "x.pem", ".pem"), ("*.key", "x.key", ".key"),
    ("*.p12", "x.p12", ".p12"), ("*.pfx", "x.pfx", ".pfx"), ("*.tfvars", "x.tfvars", ".tfvars"),
    ("*.tfvars.json", "x.tfvars.json", ".tfvars"), ("*.tfstate", "x.tfstate", ".tfstate"),
    ("*.tfstate.*", "x.tfstate.x", ".tfstate"), ("id_rsa", "id_rsa", "id_rsa"),
    ("id_ed25519", "id_ed25519", "id_ed25519"), ("id_ecdsa", "id_ecdsa", "id_ecdsa"),
    ("credentials.json", "credentials.json", "credentials"),
    (".credentials.json", ".credentials.json", "credentials"), (".netrc", ".netrc", ".netrc"),
    ("secrets", "secrets", "secrets"), (".ssh", ".ssh", ".ssh"), (".aws", ".aws", ".aws"),
    ("credentials", "credentials", "credentials"), ("sso", "sso", "sso"), ("cli", "cli", "cli"),
    ("cache", "cache", "cache"), ("gh", "gh", "gh"), ("hosts.yml", "hosts.yml", "hosts"),
    ("hosts.yaml", "hosts.yaml", "hosts"), (".codex", ".codex", ".codex"),
    ("auth.json", "auth.json", "auth")]
ALPHA = frozenset(chr(x) for x in range(32, 127))
GLOB = re.compile(r"[*?\[]")
def gparse(p, nodot=False):
    # Elements: ("*", chars) for a star, ("s", chars) for one character. With nodot, * and
    # ? never produce a dot, so an aimed glob reaches a name only inside one dot piece.
    el, i, any_ = [], 0, (ALPHA - {"."}) if nodot else ALPHA
    while i < len(p):
        ch = p[i]
        if ch == "*":
            if not el or el[-1] != ("*", any_): el.append(("*", any_))
        elif ch == "?": el.append(("s", any_))
        elif ch == "[" and p.find("]", i + 2) > 0:
            j = p.find("]", i + 2)
            body = p[i + 1:j]; neg = body[:1] in ("!", "^")
            body = body[1:] if neg else body
            chars, k = set(), 0
            while k < len(body):
                if k + 2 < len(body) and body[k + 1] == "-":
                    chars |= {chr(x) for x in range(ord(body[k]), ord(body[k + 2]) + 1)}; k += 3
                else:
                    chars.add(UNQ.get(body[k], body[k])); k += 1
            el.append(("s", frozenset(ALPHA - chars) if neg else frozenset(chars))); i = j
        else: el.append(("s", frozenset([UNQ.get(ch, ch)])))
        i += 1
    return tuple(el)
def meet(a, b, marks):
    # True when some name matches both glob element lists a (the command word) and b (a
    # secret shape) with at least two literal characters of a placed on the distinctive
    # part of b (marks), so the glob is aimed at that secret name.
    lit = [x[0] == "s" and len(x[1]) < 10 for x in a]
    @functools.lru_cache(None)
    def go(i, j, c):
        if i == len(a) and j == len(b): return c >= 2
        # A star matches nothing, or takes one character the other side produces; a
        # character both stars would produce can always be left out.
        if i < len(a) and a[i][0] == "*":
            if go(i + 1, j, c): return True
            return j < len(b) and (b[j][0] == "*" or bool(a[i][1] & b[j][1])) and go(i, j + 1, c)
        if j < len(b) and b[j][0] == "*":
            if go(i, j + 1, c): return True
            return i < len(a) and bool(a[i][1] & b[j][1]) and go(i + 1, j, c)
        if i == len(a) or j == len(b): return False
        return bool(a[i][1] & b[j][1]) and go(i + 1, j + 1, min(2, c + (lit[i] and marks[j])))
    return go(0, 0, 0)
def shape(pat, dist, dot):
    # The shell never lets * ? or [ match a leading dot, so for a glob that does not start
    # with a literal dot a dot name is out of reach and a leading * becomes [!.]*.
    el = gparse(pat)
    k = pat.find(dist)
    marks = tuple(k <= x < k + len(dist) for x in range(len(el)))
    if dot: return el, marks
    if el[0] == ("s", frozenset(["."])): return None
    if el[0][0] == "*": return (("s", ALPHA - {"."}),) + el, (False,) + marks
    return el, marks
@functools.lru_cache(None)
def names(comp):
    if not GLOB.search(comp) or len(comp) > 200: return [restore(comp)]
    g = gparse(comp, nodot=True)
    dot = g[0] == ("s", frozenset(["."]))
    hit = []
    for pat, rep, dist in SHAPES:
        sh = shape(pat, dist, dot)
        if sh and meet(g, sh[0], sh[1]) and rep not in hit: hit.append(rep)
    return hit or [restore(comp)]
def braces(w, n=0):
    m = re.search(r"\{([^{}]*,[^{}]*)\}", w)
    if not m or n > 6: return [w]
    res = []
    for alt in m.group(1).split(","):
        res += braces(w[:m.start()] + alt + w[m.end():], n + 1)
    return res[:64]
def fsglob(w):
    # Expand a glob against the file system like the shell, with a bounded listing.
    if w == "~" or w.startswith("~/"): w = home + w[1:]
    w = re.sub(r"^\$\{?HOME\}?(?=/|$)", home, w)
    if not GLOB.search(w): return []
    absl, cur, budget = w.startswith("/"), [""], 5000
    for comp in [c for c in w.split("/") if c]:
        nxt = []
        for d in cur:
            if not GLOB.search(comp):
                nxt.append(posixpath.join(d, restore(comp))); continue
            base = ("/" + d if absl else posixpath.join(cwd, d)) if d else ("/" if absl else cwd)
            if base not in LISTED:
                try: LISTED[base] = sorted(os.listdir(base))
                except OSError: LISTED[base] = []
            entries = LISTED[base]
            pat = "".join("[" + UNQ[c] + "]" if c in "\x01\x02\x03" else UNQ.get(c, c) for c in comp)
            for n in entries:
                budget -= 1
                if budget < 0: break
                if n.startswith(".") and not comp.startswith("."): continue
                if fnmatch.fnmatchcase(n, pat): nxt.append(posixpath.join(d, n))
        cur = nxt[:500]
    return [("/" + x if absl else x) for x in cur]
def shell_words(s):
    # The words outside heredoc bodies, plus the bodies fed to a shell: the shell never
    # expands a glob inside a heredoc body that only feeds cat, python or similar.
    lines, kept, i = s.split("\n"), [], 0
    while i < len(lines):
        line = lines[i]; kept.append(line); i += 1
        for dl in re.findall(r"(?<!<)<<-?\s*[\x27\"]?([A-Za-z_][A-Za-z0-9_]*)[\x27\"]?", line):
            w = re.split(r"[;&|()]", line[:line.find("<<")])[-1].split()
            while w and (w[0] in WRAP or "=" in w[0] or w[0].startswith("-")): w = w[1:]
            feeds_shell = bool(w) and os.path.basename(w[0]) in SHELLS
            while i < len(lines) and lines[i].strip() != dl:
                if feeds_shell: kept.append(lines[i])
                i += 1
            i += 1
    words, kraw, st = set(), "\n".join(kept), 0
    for m in syntax.finditer(kraw):
        if m.lastgroup == "op":
            words.update(shlex.split(kraw[st:m.start()])); st = m.end()
    words.update(shlex.split(kraw[st:]))
    return words
try: kwords = shell_words(raw)
except ValueError: kwords = None
LISTED, records, deadline = {}, [], time.time() + 3
for w in out:
    real = GLOB.search(w) or re.search(r"\{[^{}]*,[^{}]*\}", w)
    if not real or (kwords is not None and w not in kwords): continue
    if time.time() > deadline: break
    cands = []
    for e in braces(w):
        if not GLOB.search(e):
            if e != w: cands.append(restore(e))
            continue
        combos = itertools.islice(itertools.product(*[names(c) for c in e.split("/")]), 256)
        cands += ["/".join(x) for x in combos]
        cands += fsglob(e)
    if cands:
        records.append(" ".join(cands[:2000]) + "\x1f" + restore(w))
sys.stdout.write(restore(" ".join(out)) + "\x1d" + "".join(r + "\x1e" for r in records))
' "${pcwd:-$root}" 2>/dev/null || true)
# Split on the \x1d separator with read (bash pattern operators are slow on long text).
scan="" globs=""
{ IFS= read -r -d $'\x1d' scan; IFS= read -r -d '' globs; } <<< "$scan_out"
[ -n "$scan" ] || scan=$cmd
b='[[:space:]/"'"'"'=]'          # characters that may precede a path
e='([[:space:]"'"'"';|&)]|$)'      # characters that may follow a file name
secret_hit() {
  local t=$1
  hit=""
  if [[ "$t" =~ (^|$b)([A-Za-z0-9_.*?-]*\.env(rc)?(\.[A-Za-z0-9_-]+)?)$e ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)([A-Za-z0-9_.*?-]*\.(pem|key|p12|pfx|tfvars|tfvars\.json)|id_rsa|id_ed25519|id_ecdsa)$e ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)(\.env[^[:space:]]*[*?]) ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)(secrets/) ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (^|$b)(\.ssh)(/|$e) ]]; then hit=${BASH_REMATCH[2]}
  elif [[ "$t" =~ (\.aws/credentials|\.aws/sso|\.aws/cli/cache|credentials\.json|gh/hosts\.ya?ml|\.codex/auth\.json|\.netrc|\.tfstate) ]]; then hit=${BASH_REMATCH[1]}
  fi
}
secret_hit "$scan"
if [ -n "$hit" ]; then
  echo "Blocked by guard-dangerous hook: the command touches a secret file ('$hit')." \
       "Agents never read secrets; use the documented environment variable names" \
       "instead, or ask a human." >&2
  exit 2
fi
while IFS=$'\x1f' read -r -d $'\x1e' cands orig; do
  secret_hit "$cands"
  if [ -n "$hit" ]; then
    echo "Blocked by guard-dangerous hook: the glob or brace word '$orig' can match a" \
         "secret file ('$hit'). Agents never read secrets; name the file you need," \
         "or ask a human." >&2
    exit 2
  fi
done <<< "$globs"
exit 0
