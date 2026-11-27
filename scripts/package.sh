#!/usr/bin/env bash
# scripts/package.sh: build the job jars, the service jars and the three service images,
# and record their checksums and Git-SHA tags. Local only: no AWS call, no push, no
# registry, no upload (deploy.sh pushes; run-bounded-pipeline.sh uploads).
#
# Usage:
#   scripts/package.sh [--jars-only | --images-only]
#
#   default        one sbt run (timeout 45m): packageJobs (hadoop, entityResolution,
#                  sparkAnalytics job jars in target/jobs/) and the query-service,
#                  graph-service and embedding-worker assemblies (target/services/<image>/);
#                  then, with a running Docker daemon, the three local images one at a
#                  time, each its own sbt <project>/dockerLocalBuild under timeout 20m (the
#                  assembly is already up to date, so only the image is built). Without a
#                  Docker daemon the image step is reported as ==> ... NOT-RUN (exit 3).
#   --jars-only    one sbt run: packageJobs and queryService/assembly (the loaders jar the
#                  pipeline runs: storage PipelineCli with the rag renderer provider).
#   --images-only  the three assemblies, then the three images as above (what deploy.sh
#                  needs); fails without Docker.
#
# Every run checks that the query-service assembly holds exactly one provider entry in
# META-INF/services/scholarchain.storage.RendererProvider, naming
# scholarchain.rag.SemanticRendererProvider (step-02b report: --dry-run of the CLI does
# not check the renderer, so the merged jar is checked here).
# Images are tagged scholarchain-<image>:<HEAD> (or <HEAD>-dirty when the tree is dirty,
# so a dirty build never carries a clean commit's tag), next to sbt's :local tag.
# Records (git-ignored): target/package/<tag>/package-manifest.txt (revision, dirty flag,
# every jar's SHA-256 and size, every image's tag and local image ID) and SHA256SUMS.
# Exit codes: 0 built, 1 failed, 2 usage, 3 jars built but images NOT RUN (no Docker).

# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,8p' "$0"; exit 2; }

MODE=all
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jars-only) MODE=jars; shift ;;
    --images-only) MODE=images; shift ;;
    -h | --help) usage ;;
    *) printf '==> [package] FAILED: unknown argument: %s\n' "$1" >&2; usage ;;
  esac
done

IMAGES=(query-service graph-service embedding-worker)
declare -A PROJECT=([query-service]=queryService [graph-service]=graphService [embedding-worker]=embeddingWorker)
PROVIDER_FILE="META-INF/services/scholarchain.storage.RendererProvider"
PROVIDER_CLASS="scholarchain.rag.SemanticRendererProvider"

sc_step preflight "mode=$MODE"
sc_need_tools git sbt java sha256sum python3 timeout find
REV="$(sc_head)"
TAG="$REV"
DIRTY=false
if sc_is_dirty; then
  DIRTY=true
  TAG="$REV-dirty"
  sc_info "the tree is dirty: images are tagged :$TAG and the record says dirty=true"
fi
docker_up=false
if command -v docker >/dev/null 2>&1 && timeout 30s docker info >/dev/null 2>&1; then
  docker_up=true
fi
if [[ "$MODE" == images && "$docker_up" != true ]]; then
  sc_die "--images-only needs the docker CLI and a running daemon"
fi

case "$MODE" in
  jars) TASKS=(packageJobs queryService/assembly) ;;
  images) TASKS=(queryService/assembly graphService/assembly embeddingWorker/assembly) ;;
  *) TASKS=(packageJobs queryService/assembly graphService/assembly embeddingWorker/assembly) ;;
esac

# Space-joined for the markers (common.sh sets IFS to newline and tab, which would split a
# ${TASKS[*]} marker over several lines).
TASKS_SHOWN="$(IFS=' ' && printf '%s' "${TASKS[*]}")"
sc_step build "one sbt run: $TASKS_SHOWN"
(cd "$SC_ROOT" && timeout 45m sbt -batch "${TASKS[@]}")
sc_ok "sbt $TASKS_SHOWN finished"

# One image at a time, each its own bounded sbt run (Phase 13 A2: each image build under
# timeout 20m), so a hung docker build stops at 20 minutes and names its image.
if [[ "$MODE" != jars && "$docker_up" == true ]]; then
  for img in "${IMAGES[@]}"; do
    sc_step "image-$img" "sbt ${PROJECT[$img]}/dockerLocalBuild (timeout 20m): scholarchain-$img:local"
    (cd "$SC_ROOT" && timeout 20m sbt -batch "${PROJECT[$img]}/dockerLocalBuild")
    sc_ok "built scholarchain-$img:local"
  done
fi

