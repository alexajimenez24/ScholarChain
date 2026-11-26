# Neptune Serverless: one cluster, ONE db.serverless instance, 1 to 2.5 NCU.
# - Created only while enabled = true (inside a cloud window); it bills per NCU-hour
#   while it exists, and never scales below min_ncu.
# - Loaded only by the Neptune bulk loader from the graph bucket (through the S3 gateway
#   endpoint), never by sustained openCypher writes.
# - IAM database authentication is required; no password exists.
# - Encrypted at rest with the AWS managed key (aws/rds): no customer key is needed
#   because no cross-account or cross-service grant is involved.
# - Backups: 1 day in dev, 7 days when persistent. Deletion protection and a final
#   snapshot only when persistent (dev: off, so cleanup works and no snapshot keeps
#   billing; Phase 12 addendum).
# - Loader role: assumed by Neptune (rds.amazonaws.com) to read only the graph bucket.
#   The role is created by the foundation stack (admin-applied); this module puts only
#   its inline policy, which exists even when the cluster does not, at no cost.

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

variable "enabled" {
  type = bool
}

variable "persistent" {
  type = bool
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "graph_bucket_arn" {
  type = string
}

variable "loader_role_arn" {
  description = "ARN of the foundation-created loader role (modules/workload-identities, key neptune-loader)."
  type        = string
}

variable "engine_version" {
  description = "Neptune engine; Serverless needs 1.2.0.1 or later. 1.4.8.1 is active with no end-of-life date (checked 2026-11-26)."
  type        = string
  default     = "1.4.8.1"
}

variable "parameter_group_family" {
  type    = string
  default = "neptune1.4"
}

variable "min_ncu" {
  type    = number
  default = 1

  validation {
    condition     = var.min_ncu >= 1 && var.min_ncu <= 2.5
    error_message = "min_ncu must be between 1 and 2.5 (cost bound)."
  }
}

variable "max_ncu" {
  type    = number
  default = 2.5

  validation {
    condition     = var.max_ncu == 2.5
    error_message = "max_ncu is fixed at 2.5 by the Phase 12 cost bound."
  }
}

variable "query_timeout_ms" {
  description = "Server-side query timeout; the client side uses neptune.queryTimeoutMs (5000)."
  type        = number
  default     = 20000
}

locals {
  count = var.enabled ? 1 : 0

  loader_role_name = element(split("/", var.loader_role_arn), length(split("/", var.loader_role_arn)) - 1)
}

# ----- Loader role policy (the role, its rds.amazonaws.com trust and the boundary are
# created by the foundation stack; repair round 2) ---------------------------------------
data "aws_iam_policy_document" "loader" {
  statement {
    sid       = "ListGraphBucket"
    effect    = "Allow"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [var.graph_bucket_arn]
  }

  statement {
    sid       = "ReadGraphObjects"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${var.graph_bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "loader" {
  name   = "read-graph-bucket"
  role   = local.loader_role_name
  policy = data.aws_iam_policy_document.loader.json
}

# ----- Subnet and parameter groups ----------------------------------------------------
resource "aws_neptune_subnet_group" "this" {
  count = local.count

  name        = "${var.name}-neptune"
  description = "Private subnets in two AZs for Neptune"
  subnet_ids  = var.subnet_ids
}

resource "aws_neptune_cluster_parameter_group" "this" {
  count = local.count

  name        = "${var.name}-neptune-cluster"
  family      = var.parameter_group_family
  description = "ScholarChain Neptune cluster parameters"

  parameter {
    name         = "neptune_enable_audit_log"
    value        = var.persistent ? "1" : "0"
    apply_method = "pending-reboot"
  }
}

resource "aws_neptune_parameter_group" "this" {
  count = local.count

  name        = "${var.name}-neptune-instance"
  family      = var.parameter_group_family
  description = "ScholarChain Neptune instance parameters"

  parameter {
    name         = "neptune_query_timeout"
    value        = tostring(var.query_timeout_ms)
    apply_method = "pending-reboot"
  }
}

# ----- Cluster and its single serverless instance -------------------------------------
resource "aws_neptune_cluster" "this" {
  count = local.count

  cluster_identifier                   = "${var.name}-graph"
  engine                               = "neptune"
  engine_version                       = var.engine_version
  neptune_subnet_group_name            = aws_neptune_subnet_group.this[0].name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.this[0].name
  vpc_security_group_ids               = [var.security_group_id]
  iam_database_authentication_enabled  = true
  iam_roles                            = [var.loader_role_arn]
  storage_encrypted                    = true
  backup_retention_period              = var.persistent ? 7 : 1
  copy_tags_to_snapshot                = true
  deletion_protection                  = var.persistent
  skip_final_snapshot                  = !var.persistent
  final_snapshot_identifier            = var.persistent ? "${var.name}-graph-final" : null
  enable_cloudwatch_logs_exports       = var.persistent ? ["audit"] : []
  apply_immediately                    = !var.persistent

  serverless_v2_scaling_configuration {
    min_capacity = var.min_ncu
    max_capacity = var.max_ncu
  }
}

resource "aws_neptune_cluster_instance" "this" {
  count = local.count

  identifier                   = "${var.name}-graph-1"
  cluster_identifier           = aws_neptune_cluster.this[0].id
  instance_class               = "db.serverless"
  engine                       = "neptune"
  neptune_parameter_group_name = aws_neptune_parameter_group.this[0].name
  auto_minor_version_upgrade   = true
  apply_immediately            = !var.persistent
}

output "enabled" {
  value = var.enabled
}

output "endpoint" {
  value = var.enabled ? aws_neptune_cluster.this[0].endpoint : ""
}

output "reader_endpoint" {
  value = var.enabled ? aws_neptune_cluster.this[0].reader_endpoint : ""
}

output "port" {
  value = var.enabled ? aws_neptune_cluster.this[0].port : 8182
}

output "cluster_resource_id" {
  value = var.enabled ? aws_neptune_cluster.this[0].cluster_resource_id : ""
}

output "loader_role_arn" {
  value = var.loader_role_arn
}
