#!/usr/bin/env python3
"""Validates datasets/dataset-manifest.yaml (strict YAML subset) with the standard library only.

Checks: required fields per dataset, dblp required plus at least three required enrichment datasets,
list-typed fields, no hardcoded account ID or bucket in S3 prefixes, no file names with a forbidden
suffix under datasets/, and the SHA-256 and byte size of every committed sample file under sample_files.
Usage: validate-manifest.py [manifest] (run from the repository root)."""
import hashlib, os, re, sys

REQUIRED = ["name", "purpose", "source_url", "documentation", "license", "license_source", "observed_on", "snapshot_date",
            "retrieval", "format", "expected_size", "observed_size", "raw_prefix", "integrity", "schema_version",
            "subset_rule", "update_strategy", "linkage_fields", "privacy", "limitations", "validation", "unverified", "owner"]
LISTS = ("linkage_fields", "limitations", "validation", "unverified", "sample_files")
FORBIDDEN_SUFFIXES = ("." + "env", "." + "tfvars")


def parse(path):
    root, datasets, current, key_list = {}, None, None, None
    for n, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        text = line.strip()
        if text.startswith("- "):
            if current is None or key_list is None:
                raise ValueError(f"line {n}: list item outside a list")
            current[key_list].append(text[2:].strip().strip('"'))
            continue
        if ":" not in text:
            raise ValueError(f"line {n}: expected 'key: value'")
        k, v = text.split(":", 1)
        k, v = k.strip(), v.strip()
        if indent == 0:
            root[k] = v.strip('"') if v else {}
            datasets = root[k] if k == "datasets" else datasets
        elif indent == 2:
            datasets[k] = {}
            current = datasets[k]
        elif indent == 4:
            if v == "":
                current[k] = []
                key_list = k
            else:
                current[k] = v.strip('"')
                key_list = None
        else:
            raise ValueError(f"line {n}: unsupported indentation {indent}")
    return root


def check(path):
    m = parse(path)
    errors = []
    ds = m.get("datasets", {})
    required = [k for k, v in ds.items() if v.get("required") == "true"]
    if "dblp" not in required:
        errors.append("dblp must be a required dataset")
    if len([k for k in required if k != "dblp"]) < 3:
        errors.append("at least three required enrichment datasets are needed")
    for name, d in ds.items():
        for f in REQUIRED:
            if f not in d or d[f] in ("", []):
                errors.append(f"{name}: missing {f}")
        for f in LISTS:
            if f in d and not isinstance(d[f], list):
                errors.append(f"{name}: {f} must be a list")
        if d.get("owner") != "dataset-engineer":
            errors.append(f"{name}: owner must be dataset-engineer")
        rp = d.get("raw_prefix", "")
        if rp.startswith("s3://") and not rp.startswith("s3://${"):
            errors.append(f"{name}: raw_prefix must use a ${{VAR}} bucket placeholder, got {rp}")
        if re.search(r"\b\d{12}\b", rp):
            errors.append(f"{name}: raw_prefix contains an account ID")
        for entry in d.get("sample_files", []):
            mm = re.match(r"(\S+) sha256=([0-9a-f]{64}) bytes=(\d+)", entry)
            if not mm:
                errors.append(f"{name}: malformed sample_files entry: {entry}")
                continue
            f, digest, size = mm.group(1), mm.group(2), int(mm.group(3))
            if not os.path.exists(f):
                errors.append(f"{name}: sample file missing: {f}")
                continue
            with open(f, "rb") as fh:
                actual = hashlib.sha256(fh.read()).hexdigest()
            if actual != digest:
                errors.append(f"{name}: sha256 mismatch for {f}")
            if os.path.getsize(f) != size:
                errors.append(f"{name}: size mismatch for {f}")
    for dirpath, _, files in os.walk("datasets"):
        for fn in files:
            if fn.endswith(FORBIDDEN_SUFFIXES):
                errors.append(f"forbidden file name: {os.path.join(dirpath, fn)}")
    return ds, required, errors


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "datasets/dataset-manifest.yaml"
    ds, required, errors = check(path)
    if errors:
        print("\n".join("[manifest][FAIL] " + e for e in errors))
        sys.exit(1)
    print(f"[manifest][ok] {len(ds)} datasets, required: {', '.join(required)}")


if __name__ == "__main__":
    main()
