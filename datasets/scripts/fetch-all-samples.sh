#!/usr/bin/env bash
# Bounded sample retrieval of the required datasets into datasets/samples/local/<snapshot>/ (git-ignored),
# with checksum verification. There is no upload: S3 writes go only through the infra-engineer and
# deploy-engineer gated path.
# Usage: datasets/scripts/fetch-all-samples.sh [records]
#   records  DBLP records to keep (default 1000; fetch_samples.py refuses above 500000)
# Env: SNAPSHOT (default today, UTC), MAX_DOIS (default 50),
#      WITH_ORCID=1 to also fetch ORCID records (OFF by default: the ORCID public-API terms are a pending
#      human decision, see datasets/dataset-manifest.yaml), ORCID_MAX (default 20, used only with WITH_ORCID=1),
#      CONTACT_EMAIL and OPENALEX_API_KEY (optional, never printed).
set -Eeuo pipefail
cd "$(dirname "$0")/../.." || exit 1
RECORDS="${1:-1000}"
SNAP="${SNAPSHOT:-$(date -u +%F)}"
if ! [[ "$SNAP" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || ! date -d "$SNAP" +%F >/dev/null 2>&1; then
  echo "[datasets] invalid SNAPSHOT '$SNAP': must be a real date, YYYY-MM-DD" >&2
  exit 2
fi
F=datasets/scripts/fetch_samples.py
python3 "$F" dblp --records "$RECORDS" --min-authors 2 --snapshot "$SNAP"
python3 "$F" dtd --snapshot "$SNAP"
python3 "$F" openalex --max-dois "${MAX_DOIS:-50}" --snapshot "$SNAP"
python3 "$F" crossref --max-dois "${MAX_DOIS:-50}" --snapshot "$SNAP"
if [ "${WITH_ORCID:-0}" = "1" ]; then
  python3 "$F" orcid --max "${ORCID_MAX:-20}" --snapshot "$SNAP"
else
  echo "[datasets] ORCID step skipped (opt-in: WITH_ORCID=1, pending the human decision on ORCID API terms)"
fi
python3 "$F" verify --snapshot "$SNAP"
echo "[datasets] snapshot $SNAP ready in datasets/samples/local/$SNAP"