# newest PATTERN DIR: the jar matching PATTERN in DIR (sbt-assembly keeps an unchanged
# jar, so the file can predate this run). More than one (an older version left in
# target/) takes the most recently written one and names the others.
newest() {
  local -a m
  mapfile -t m < <(find "$2" -maxdepth 1 -type f -name "$1" -printf '%T@ %p\n' 2>/dev/null | sort -rn | cut -d' ' -f2-)
  [[ "${#m[@]}" -ge 1 ]] || sc_die "no $1 in ${2#"$SC_ROOT"/} after the build"
  if [[ "${#m[@]}" -gt 1 ]]; then
    sc_info "WARNING: ${#m[@]} files match $1 in ${2#"$SC_ROOT"/}; using the newest, ${m[0]##*/}"
  fi
  printf '%s' "${m[0]}"
}

declare -a JARS=()
if [[ "$MODE" != images ]]; then
  JARS+=("hadoop=$(newest 'hadoop-*.jar' "$SC_ROOT/target/jobs")")
  JARS+=("entity-resolution=$(newest 'entityResolution-*.jar' "$SC_ROOT/target/jobs")")
  JARS+=("spark=$(newest 'sparkAnalytics-*.jar' "$SC_ROOT/target/jobs")")
fi
for img in "${IMAGES[@]}"; do
  if [[ "$MODE" == jars && "$img" != query-service ]]; then continue; fi
  JARS+=("$img=$(newest "${PROJECT[$img]}-*.jar" "$SC_ROOT/target/services/$img")")
done

sc_step provider "the query-service assembly holds exactly one RendererProvider entry"
query_jar=""
for j in "${JARS[@]}"; do
  if [[ "${j%%=*}" == query-service ]]; then query_jar="${j#*=}"; fi
done
[[ -n "$query_jar" ]] || sc_die "no query-service assembly from this run"
# Prints every provider line of the service file (comments and blanks dropped), or MISSING.
entries="$(python3 -I -c 'import sys, zipfile
jar, name = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    if name not in z.namelist():
        print("MISSING")
        sys.exit(0)
    text = z.read(name).decode("utf-8")
for line in text.splitlines():
    line = line.split("#", 1)[0].strip()
    if line:
        print(line)' "$query_jar" "$PROVIDER_FILE")"
[[ "$entries" == "$PROVIDER_CLASS" ]] ||
  sc_die "$PROVIDER_FILE in ${query_jar#"$SC_ROOT"/} must hold exactly one entry $PROVIDER_CLASS; found: $(printf '%s' "$entries" | tr '\n' ' ')"
sc_ok "one provider entry: $PROVIDER_CLASS"

OUT="$SC_ROOT/target/package/$TAG"
mkdir -p "$OUT"
REC="$OUT/package-manifest.txt"
{
  printf 'revision=%s\ndirty=%s\nmode=%s\nbuilt=%s\njvm-release=17\n' "$REV" "$DIRTY" "$MODE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$REC"
: >"$OUT/SHA256SUMS"

sc_step checksums "SHA-256 of every jar built by this run"
for j in "${JARS[@]}"; do
  name="${j%%=*}" path="${j#*=}"
  sum="$(sha256sum -- "$path" | awk '{print $1}')"
  size="$(stat -c %s -- "$path")"
  printf 'jar %s sha256=%s bytes=%s path=%s\n' "$name" "$sum" "$size" "${path#"$SC_ROOT"/}" >>"$REC"
  printf '%s  %s\n' "$sum" "${path#"$SC_ROOT"/}" >>"$OUT/SHA256SUMS"
  printf '==> PACKAGE jar %s sha256=%s %s\n' "$name" "$sum" "${path#"$SC_ROOT"/}"
done

rc=0
if [[ "$MODE" == jars ]]; then
  sc_skip "images (--jars-only)"
elif [[ "$docker_up" != true ]]; then
  printf '==> [package] NOT-RUN images: no Docker daemon; the three assemblies were built and checksummed instead\n'
  printf 'images=NOT-RUN (no Docker daemon)\n' >>"$REC"
  rc=3
else
  sc_step images "tag the three local images :$TAG and record their image IDs"
  for img in "${IMAGES[@]}"; do
    timeout 60s docker tag "scholarchain-$img:local" "scholarchain-$img:$TAG"
    id="$(timeout 60s docker image inspect --format '{{.Id}}' "scholarchain-$img:$TAG")"
    [[ "$id" =~ ^sha256:[0-9a-f]{64}$ ]] || sc_die "could not read the image ID of scholarchain-$img:$TAG"
    printf 'image %s tag=scholarchain-%s:%s id=%s\n' "$img" "$img" "$TAG" "$id" >>"$REC"
    printf '==> PACKAGE image %s scholarchain-%s:%s id=%s\n' "$img" "$img" "$TAG" "$id"
  done
fi

printf '==> PACKAGE record %s\n' "${REC#"$SC_ROOT"/}"
if [[ "$rc" -eq 0 ]]; then
  sc_ok "packaged $TAG (mode=$MODE)"
else
  sc_ok "jars packaged at $TAG; images NOT RUN (exit 3)"
fi
exit "$rc"
