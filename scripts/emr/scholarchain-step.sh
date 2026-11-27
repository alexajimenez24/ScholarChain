#!/usr/bin/env bash
# scripts/emr/scholarchain-step.sh: runs ON the EMR cluster, never on a laptop.
# run-bounded-pipeline.sh uploads it to s3://<artifacts>/pipeline/<sha>/emr/; the state
# machine (infra/stacks/compute/pipeline.tf) runs it as the bootstrap action "install"
# and then as command-runner.jar steps. Arguments arrive as separate words (no shell
# string is built from execution input), and each is checked against a strict pattern.
#
#   install
#       copy this script to /home/hadoop/scholarchain-step.sh (bootstrap action).
#   spark <spark jar s3://...> <hadoop out s3a://...> <er out s3a://...> <spark out s3a://...> <checkpoint dir s3a://...>
#       find the one Hadoop and the one ER run manifest, then spark-submit SparkPipeline
#       (exit 3 = outputs written but an invariant failed: the step fails, no load runs).
#   pipeline-cli <loaders jar s3://...> <command> <spark out s3a://...> <limit> [NAME=VALUE ...]
#       run the storage PipelineCli (Phase 13, step-02 report) on this node:
#         neptune-load        limit = Neptune wait seconds (--wait-seconds)
#         load-dynamo         limit = "-"
#         enqueue-embeddings  limit = "all" (--all) or N (--max-units-per-type N)
#       NAME=VALUE words set only the allow-listed configuration names below, each
#       checked against its own pattern. The CLI's own exit code is the step's exit code
#       (0 ok, 1 failed, 2 arguments or configuration, 3 rejected records, 4 load not
#       finished in time).
#
# Credentials for pipeline-cli (Phase 13 MINOR-3): the storage CLI reads AWS credentials
# only from the environment or a container credentials endpoint, not from the EC2
# instance profile (step-02 report; core EnvironmentCredentials). A single exported
# snapshot could expire mid-run, so this helper starts a container-credentials endpoint
# on loopback (127.0.0.1, an ephemeral port) for the CLI's run only. Each request must
# carry a random bearer token, generated inside the endpoint and handed to the CLI alone
# as AWS_CONTAINER_AUTHORIZATION_TOKEN (never on a command line), and is answered with
# the CURRENT instance profile credentials read from IMDSv2 (the node enforces IMDSv2,
# hop limit 1). The CLI caches them and asks again 5 minutes before they expire, and EC2
# rotates them at least 5 minutes before expiry, so they refresh themselves. Nothing is
# written to a file; the endpoint logs nothing, stops when this helper exits (its stdin
# closes, the EXIT trap kills it) and after a hard lifetime. No xtrace; the ERR trap
# prints the command text, which never holds a value.
#
# Account ID (Phase 13 MINOR-1): the output of every AWS-facing command here (aws, hadoop,
# spark-submit, the CLI) goes to the EMR step logs, so stdout and stderr both pass
# through redact(): ARN account fields, ECR hosts, SQS queue URLs and any bare 12-digit
# number become <account-id>. The commands' exit codes are kept (pipefail).

set -Eeuo pipefail
IFS=$'\n\t'
trap 'printf "==> [emr-step] FAILED line=%s exit=%s command: %s\n" "$LINENO" "$?" "$BASH_COMMAND" >&2' ERR

say() { printf '==> [emr-step] %s\n' "$*"; }
die() {
  printf '==> [emr-step] FAILED: %s\n' "$*" >&2
  exit 1
}

