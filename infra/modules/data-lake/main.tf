# Data lake: one private bucket per zone.
#   raw        source snapshots (DBLP and open datasets); authoritative, versioned
#   curated    Hadoop and entity-resolution outputs, plus the reports/ prefix that the
#              storage code uses for run reports (StorageConfig has no reports bucket);
#              versioned because the reports are evidence
#   (logs is the only unversioned bucket: its objects expire after log_retention_days)
#   graph      Spark outputs and Neptune bulk-load files; versioned
#   rag        semantic-context text objects and vector-load manifests; versioned
#   artifacts  job jars, bootstrap scripts, release evidence; versioned
#   logs       EMR logs and S3 server access logs of the other buckets; expires
# Every bucket: SSE-S3 (AES256, AWS-managed, no key fee), all public access blocked,
# BucketOwnerEnforced (no ACLs), TLS-only policy, abort of incomplete uploads.
# Deletion safeguard: force_destroy is always false, so a destroy stops at any non-empty
# bucket and evidence survives cleanup. Names use bucket_prefix (random suffix), so no
# account ID is written into a bucket name.

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.74.0"
    }
  }
}

variable "name" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "noncurrent_version_days" {
  type    = number
  default = 30
}

locals {
  zones = {
    raw       = { versioned = true }
    curated   = { versioned = true }
    graph     = { versioned = true }
    rag       = { versioned = true }
    artifacts = { versioned = true }
    logs      = { versioned = false }
  }
  access_logged = [for z in keys(local.zones) : z if z != "logs"]
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "this" {
  for_each = local.zones

  bucket_prefix = "${var.name}-${each.key}-"
  force_destroy = false

  tags = { zone = each.key }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = aws_s3_bucket.this

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = { for k, v in local.zones : k => v if v.versioned }

  bucket = aws_s3_bucket.this[each.key].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_logging" "this" {
  for_each = toset(local.access_logged)

  bucket        = aws_s3_bucket.this[each.key].id
  target_bucket = aws_s3_bucket.this["logs"].id
  target_prefix = "s3-access/${each.key}/"
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 2
    }
  }

  dynamic "rule" {
    for_each = local.zones[each.key].versioned ? [1] : []
    content {
      id     = "expire-noncurrent-versions"
      status = "Enabled"
      filter {}
      noncurrent_version_expiration {
        noncurrent_days = var.noncurrent_version_days
      }
    }
  }

  dynamic "rule" {
    for_each = each.key == "logs" ? [1] : []
    content {
      id     = "expire-logs"
      status = "Enabled"
      filter {}
      expiration {
        days = var.log_retention_days
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

data "aws_iam_policy_document" "bucket" {
  for_each = aws_s3_bucket.this

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [each.value.arn, "${each.value.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  dynamic "statement" {
    for_each = each.key == "logs" ? [1] : []
    content {
      sid       = "AllowS3ServerAccessLogDelivery"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${each.value.arn}/s3-access/*"]
      principals {
        type        = "Service"
        identifiers = ["logging.s3.amazonaws.com"]
      }
      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }
      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = [for z in local.access_logged : aws_s3_bucket.this[z].arn]
      }
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = aws_s3_bucket.this

  bucket = each.value.id
  policy = data.aws_iam_policy_document.bucket[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

output "bucket_names" {
  value = { for k, b in aws_s3_bucket.this : k => b.bucket }
}

output "bucket_arns" {
  value = { for k, b in aws_s3_bucket.this : k => b.arn }
}
