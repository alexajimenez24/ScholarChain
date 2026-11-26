# The six Phase 07 tables (modules/storage/MODULE.md, Terraform contract): string keys
# pk and sk, TTL attribute "ttl" on the query-session table only, no secondary index
# (no access pattern of Phase 07 needs one).
# Capacity: on-demand (PAY_PER_REQUEST). The load is tiny and bursty (a bounded pipeline
# run, then a few queries); provisioned capacity would bill every hour and need
# autoscaling.
# Encryption: the default AWS owned key (no key fee, no KMS request charges).
# PITR and deletion protection: on only when persistent = true; in dev they are off so
# cleanup works, as the Phase 12 addendum says.

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

locals {
  # Logical name (as in core config `dynamo.*Table`) => settings.
  tables = {
    run-ledger        = { ttl = false }
    entity-link-cache = { ttl = false }
    query-session     = { ttl = true }
    evidence-bundle   = { ttl = false }
    artifact-registry = { ttl = false }
    researcher-metric = { ttl = false }
  }
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name         = "${var.name}-${each.key}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  dynamic "ttl" {
    for_each = each.value.ttl ? [1] : []
    content {
      attribute_name = "ttl"
      enabled        = true
    }
  }

  point_in_time_recovery {
    enabled = var.persistent
  }

  deletion_protection_enabled = var.persistent

  tags = { table = each.key }
}

output "table_names" {
  value = { for k, t in aws_dynamodb_table.this : k => t.name }
}

output "table_arns" {
  value = { for k, t in aws_dynamodb_table.this : k => t.arn }
}