uri_ok() { [[ "$1" =~ ^s3a?://[a-z0-9][a-z0-9.-]{1,62}/[A-Za-z0-9=._/-]+$ ]]; }

redact() {
  sed -u -E \
    -e 's/(arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:)[0-9]{12}(:|\/)/\1<account-id>\2/g' \
    -e 's/[0-9]{12}(\.dkr\.ecr\.)/<account-id>\1/g' \
    -e 's/(amazonaws\.com\/)[0-9]{12}(\/|$)/\1<account-id>\2/g' \
    -e 's/(^|[^0-9A-Za-z])[0-9]{12}($|[^0-9A-Za-z])/\1<account-id>\2/g' \
    -e 's/(^|[^0-9A-Za-z])[0-9]{12}($|[^0-9A-Za-z])/\1<account-id>\2/g'
}

# err_redacted CMD...: CMD's stdout untouched (parsed by the caller), its stderr redacted.
err_redacted() {
  local rc=0
  { "$@" 2>&1 1>&3 3>&- | redact >&2 3>&-; } 3>&1 || rc=$?
  return "$rc"
}

# redacted CMD...: CMD's stdout and stderr both redacted, each on its own stream.
redacted() {
  local rc=0
  err_redacted "$@" | redact || rc=$?
  return "$rc"
}

one_manifest() {
  local dir="$1" found=() f
  mapfile -t found < <(err_redacted timeout 300s hadoop fs -ls -C "$dir/run-manifests/" | grep -E '/[^/]+\.json$' | grep -v -E -- '-summary\.json$' || true)
  [[ "${#found[@]}" -eq 1 ]] || die "expected exactly one run manifest under $dir/run-manifests/, found ${#found[@]}"
  f="${found[0]}"
  printf '%s' "$f"
}

# Allow list of configuration names for pipeline-cli and the pattern each value must match.
declare -A CONF_PATTERN=(
  [AWS_REGION]='^us-east-2$'
  [CURATED_BUCKET]='^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$'
  [GRAPH_BUCKET]='^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$'
  [DDB_RUN_LEDGER]='^[A-Za-z0-9_.-]{3,255}$'
  [DDB_RESEARCHER_METRIC]='^[A-Za-z0-9_.-]{3,255}$'
  [NEPTUNE_ENDPOINT]='^[a-z0-9.-]+\.neptune\.amazonaws\.com$'
  [NEPTUNE_PORT]='^[0-9]{2,5}$'
  [NEPTUNE_LOADER_ROLE_ARN]='^arn:aws:iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+$'
  [EMBEDDING_QUEUE_URL]='^https://sqs\.us-east-2\.amazonaws\.com/[0-9]{12}/[A-Za-z0-9_-]{1,80}$'
  [EMBEDDING_DIMENSION]='^[0-9]{1,5}$'
)

# The loopback container-credentials endpoint (see the header). argv: hard lifetime in
# seconds. It checks the instance profile once, then prints one line on stdout,
# "<port> <token> <expiration>", and serves GET /credentials until its stdin closes (the
# helper exited) or the lifetime ends. It never logs a request or a value.
# SC_IMDS_BASE exists only so the offline tests can point it at a loopback stub.
CRED_ENDPOINT_PY='import hmac, http.server, json, os, re, secrets, sys, threading, urllib.request
IMDS = os.environ.get("SC_IMDS_BASE", "http://169.254.169.254/latest")
TOKEN = secrets.token_urlsafe(32)
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def imds(path, method="GET", headers=None):
    req = urllib.request.Request(IMDS + path, method=method, headers=headers or {})
    with OPENER.open(req, timeout=5) as r:
        return r.read().decode("utf-8")
def current():
    t = imds("/api/token", "PUT", {"X-aws-ec2-metadata-token-ttl-seconds": "300"})
    h = {"X-aws-ec2-metadata-token": t}
    role = imds("/meta-data/iam/security-credentials/", headers=h).strip().splitlines()[0]
    if not re.fullmatch(r"[A-Za-z0-9+=,.@_-]+", role):
        raise ValueError("role")
    d = json.loads(imds("/meta-data/iam/security-credentials/" + role, headers=h))
    if d.get("Code") != "Success":
        raise ValueError("code")
    return {k: d[k] for k in ("AccessKeyId", "SecretAccessKey", "Token", "Expiration")}
class Handler(http.server.BaseHTTPRequestHandler):
    timeout = 10
    def log_message(self, *args):
        pass
    def reply(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def do_GET(self):
        auth = self.headers.get("Authorization", "")
        if self.path != "/credentials" or not hmac.compare_digest(auth.encode(), TOKEN.encode()):
            return self.reply(403)
        try:
            body = json.dumps(current()).encode("utf-8")
        except Exception:
            return self.reply(502)
        self.reply(200, body)
try:
    expires = current()["Expiration"]
except Exception:
    sys.exit(3)
server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
server.handle_error = lambda request, address: None
def stop():
    server.shutdown()
def stop_on_eof():
    sys.stdin.read()
    stop()
threading.Thread(target=stop_on_eof, daemon=True).start()
timer = threading.Timer(int(sys.argv[1]), stop)
timer.daemon = True
timer.start()
print(server.server_address[1], TOKEN, re.sub(r"[^0-9A-Za-z:.-]", "", str(expires)), flush=True)
server.serve_forever(poll_interval=1)
server.server_close()'

CRED_PID="" CRED_IN="" CRED_PORT="" CRED_TOKEN=""
# Starts the endpoint for a CLI run bounded by $1 seconds (lifetime: $1 + 120).
start_credential_endpoint() {
  local out="" line="" expires=""
  coproc CREDSRV { exec python3 -I -c "$CRED_ENDPOINT_PY" "$(($1 + 120))"; }
  CRED_PID="${CREDSRV_PID:-}"
  out="${CREDSRV[0]:-}"
  CRED_IN="${CREDSRV[1]:-}"
  [[ -n "$CRED_PID" && -n "$out" ]] || die "the loopback credential endpoint did not start"
  IFS= read -r -t 30 line <&"$out" || die "the loopback credential endpoint did not start (no instance profile credentials from IMDSv2?)"
  CRED_PORT="${line%% *}"
  line="${line#* }"
  CRED_TOKEN="${line%% *}"
  expires="${line#* }"
  unset line
  [[ "$CRED_PORT" =~ ^[0-9]{2,5}$ && "$CRED_TOKEN" =~ ^[A-Za-z0-9_-]{40,}$ ]] || die "the loopback credential endpoint answered an unexpected start line"
  say "loopback credential endpoint on 127.0.0.1:$CRED_PORT (instance profile via IMDSv2, refreshed on demand; current credentials expire $expires)"
}

stop_credential_endpoint() {
  # Closing our end of its stdin stops the endpoint; the kill below is the backstop.
  if [[ -n "$CRED_IN" ]]; then exec {CRED_IN}>&-; fi
  if [[ -n "$CRED_PID" ]]; then
    kill "$CRED_PID" 2>/dev/null || true
    wait "$CRED_PID" 2>/dev/null || true
  fi
  CRED_PID="" CRED_IN="" CRED_TOKEN=""
}

cmd="${1:-}"
shift || true

case "$cmd" in
  install)
    install -m 0755 "$0" /home/hadoop/scholarchain-step.sh
    say "installed /home/hadoop/scholarchain-step.sh"
    ;;

  spark)
    [[ $# -eq 5 ]] || die "spark needs 5 arguments, got $#"
    for a in "$@"; do uri_ok "$a" || die "invalid S3 URI argument: $a"; done
    jar="${1/#s3:/s3a:}"
    hadoop_manifest="$(one_manifest "$2")"
    er_manifest="$(one_manifest "$3")"
    say "hadoop manifest: $hadoop_manifest"
    say "er manifest: $er_manifest"
    redacted timeout 5400s spark-submit --deploy-mode cluster --class scholarchain.spark.SparkPipeline \
      "$jar" --hadoop-manifest "$hadoop_manifest" --er-manifest "$er_manifest" \
      --output "$4" --checkpoint-dir "$5"
    say "spark pipeline finished"
    ;;

  pipeline-cli)
    [[ $# -ge 4 ]] || die "pipeline-cli needs <jar> <command> <spark out> <limit> [NAME=VALUE ...], got $# arguments"
    jar_uri="$1" command="$2" spark_out="${3/#s3a:/s3:}" limit="$4"
    shift 4
    [[ "$jar_uri" =~ ^s3://[a-z0-9][a-z0-9.-]{1,62}/pipeline/[0-9a-f]{40}/jobs/loaders\.jar$ ]] || die "invalid loaders jar URI: $jar_uri"
    uri_ok "$spark_out" || die "invalid spark output URI: $spark_out"
    flags=()
    # Bound of the CLI process: the Neptune wait plus 15 minutes for writing the load
    # files; one hour for the DynamoDB load and the enqueue (bounded sample).
    max_seconds=3600
    case "$command" in
      neptune-load)
        [[ "$limit" =~ ^[0-9]{1,5}$ ]] || die "neptune-load limit must be the wait in seconds"
        flags=(--wait-seconds "$limit")
        max_seconds=$((10#$limit + 900))
        ;;
      load-dynamo)
        [[ "$limit" == "-" ]] || die "load-dynamo takes the limit -"
        ;;
      enqueue-embeddings)
        if [[ "$limit" == "all" ]]; then
          flags=(--all)
        elif [[ "$limit" =~ ^[1-9][0-9]{0,5}$ ]]; then
          flags=(--max-units-per-type "$limit")
        else
          die "enqueue-embeddings limit must be all or 1 to 999999"
        fi
        ;;
      *) die "unknown pipeline-cli command: $command (neptune-load | load-dynamo | enqueue-embeddings)" ;;
    esac
    for kv in "$@"; do
      name="${kv%%=*}"
      value="${kv#*=}"
      [[ "$kv" == *=* && -n "${CONF_PATTERN[$name]:-}" ]] || die "configuration word not allowed: ${name}"
      [[ "$value" =~ ${CONF_PATTERN[$name]} ]] || die "invalid value for $name"
      export "$name=$value"
    done
    work="$(mktemp -d)"
    trap 'stop_credential_endpoint; rm -rf -- "$work"' EXIT
    redacted timeout 600s aws s3 cp --only-show-errors --region us-east-2 "$jar_uri" "$work/loaders.jar"
    say "loaders jar sha256 $(sha256sum "$work/loaders.jar" | awk '{print $1}')"
    start_credential_endpoint "$max_seconds"
    shown="$(IFS=' ' && printf '%s' "${flags[*]:-}")"
    say "PipelineCli $command --spark-output $spark_out $shown (bounded by ${max_seconds}s)"
    # Static keys would win over the endpoint in EnvironmentCredentials: none may be set.
    unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_CONTAINER_CREDENTIALS_RELATIVE_URI
    rc=0
    AWS_CONTAINER_CREDENTIALS_FULL_URI="http://127.0.0.1:$CRED_PORT/credentials" \
      AWS_CONTAINER_AUTHORIZATION_TOKEN="$CRED_TOKEN" \
      redacted timeout "${max_seconds}s" java -Xmx4g -cp "$work/loaders.jar" scholarchain.storage.PipelineCli \
      "$command" --spark-output "$spark_out" "${flags[@]}" || rc=$?
    stop_credential_endpoint
    [[ "$rc" -eq 0 ]] || die "PipelineCli $command exited $rc (0 ok, 1 failed, 2 arguments or configuration, 3 rejected records, 4 load not finished in time, 124 timeout)"
    say "PipelineCli $command finished"
    ;;

  *)
    die "unknown command: ${cmd:-<none>} (install | spark | pipeline-cli)"
    ;;
esac
