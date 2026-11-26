# Workload role identities: every role a ScholarChain workload runs as, with its trust
# policy and the workload permissions boundary. Applied ONLY in the foundation stack, which
# the human applies with administrator credentials whenever a role changes (deploy.sh
# refuses the deployment role for any foundation plan with admin-changes > 0).
#
# Phase 12 repair round 2 (human decision 1): the deployment role may never set a role's
# trust by any route (CreateRole, UpdateAssumeRolePolicy, delete and re-create), so role
# creation moved here from the data and app stacks. Those stacks keep only the inline
# least-privilege policies (iam:PutRolePolicy on these exact role names), so each stack's
# permissions still live next to the resources they name.
#
#   runtime-query, runtime-graph, runtime-worker  ECS task roles (trust ecs-tasks)
#   ecs-execution                                 ECS task execution role (trust ecs-tasks)
#   emr-service                                   EMR service role (trust elasticmapreduce;
#                                                 AWS managed AmazonEMRServicePolicy_v2)
#   emr-instance                                  EC2 instance profile role of EMR nodes
#   step-functions                                the pipeline state machine (trust states;
#                                                 the pipeline boundary: the only EMR
#                                                 create, run and terminate rights)
#   neptune-loader                                Neptune bulk loader (trust rds)
# The EMR service role's pass-instance-role grant and the instance profile are here too,
# because neither references a data-stack resource.

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

variable "role_names" {
  description = "Role key => role name (foundation stack locals; the boundary uses the same names)."
  type        = map(string)
}

variable "permissions_boundary_arn" {
  description = "Workload boundary: every role except emr-service."
  type        = string
}

variable "emr_service_boundary_arn" {
  description = "Boundary of the EMR service role, the only role that may launch EC2 instances."
  type        = string
}

variable "pipeline_boundary_arn" {
  description = "Boundary of the step-functions role, the only role that may create, run or terminate EMR clusters (repair round 3)."
  type        = string
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  region    = data.aws_region.current.region

  # Trust kind of every role key; a key without a trust kind fails the plan.
  trust_kind = {
    runtime-query  = "ecs"
    runtime-graph  = "ecs"
    runtime-worker = "ecs"
    ecs-execution  = "ecs"
    emr-service    = "emr"
    emr-instance   = "ec2"
    step-functions = "sfn"
    neptune-loader = "rds"
  }

  description = {
    runtime-query  = "query-service ECS task role"
    runtime-graph  = "graph-service ECS task role (read-only Neptune runtime role)"
    runtime-worker = "embedding-worker ECS task role"
    ecs-execution  = "ECS task execution role: pulls the three images and writes their logs"
    emr-service    = "EMR service role"
    emr-instance   = "EMR EC2 instance profile role"
    step-functions = "Pipeline state machine role"
    neptune-loader = "Neptune bulk loader: read-only access to the graph bucket"
  }

  # Service principal, and the aws:SourceArn pattern where the service sets one.
  trust = {
    ecs = { service = "ecs-tasks.amazonaws.com", source = "arn:${local.partition}:ecs:${local.region}:${local.account}:*" }
    emr = { service = "elasticmapreduce.amazonaws.com", source = "arn:${local.partition}:elasticmapreduce:${local.region}:${local.account}:*" }
    ec2 = { service = "ec2.amazonaws.com", source = "" }
    sfn = { service = "states.amazonaws.com", source = "arn:${local.partition}:states:${local.region}:${local.account}:stateMachine:${var.name}-*" }
    rds = { service = "rds.amazonaws.com", source = "" }
  }
}

data "aws_iam_policy_document" "trust" {
  for_each = var.role_names

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = [local.trust[local.trust_kind[each.key]].service]
    }

    # EC2 sets neither key for an instance profile.
    dynamic "condition" {
      for_each = local.trust_kind[each.key] == "ec2" ? [] : [1]
      content {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [local.account]
      }
    }

    dynamic "condition" {
      for_each = local.trust[local.trust_kind[each.key]].source == "" ? [] : [1]
      content {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = [local.trust[local.trust_kind[each.key]].source]
      }
    }
  }
}

resource "aws_iam_role" "this" {
  for_each = var.role_names

  name                 = each.value
  description          = local.description[each.key]
  assume_role_policy   = data.aws_iam_policy_document.trust[each.key].json
  permissions_boundary = lookup({ emr-service = var.emr_service_boundary_arn, step-functions = var.pipeline_boundary_arn }, each.key, var.permissions_boundary_arn)
}

# AWS managed policy (documented in infra/README.md, "IAM scope"): AWS recommends it for
# the EMR service role; its EC2 statements require the for-use-with-amazon-emr-managed-
# policies tag, which the private subnets, the three EMR security groups and every
# cluster (compute stack) carry. The EMR service boundary limits it further (no new
# endpoint, security group or network path, this Region only).
resource "aws_iam_role_policy_attachment" "emr_service" {
  role       = aws_iam_role.this["emr-service"].name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

# AmazonEMRServicePolicy_v2 lets EMR pass only roles named EMR_EC2_DefaultRole; this
# grants passing exactly the project instance role, to EC2 only.
data "aws_iam_policy_document" "emr_service_pass" {
  statement {
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.this["emr-instance"].arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "emr_service_pass" {
  name   = "pass-instance-role"
  role   = aws_iam_role.this["emr-service"].id
  policy = data.aws_iam_policy_document.emr_service_pass.json
}

resource "aws_iam_instance_profile" "emr" {
  name = var.role_names["emr-instance"]
  role = aws_iam_role.this["emr-instance"].name
}

output "role_arns" {
  value = { for k, r in aws_iam_role.this : k => r.arn }
}

output "emr_instance_profile_arn" {
  value = aws_iam_instance_profile.emr.arn
}

output "emr_instance_profile_name" {
  value = aws_iam_instance_profile.emr.name
}
