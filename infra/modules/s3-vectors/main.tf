# Durable semantic retrieval: one S3 vector bucket and the five indexes of core config
# `vectors.indexes`, each float32, cosine, the configured dimension, and the
# non-filterable metadata keys exactly as storage `VectorIndexes.NonFilterable`
# (modules/storage/MODULE.md, Terraform contract). Long text never lives in vector
# metadata; textUri points into the rag bucket.
# Encryption: SSE-S3 (AES256), the AWS-managed choice; no key fee.
# Access: identity policies on the runtime roles, plus a bucket policy that denies every
# principal outside this account.
# force_destroy follows `persistent`: in dev a cleanup removes the indexes with their
# vectors (they bill per GB-month); a persistent environment keeps them.
# Every index argument forces replacement, so a changed dimension or key list is a
# stateful REPLACEMENT that must be listed first in the plan summary.

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

variable "persistent" {
  type = bool
}

variable "dimension" {
  description = "Embedding dimension; core config EMBEDDING_DIMENSION default 256 (Titan V2 accepts 256, 512, 1024)."
  type        = number
  default     = 256

  validation {
    condition     = contains([256, 512, 1024], var.dimension)
    error_message = "dimension must be 256, 512 or 1024 (Titan Text Embeddings V2)."
  }
}

variable "indexes" {
  description = "Logical name (core config key) => index name."
  type        = map(string)
  default = {
    researchers       = "researcher-profile-v1"
    communities       = "collaboration-community-v1"
    abstracts         = "paper-abstract-v1"
    paths             = "collaboration-path-v1"
    topicInstitutions = "topic-institution-summary-v1"
  }
}

variable "non_filterable_keys" {
  type    = list(string)
  default = ["textUri", "textSha256", "evidenceId", "sourceUri"]
}

data "aws_caller_identity" "current" {}

resource "aws_s3vectors_vector_bucket" "this" {
  vector_bucket_name = "${var.name}-vectors"
  force_destroy      = !var.persistent

  encryption_configuration {
    sse_type = "AES256"
  }
}

data "aws_iam_policy_document" "bucket" {
  statement {
    sid     = "DenyOtherAccounts"
    effect  = "Deny"
    actions = ["s3vectors:*"]
    resources = [
      aws_s3vectors_vector_bucket.this.vector_bucket_arn,
      "${aws_s3vectors_vector_bucket.this.vector_bucket_arn}/index/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "StringNotEquals"
      variable = "aws:PrincipalAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_s3vectors_vector_bucket_policy" "this" {
  vector_bucket_arn = aws_s3vectors_vector_bucket.this.vector_bucket_arn
  policy            = data.aws_iam_policy_document.bucket.json
}

resource "aws_s3vectors_index" "this" {
  for_each = var.indexes

  index_name         = each.value
  vector_bucket_name = aws_s3vectors_vector_bucket.this.vector_bucket_name
  data_type          = "float32"
  dimension          = var.dimension
  distance_metric    = "cosine"

  metadata_configuration {
    non_filterable_metadata_keys = var.non_filterable_keys
  }
}

output "vector_bucket_name" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_name
}

output "vector_bucket_arn" {
  value = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "index_names" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_name }
}

output "index_arns" {
  value = { for k, i in aws_s3vectors_index.this : k => i.index_arn }
}
