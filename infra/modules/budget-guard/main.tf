# Project cost guard, exactly as the Phase 12 addendum says:
# - one AWS Budget of $25 (COST, account-wide, no filter, so every charge counts;
#   credits and refunds are NOT netted, see cost_types);
# - email alerts on ACTUAL spend at $5, $10, $15 and $20 and on FORECASTED spend at $20;
# - a budget action at $20 ACTUAL (automatic) that attaches a deny policy to the
#   deployment role, blocking creation of new billable resources. Deletes, scale-downs
#   and reads stay allowed so cleanup still works.
# The $25 cap covers the whole course, not a month, so the budget is ANNUALLY (calendar
# year). Billing data lags by hours: this is a backstop, not the enforcement mechanism.
# The alert address is a plan-time input (sensitive); it is never written into a file.

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

variable "budget_name" {
  type = string
}

variable "limit_usd" {
  type    = number
  default = 25
}

variable "actual_thresholds_usd" {
  type    = list(number)
  default = [5, 10, 15, 20]
}

variable "forecast_threshold_usd" {
  type    = number
  default = 20
}

variable "action_threshold_usd" {
  type    = number
  default = 20
}

variable "alert_email" {
  description = "Budget alert address, supplied at plan time (TF_VAR_budget_alert_email)."
  type        = string
  sensitive   = true
}

variable "deployment_role_name" {
  type = string
}

variable "deny_policy_name" {
  type = string
}

variable "action_role_name" {
  type = string
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  partition       = data.aws_partition.current.partition
  account         = data.aws_caller_identity.current.account_id
  deployment_role = "arn:${local.partition}:iam::${local.account}:role/${var.deployment_role_name}"
}

resource "aws_budgets_budget" "project" {
  name         = var.budget_name
  budget_type  = "COST"
  limit_amount = format("%.2f", var.limit_usd)
  limit_unit   = "USD"
  time_unit    = "ANNUALLY"

  # Repair round 2 (human decision 2): track usage BEFORE credits and refunds, so the
  # alerts and the $20 action fire on real consumption even on a credit-funded account.
  # aws 6.74.0 cost_types (Terraform Registry docs, aws_budgets_budget, read 2026-11-26);
  # every other flag is written at its provider default, which counts the charge: tax,
  # support, subscriptions, upfront and recurring fees, other subscriptions and discounts
  # included; unblended (not blended) and not amortized rates.
  cost_types {
    include_credit             = false
    include_refund             = false
    include_discount           = true
    include_other_subscription = true
    include_recurring          = true
    include_subscription       = true
    include_support            = true
    include_tax                = true
    include_upfront            = true
    use_amortized              = false
    use_blended                = false
  }

  dynamic "notification" {
    for_each = var.actual_thresholds_usd
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [var.alert_email]
    }
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = var.forecast_threshold_usd
    threshold_type             = "ABSOLUTE_VALUE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}

# Deny policy attached by the action. It names only create, start and run actions of
# services that bill by time or by request at scale; nothing that deletes or scales down.
data "aws_iam_policy_document" "deny_billable" {
  statement {
    sid    = "DenyNewBillableResources"
    effect = "Deny"
    actions = [
      "ec2:RunInstances",
      "ec2:CreateNatGateway",
      "ec2:AllocateAddress",
      "ec2:CreateVpcEndpoint",
      "elasticmapreduce:RunJobFlow",
      "elasticmapreduce:AddInstanceGroups",
      "elasticmapreduce:AddInstanceFleet",
      "elasticmapreduce:AddJobFlowSteps",
      "states:StartExecution",
      "states:StartSyncExecution",
      "ecs:CreateService",
      "ecs:RunTask",
      "ecs:StartTask",
      "rds:CreateDBCluster",
      "rds:CreateDBInstance",
      "rds:RestoreDBClusterFromSnapshot",
      "es:CreateDomain",
      "aoss:CreateCollection",
      "s3vectors:CreateVectorBucket",
      "s3vectors:CreateIndex",
      "s3vectors:PutVectors",
      "dynamodb:CreateTable",
      "apigateway:POST",
      "cloudwatch:PutDashboard",
      "cloudwatch:PutMetricAlarm",
      "secretsmanager:CreateSecret",
      "kms:CreateKey",
      "bedrock:InvokeModel",
      "bedrock:InvokeModelWithResponseStream",
      "sagemaker:CreateEndpoint",
      "lambda:CreateFunction",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "deny_billable" {
  name        = var.deny_policy_name
  description = "Attached to the deployment role by the budget action at the action threshold."
  policy      = data.aws_iam_policy_document.deny_billable.json
}

data "aws_iam_policy_document" "action_trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["budgets.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_iam_role" "action" {
  name               = var.action_role_name
  description        = "Assumed by AWS Budgets to attach the deny policy to the deployment role."
  assume_role_policy = data.aws_iam_policy_document.action_trust.json
}

# Least privilege for the action: attach or detach exactly one policy on exactly one role.
data "aws_iam_policy_document" "action" {
  statement {
    effect    = "Allow"
    actions   = ["iam:AttachRolePolicy", "iam:DetachRolePolicy"]
    resources = [local.deployment_role]
    condition {
      test     = "ArnEquals"
      variable = "iam:PolicyARN"
      values   = [aws_iam_policy.deny_billable.arn]
    }
  }
}

resource "aws_iam_role_policy" "action" {
  name   = "attach-deny-to-deployment-role"
  role   = aws_iam_role.action.id
  policy = data.aws_iam_policy_document.action.json
}

resource "aws_budgets_budget_action" "deny_at_threshold" {
  budget_name        = aws_budgets_budget.project.name
  action_type        = "APPLY_IAM_POLICY"
  approval_model     = "AUTOMATIC"
  notification_type  = "ACTUAL"
  execution_role_arn = aws_iam_role.action.arn

  action_threshold {
    action_threshold_type  = "ABSOLUTE_VALUE"
    action_threshold_value = var.action_threshold_usd
  }

  definition {
    iam_action_definition {
      policy_arn = aws_iam_policy.deny_billable.arn
      roles      = [var.deployment_role_name]
    }
  }

  subscriber {
    address           = var.alert_email
    subscription_type = "EMAIL"
  }

  depends_on = [aws_iam_role_policy.action]
}

output "budget_name" {
  value = aws_budgets_budget.project.name
}

output "deny_policy_arn" {
  value = aws_iam_policy.deny_billable.arn
}
