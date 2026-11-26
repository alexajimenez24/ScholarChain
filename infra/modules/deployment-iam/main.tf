# Deployment identity and the workload permissions boundary.
#
# Phase 12 repair round 2 (human decision 1, verbatim in docs/agent-runs/phase-12): IAM
# is scoped to actual resources. Every statement that keeps Resource "*" is an action the
# Service Authorization Reference lists with no resource type (or a Deny, which a narrower
# resource would weaken); each is listed in infra/README.md, "IAM scope", with its URL.
#
# 1. Permissions boundary "<name>-workload-boundary": attached to every workload role
#    except the EMR service and step-functions roles (modules/workload-identities). It
#    caps what any such role can ever do, even if a later inline policy is too wide:
#    project resources only, no EC2, no IAM writes, no budget, billing or organization
#    calls, no deletion of buckets, tables, vector buckets or the Neptune cluster, secrets
#    only under scholarchain/<env>/, and an explicit Deny of every instance launch route,
#    every EMR and Step Functions action and every PassRole (repair round 3).
#    "<name>-emr-service-boundary" caps the EMR service role, the only principal that
#    may launch an instance, to the actions of AmazonEMRServicePolicy_v2.
#    "<name>-pipeline-boundary" caps the step-functions role, the only role that may
#    create, run, change or terminate an EMR cluster (repair round 3).
# 2. Deployment role "<name>-deployment": the identity scripts/deploy.sh REQUIRES for the
#    data, compute, app and observability stacks, and for foundation plans with
#    admin-changes = 0 (the NAT and endpoint toggles); run-bounded-pipeline.sh start
#    requires it too (infra/README.md, "Deployment identity"). 6 managed policies:
#    network, data, runtime, edge, control and guards (every Deny). It is separate from
#    every runtime role. It
#    never creates, deletes or re-trusts a role (roles are foundation resources, applied
#    by the human), only puts the inline policies of the named workload roles, passes
#    only named roles to the one service each is for, never modifies itself, its own
#    "<name>-deploy-*" policies, the boundary, the budget or the budget action resources,
#    never assumes another role, never launches an instance and never reads a secret value.
# Trust: the account itself (arn:aws:iam::<account>:root), so only principals in this
# account that already hold sts:AssumeRole on this role ARN can assume it. The account ID
# is read at plan time and never written into a file.

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

variable "environment" {
  type = string
}

variable "state_bucket_name" {
  description = "Name of the Terraform state bucket (created by the human outside Terraform)."
  type        = string
}

variable "budget_name" {
  type = string
}

variable "budget_action_role_name" {
  description = "Name of the role AWS Budgets assumes; protected from the deployment role."
  type        = string
}

variable "budget_deny_policy_name" {
  description = "Name of the deny policy the budget action attaches; protected from the deployment role."
  type        = string
}

variable "workload_role_names" {
  description = "Role key => name of every workload role (modules/workload-identities uses the same map)."
  type        = map(string)
}

variable "vpc_id" {
  description = "The project VPC (Route 53 private zone conditions for Cloud Map)."
  type        = string
}

variable "max_session_seconds" {
  description = "Maximum session length of the deployment role (a Neptune create can take 20 minutes)."
  type        = number
  default     = 7200
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  region    = data.aws_region.current.region

  role_name     = "${var.name}-deployment"
  boundary_name = "${var.name}-workload-boundary"

  iam_prefix       = "arn:${local.partition}:iam::${local.account}"
  role_arn         = "${local.iam_prefix}:role/${local.role_name}"
  boundary_arn     = "${local.iam_prefix}:policy/${local.boundary_name}"
  deny_policy_arn  = "${local.iam_prefix}:policy/${var.budget_deny_policy_name}"
  budget_role_arn  = "${local.iam_prefix}:role/${var.budget_action_role_name}"
  budget_arn       = "arn:${local.partition}:budgets::${local.account}:budget/${var.budget_name}"
  project_roles    = "${local.iam_prefix}:role/${var.name}-*"
  project_policies = "${local.iam_prefix}:policy/${var.name}-*"
  deploy_policies  = "${local.iam_prefix}:policy/${var.name}-deploy-*"
  project_profiles = "${local.iam_prefix}:instance-profile/${var.name}-*"
  param_prefix     = "arn:${local.partition}:ssm:${local.region}:${local.account}:parameter/scholarchain/${var.environment}"
  secret_prefix    = "arn:${local.partition}:secretsmanager:${local.region}:${local.account}:secret:scholarchain/${var.environment}/*"
  in_region        = { test = "StringEquals", variable = "aws:RequestedRegion", values = [local.region] }

  # Exact workload role ARNs (strings, so the boundary does not depend on the roles).
  wl = { for k, n in var.workload_role_names : k => "${local.iam_prefix}:role/${n}" }

  # Regional ARN prefixes of this account.
  ec2    = "arn:${local.partition}:ec2:${local.region}:${local.account}"
  logs   = "arn:${local.partition}:logs:${local.region}:${local.account}:log-group:/scholarchain/${var.environment}"
  ecs    = "arn:${local.partition}:ecs:${local.region}:${local.account}"
  emr    = "arn:${local.partition}:elasticmapreduce:${local.region}:${local.account}"
  cognit = "arn:${local.partition}:cognito-idp:${local.region}:${local.account}"
  cmap   = "arn:${local.partition}:servicediscovery:${local.region}:${local.account}"
  apigw  = "arn:${local.partition}:apigateway:${local.region}::"

  # The tags every project resource carries (provider default_tags).
  tag_keys = { project = "scholarchain", environment = var.environment }

  zone_name = "${var.environment}.scholarchain.internal"

  # Every route to an EC2 instance; only the EMR service role may take one.
  launch_actions = [
    "ec2:RunInstances", "ec2:CreateFleet", "ec2:RequestSpotInstances", "ec2:RequestSpotFleet",
    "ec2:RunScheduledInstances", "ec2:StartInstances", "ec2:CreateLaunchTemplate",
    "ec2:CreateLaunchTemplateVersion", "ec2:ModifyLaunchTemplate", "ec2:ImportInstance",
    "autoscaling:CreateAutoScalingGroup", "autoscaling:UpdateAutoScalingGroup",
    "autoscaling:CreateLaunchConfiguration", "autoscaling:SetDesiredCapacity",
  ]
}

# ---------------------------------------------------------------------------------------
# Workload permissions boundary.
# ---------------------------------------------------------------------------------------
data "aws_iam_policy_document" "boundary" {
  statement {
    sid       = "Objects"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
    resources = ["arn:${local.partition}:s3:::${var.name}-*/*"]
  }

  statement {
    sid       = "Buckets"
    effect    = "Allow"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads"]
    resources = ["arn:${local.partition}:s3:::${var.name}-*"]
  }

  # The AWS-owned buckets EMR nodes read from a private subnet, and its log bucket
  # (modules/workload-roles, emr-instance).
  statement {
    sid     = "EmrRepos"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    resources = [
      "arn:${local.partition}:s3:::al2023-repos-${local.region}-de612dc2/*",
      "arn:${local.partition}:s3:::repo.${local.region}.emr.amazonaws.com/*",
      "arn:${local.partition}:s3:::aws157-instance-data-0-prod-${local.region}/*",
      "arn:${local.partition}:s3:::aws157-instance-data-1-prod-${local.region}/*",
    ]
  }

  statement {
    sid       = "EmrLogs"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["arn:${local.partition}:s3:::aws157-logs-prod-${local.region}/*"]
  }

  statement {
    sid     = "Vectors"
    effect  = "Allow"
    actions = ["s3vectors:QueryVectors", "s3vectors:GetVectors", "s3vectors:PutVectors", "s3vectors:ListVectors", "s3vectors:GetIndex", "s3vectors:GetVectorBucket"]
    resources = [
      "arn:${local.partition}:s3vectors:${local.region}:${local.account}:bucket/${var.name}-*",
      "arn:${local.partition}:s3vectors:${local.region}:${local.account}:bucket/${var.name}-*/index/*",
    ]
  }

  statement {
    sid     = "Tables"
    effect  = "Allow"
    actions = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:DescribeTable", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem"]
    resources = [
      "arn:${local.partition}:dynamodb:${local.region}:${local.account}:table/${var.name}-*",
      "arn:${local.partition}:dynamodb:${local.region}:${local.account}:table/${var.name}-*/index/*",
    ]
  }

  # The neptune-db ARN carries the cluster resource ID, which AWS assigns at creation and
  # the data stack creates after this policy: account and Region is the narrowest scope.
  statement {
    sid       = "Neptune"
    effect    = "Allow"
    actions   = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus", "neptune-db:GetEngineStatus", "neptune-db:StartLoaderJob", "neptune-db:GetLoaderJobStatus", "neptune-db:ListLoaderJobs", "neptune-db:CancelLoaderJob"]
    resources = ["arn:${local.partition}:neptune-db:${local.region}:${local.account}:*/*"]
  }

  # In-Region models and this account's inference profiles; a model in another Region
  # only through one of those profiles (Bedrock geo cross-Region inference).
  statement {
    sid     = "BedrockInRegion"
    effect  = "Allow"
    actions = ["bedrock:InvokeModel"]
    resources = [
      "arn:${local.partition}:bedrock:${local.region}::foundation-model/*",
      "arn:${local.partition}:bedrock:${local.region}:${local.account}:inference-profile/*",
    ]
  }

  statement {
    sid       = "BedrockProfile"
    effect    = "Allow"
    actions   = ["bedrock:InvokeModel"]
    resources = ["arn:${local.partition}:bedrock:*::foundation-model/*"]
    condition {
      test     = "ArnLike"
      variable = "bedrock:InferenceProfileArn"
      values   = ["arn:${local.partition}:bedrock:${local.region}:${local.account}:inference-profile/*"]
    }
  }

  statement {
    sid       = "Queues"
    effect    = "Allow"
    actions   = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
    resources = ["arn:${local.partition}:sqs:${local.region}:${local.account}:${var.name}-*"]
  }

  statement {
    sid       = "LogStreams"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["${local.logs}/*", "${local.logs}/*:*"]
  }

  # No resource type in the Service Authorization Reference (registry token). The
  # vended-log delivery actions moved to the pipeline boundary (repair round 3): only the
  # state machine role uses them.
  statement {
    sid       = "NoResourceType"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "Images"
    effect    = "Allow"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = ["arn:${local.partition}:ecr:${local.region}:${local.account}:repository/${var.name}-*"]
  }

  statement {
    sid       = "ParamsSecrets"
    effect    = "Allow"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath", "secretsmanager:GetSecretValue"]
    resources = ["${local.param_prefix}/*", local.secret_prefix]
  }

  # Anything not allowed above (EC2, EMR, Step Functions, IAM writes and PassRole, budgets,
  # billing, organizations, KMS key administration, Cognito, API Gateway and the rest) is
  # outside the boundary.

  # Repair round 3 (human, 2026-11-26): EMR create, run and terminate rights belong to the
  # step-functions role only, which carries the pipeline boundary, never this one. Every
  # EMR action, every Step Functions action and every PassRole is denied explicitly, so no
  # inline policy (iam:PutRolePolicy) can give a role under this boundary a route to a
  # cluster, the state machine or another role.
  statement {
    sid       = "NoEmrPipelineOrPassRole"
    effect    = "Deny"
    actions   = ["elasticmapreduce:*", "states:*", "iam:PassRole"]
    resources = ["*"]
  }

  # Human decision 1: EC2 launch only through the EMR service role, which carries its
  # own boundary (emr_service_boundary below), never this one.
  statement {
    sid       = "NoInstanceLaunch"
    effect    = "Deny"
    actions   = local.launch_actions
    resources = ["*"]
  }

  # Workloads may never build or widen network egress.
  statement {
    sid    = "DenyNetworkChanges"
    effect = "Deny"
    actions = [
      "ec2:CreateVpc",
      "ec2:DeleteVpc",
      "ec2:CreateInternetGateway",
      "ec2:AttachInternetGateway",
      "ec2:CreateNatGateway",
      "ec2:AllocateAddress",
    ]
    resources = ["*"]
  }

  statement {
    sid           = "SecretsOnlyUnderProjectPath"
    effect        = "Deny"
    actions       = ["secretsmanager:GetSecretValue"]
    not_resources = [local.secret_prefix]
  }

}

resource "aws_iam_policy" "boundary" {
  name        = local.boundary_name
  description = "Permissions boundary for every ScholarChain workload role except the EMR service and pipeline roles (${var.environment})."
  policy      = data.aws_iam_policy_document.boundary.json
}

# Boundary of the step-functions role (repair round 3, human decision 2026-11-26): the ONLY
# role that may create, run, change or terminate an EMR cluster and pass the two EMR
# roles. It caps the role to exactly the rights the state machine's EMR .sync tasks use
# (modules/workload-roles, "sfn"), even if a later inline policy is wider.
data "aws_iam_policy_document" "pipeline_boundary" {
  # elasticmapreduce:RunJobFlow has no resource type; the request must carry the tag.
  statement {
    sid       = "StartClusters"
    effect    = "Allow"
    actions   = ["elasticmapreduce:RunJobFlow"]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/project"
      values   = ["scholarchain"]
    }
  }

  statement {
    sid       = "ControlProjectClusters"
    effect    = "Allow"
    actions   = ["elasticmapreduce:DescribeCluster", "elasticmapreduce:TerminateJobFlows", "elasticmapreduce:AddJobFlowSteps", "elasticmapreduce:DescribeStep", "elasticmapreduce:CancelSteps", "elasticmapreduce:ListSteps", "elasticmapreduce:PutManagedScalingPolicy", "elasticmapreduce:PutAutoTerminationPolicy", "elasticmapreduce:AddTags"]
    resources = ["${local.emr}:cluster/*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/project"
      values   = ["scholarchain"]
    }
  }

  dynamic "statement" {
    for_each = { PassServiceRoleToEmr = ["emr-service", "elasticmapreduce.amazonaws.com"], PassInstanceRoleToEc2 = ["emr-instance", "ec2.amazonaws.com"] }
    content {
      sid       = statement.key
      effect    = "Allow"
      actions   = ["iam:PassRole"]
      resources = [local.wl[statement.value[0]]]
      condition {
        test     = "StringEquals"
        variable = "iam:PassedToService"
        values   = [statement.value[1]]
      }
    }
  }

  statement {
    sid       = "SyncIntegrationRules"
    effect    = "Allow"
    actions   = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
    resources = ["arn:${local.partition}:events:${local.region}:${local.account}:rule/StepFunctionsGetEventForEMR*"]
  }

  # No resource type in the Service Authorization Reference; Step Functions documents the
  # vended-log delivery actions on "*".
  statement {
    sid    = "ExecutionLogDelivery"
    effect = "Allow"
    actions = [
      "logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery", "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries", "logs:PutResourcePolicy", "logs:DescribeResourcePolicies", "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "EnqueueEmbeddingJobs"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = ["arn:${local.partition}:sqs:${local.region}:${local.account}:${var.name}-*"]
  }

  # Instances only through EMR (the EMR service role launches them), never directly; no
  # Step Functions call (the role cannot start, define or change a state machine); and no
  # pass of any role except the two EMR roles above.
  statement {
    sid       = "NoDirectLaunchOrPipelineChange"
    effect    = "Deny"
    actions   = concat(local.launch_actions, ["states:*"])
    resources = ["*"]
  }

  statement {
    sid           = "PassOnlyTheEmrRoles"
    effect        = "Deny"
    actions       = ["iam:PassRole"]
    not_resources = [local.wl["emr-service"], local.wl["emr-instance"]]
  }
}

resource "aws_iam_policy" "pipeline_boundary" {
  name        = "${var.name}-pipeline-boundary"
  description = "Permissions boundary of the ScholarChain pipeline (step-functions) role, the only role that may create, run or terminate EMR clusters (${var.environment})."
  policy      = data.aws_iam_policy_document.pipeline_boundary.json
}

# Boundary of the EMR service role, the ONLY principal that may launch EC2 instances: the
# actions of the AWS managed AmazonEMRServicePolicy_v2, whose own statements scope them by
# the for-use-with-amazon-emr-managed-policies tag (infra/README.md, "IAM scope"), and
# passing the instance role to EC2. Its CreateVpcEndpoint and CreateSecurityGroup are left
# out: the endpoint (compute) and the EMR security groups (foundation) are Terraform
# resources.
data "aws_iam_policy_document" "emr_service_boundary" {
  statement {
    sid    = "AmazonEMRServicePolicyV2Actions"
    effect = "Allow"
    actions = [
      "ec2:RunInstances", "ec2:CreateFleet", "ec2:CreateNetworkInterface", "ec2:CreateLaunchTemplate",
      "ec2:CreateLaunchTemplateVersion", "ec2:DeleteLaunchTemplate", "ec2:DeleteNetworkInterface",
      "ec2:ModifyInstanceAttribute", "ec2:TerminateInstances", "ec2:CreateTags", "ec2:DeleteTags",
      "ec2:Describe*", "ec2:AuthorizeSecurityGroupEgress", "ec2:AuthorizeSecurityGroupIngress",
      "ec2:RevokeSecurityGroupEgress", "ec2:RevokeSecurityGroupIngress", "ec2:CreatePlacementGroup",
      "ec2:DeletePlacementGroup", "ec2:ModifyVpcEndpoint",
      "application-autoscaling:DeleteScalingPolicy", "application-autoscaling:DeregisterScalableTarget",
      "application-autoscaling:DescribeScalableTargets", "application-autoscaling:DescribeScalingPolicies",
      "application-autoscaling:PutScalingPolicy", "application-autoscaling:RegisterScalableTarget",
      "resource-groups:ListGroupResources",
      "cloudwatch:PutMetricAlarm", "cloudwatch:DeleteAlarms", "cloudwatch:DescribeAlarms",
    ]
    resources = ["*"]
    condition {
      test     = local.in_region.test
      variable = local.in_region.variable
      values   = local.in_region.values
    }
  }

  statement {
    sid       = "PassOnlyTheInstanceRoleToEc2"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [local.wl["emr-instance"]]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }

  # Only into the project network: launches must use EMR-tagged subnets and security
  # groups (the managed policy's own condition), and never a new network path.
  statement {
    sid    = "DenyNetworkChanges"
    effect = "Deny"
    actions = [
      "ec2:CreateVpc", "ec2:DeleteVpc", "ec2:CreateInternetGateway", "ec2:AttachInternetGateway",
      "ec2:CreateNatGateway", "ec2:AllocateAddress", "ec2:CreateVpcEndpoint", "ec2:CreateSecurityGroup",
    ]
    resources = ["*"]
  }

  # Repair round 3: AmazonEMRServicePolicy_v2 (v6) holds no elasticmapreduce or states
  # action, so the EMR service role never starts, changes or terminates a cluster through
  # the EMR API; it only acts on EC2 for clusters the pipeline role created.
  statement {
    sid       = "NoEmrApiOrPipeline"
    effect    = "Deny"
    actions   = ["elasticmapreduce:*", "states:*"]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "emr_service_boundary" {
  name        = "${var.name}-emr-service-boundary"
  description = "Permissions boundary of the ScholarChain EMR service role, the only role that may launch EC2 instances (${var.environment})."
  policy      = data.aws_iam_policy_document.emr_service_boundary.json
}

# ---------------------------------------------------------------------------------------
# Deployment role.
# ---------------------------------------------------------------------------------------
data "aws_iam_policy_document" "trust" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account}:root"]
    }
  }
}

resource "aws_iam_role" "deployment" {
  name                 = local.role_name
  description          = "Applies the ScholarChain ${var.environment} stacks through scripts/deploy.sh; never a runtime role."
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = var.max_session_seconds
}

# Network: what the role applies in foundation (NAT and interface-endpoint toggles) and
# compute (EMR service endpoint and its security group rules). Creating or deleting the
# VPC, subnets, gateways, route tables and security groups is admin-only (foundation
# bootstrap). Every write is limited to resources carrying the project and environment
# tags, or to new resources requested with them.
data "aws_iam_policy_document" "network" {
  statement {
    sid       = "DescribeNoResourceType"
    effect    = "Allow"
    actions   = ["ec2:Describe*"]
    resources = ["*"]
    condition {
      test     = local.in_region.test
      variable = local.in_region.variable
      values   = local.in_region.values
    }
  }

  statement {
    sid       = "CreateTaggedNetworkResources"
    effect    = "Allow"
    actions   = ["ec2:AllocateAddress", "ec2:CreateNatGateway", "ec2:CreateVpcEndpoint", "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress"]
    resources = ["${local.ec2}:elastic-ip/*", "${local.ec2}:natgateway/*", "${local.ec2}:vpc-endpoint/*", "${local.ec2}:security-group-rule/*"]
    dynamic "condition" {
      for_each = local.tag_keys
      content {
        test     = "StringEquals"
        variable = "aws:RequestTag/${condition.key}"
        values   = [condition.value]
      }
    }
  }

  statement {
    sid    = "ChangeTaggedNetworkResources"
    effect = "Allow"
    actions = [
      "ec2:CreateNatGateway", "ec2:DeleteNatGateway", "ec2:ReleaseAddress", "ec2:DisassociateAddress",
      "ec2:CreateRoute", "ec2:DeleteRoute", "ec2:ReplaceRoute",
      "ec2:CreateVpcEndpoint", "ec2:ModifyVpcEndpoint", "ec2:DeleteVpcEndpoints",
      "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress",
      "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress", "ec2:ModifySecurityGroupRules",
      "ec2:UpdateSecurityGroupRuleDescriptionsIngress", "ec2:UpdateSecurityGroupRuleDescriptionsEgress",
      "ec2:CreateTags", "ec2:DeleteTags",
    ]
    resources = [for t in ["vpc", "subnet", "route-table", "security-group", "security-group-rule", "elastic-ip", "natgateway", "vpc-endpoint"] : "${local.ec2}:${t}/*"]
    dynamic "condition" {
      for_each = local.tag_keys
      content {
        test     = "StringEquals"
        variable = "aws:ResourceTag/${condition.key}"
        values   = [condition.value]
      }
    }
  }

  statement {
    sid       = "TagOnCreate"
    effect    = "Allow"
    actions   = ["ec2:CreateTags"]
    resources = ["${local.ec2}:elastic-ip/*", "${local.ec2}:natgateway/*", "${local.ec2}:vpc-endpoint/*", "${local.ec2}:security-group-rule/*"]
    condition {
      test     = "StringEquals"
      variable = "ec2:CreateAction"
      values   = ["AllocateAddress", "CreateNatGateway", "CreateVpcEndpoint", "AuthorizeSecurityGroupIngress", "AuthorizeSecurityGroupEgress"]
    }
  }
}

# Data stack: buckets, tables, Neptune, S3 Vectors, SSM parameters, all name-scoped.
data "aws_iam_policy_document" "data" {
  statement {
    sid    = "ProjectBuckets"
    effect = "Allow"
    actions = [
      "s3:CreateBucket", "s3:DeleteBucket", "s3:ListBucket", "s3:ListBucketVersions",
      "s3:ListBucketMultipartUploads",
      "s3:GetBucket*", "s3:PutBucket*", "s3:DeleteBucketPolicy",
      "s3:GetLifecycleConfiguration", "s3:PutLifecycleConfiguration",
      "s3:GetEncryptionConfiguration", "s3:PutEncryptionConfiguration",
      "s3:GetAccelerateConfiguration", "s3:GetReplicationConfiguration",
      "s3:GetAnalyticsConfiguration", "s3:GetInventoryConfiguration",
      "s3:GetMetricsConfiguration", "s3:GetIntelligentTieringConfiguration",
    ]
    resources = ["arn:${local.partition}:s3:::${var.name}-*"]
  }

  statement {
    sid    = "ProjectObjects"
    effect = "Allow"
    # Repair M3: run-bounded-pipeline.sh uploads the input and the job jars with `aws s3
    # cp`, which uses multipart uploads above 8 MB and aborts them on failure.
    actions = [
      "s3:GetObject", "s3:PutObject", "s3:GetObjectVersion", "s3:GetObjectTagging", "s3:PutObjectTagging",
      "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts",
    ]
    resources = ["arn:${local.partition}:s3:::${var.name}-*/*"]
  }

  statement {
    sid    = "VectorBucketsAndIndexes"
    effect = "Allow"
    actions = [
      "s3vectors:CreateVectorBucket", "s3vectors:DeleteVectorBucket", "s3vectors:GetVectorBucket",
      "s3vectors:CreateIndex", "s3vectors:DeleteIndex", "s3vectors:GetIndex", "s3vectors:ListIndexes",
      "s3vectors:PutVectorBucketPolicy", "s3vectors:GetVectorBucketPolicy",
      "s3vectors:DeleteVectorBucketPolicy",
      "s3vectors:TagResource", "s3vectors:UntagResource", "s3vectors:ListTagsForResource",
    ]
    resources = [
      "arn:${local.partition}:s3vectors:${local.region}:${local.account}:bucket/${var.name}-*",
      "arn:${local.partition}:s3vectors:${local.region}:${local.account}:bucket/${var.name}-*/index/*",
    ]
  }

  statement {
    sid    = "DynamoTables"
    effect = "Allow"
    actions = [
      "dynamodb:CreateTable", "dynamodb:DeleteTable", "dynamodb:DescribeTable", "dynamodb:UpdateTable",
      "dynamodb:DescribeContinuousBackups", "dynamodb:UpdateContinuousBackups",
      "dynamodb:DescribeTimeToLive", "dynamodb:UpdateTimeToLive",
      "dynamodb:TagResource", "dynamodb:UntagResource", "dynamodb:ListTagsOfResource",
    ]
    resources = ["arn:${local.partition}:dynamodb:${local.region}:${local.account}:table/${var.name}-*"]
  }

  statement {
    sid    = "NeptuneControlPlane"
    effect = "Allow"
    actions = [
      "rds:CreateDBCluster", "rds:DeleteDBCluster", "rds:ModifyDBCluster",
      "rds:CreateDBInstance", "rds:DeleteDBInstance", "rds:ModifyDBInstance",
      "rds:CreateDBSubnetGroup", "rds:DeleteDBSubnetGroup", "rds:ModifyDBSubnetGroup",
      "rds:CreateDBClusterParameterGroup", "rds:DeleteDBClusterParameterGroup",
      "rds:ModifyDBClusterParameterGroup",
      "rds:CreateDBParameterGroup", "rds:DeleteDBParameterGroup", "rds:ModifyDBParameterGroup",
      "rds:AddRoleToDBCluster", "rds:RemoveRoleFromDBCluster", "rds:Describe*",
      "rds:AddTagsToResource", "rds:RemoveTagsFromResource", "rds:ListTagsForResource",
    ]
    resources = concat(
      [for t in ["cluster", "db", "subgrp", "cluster-pg", "pg", "cluster-snapshot"] : "arn:${local.partition}:rds:${local.region}:${local.account}:${t}:${var.name}-*"],
      ["arn:${local.partition}:rds::${local.account}:global-cluster:*"],
    )
  }

  statement {
    sid    = "SsmProjectParameters"
    effect = "Allow"
    actions = [
      "ssm:PutParameter", "ssm:DeleteParameter", "ssm:GetParameter", "ssm:GetParameters",
      "ssm:GetParametersByPath", "ssm:AddTagsToResource", "ssm:RemoveTagsFromResource",
      "ssm:ListTagsForResource",
    ]
    resources = ["${local.param_prefix}/*"]
  }

  statement {
    sid    = "ListNoResourceType"
    effect = "Allow"
    actions = [
      "s3vectors:ListVectorBuckets", "dynamodb:ListTables", "ssm:DescribeParameters",
      "rds:DescribeDBEngineVersions", "rds:DescribeOrderableDBInstanceOptions",
    ]
    resources = ["*"]
    condition {
      test     = local.in_region.test
      variable = local.in_region.variable
      values   = local.in_region.values
    }
  }
}

# Compute, app (ECS) and observability stacks. Name-scoped where the ARN carries our
# name, tag-scoped where it carries a generated ID.
data "aws_iam_policy_document" "runtime" {
  statement {
    sid    = "NoResourceType"
    effect = "Allow"
    actions = [
      "elasticmapreduce:CreateSecurityConfiguration", "elasticmapreduce:DeleteSecurityConfiguration",
      "elasticmapreduce:DescribeSecurityConfiguration", "elasticmapreduce:ListClusters",
      "elasticmapreduce:GetBlockPublicAccessConfiguration",
      "ecs:DescribeTaskDefinition", "ecs:DeregisterTaskDefinition", "ecs:ListTaskDefinitions",
      "application-autoscaling:DescribeScalableTargets", "application-autoscaling:DescribeScalingPolicies",
      "application-autoscaling:DescribeScalingActivities",
      "ecr:GetAuthorizationToken", "states:ValidateStateMachineDefinition", "sqs:ListQueues",
    ]
    resources = ["*"]
    condition {
      test     = local.in_region.test
      variable = local.in_region.variable
      values   = local.in_region.values
    }
  }

  # Repair round 3: read only. Terminate, tag and every other cluster write belong to the
  # step-functions role (deploy-guards NeverLaunchOrChangeCompute denies them here).
  statement {
    sid       = "ReadProjectClusters"
    effect    = "Allow"
    actions   = ["elasticmapreduce:DescribeCluster", "elasticmapreduce:ListSteps", "elasticmapreduce:DescribeStep", "elasticmapreduce:ListInstanceGroups"]
    resources = ["${local.emr}:cluster/*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/project"
      values   = ["scholarchain"]
    }
  }

  statement {
    sid    = "EcsClusterServicesTasks"
    effect = "Allow"
    actions = [
      "ecs:CreateCluster", "ecs:DeleteCluster", "ecs:DescribeClusters", "ecs:UpdateCluster", "ecs:UpdateClusterSettings",
      "ecs:CreateService", "ecs:UpdateService", "ecs:DeleteService", "ecs:DescribeServices",
      "ecs:RegisterTaskDefinition", "ecs:DeleteTaskDefinitions",
      "ecs:TagResource", "ecs:UntagResource", "ecs:ListTagsForResource",
    ]
    resources = ["${local.ecs}:cluster/${var.name}", "${local.ecs}:service/${var.name}/*", "${local.ecs}:task-definition/${var.name}-*:*"]
  }

  statement {
    sid    = "EcsServiceScaling"
    effect = "Allow"
    actions = [
      "application-autoscaling:RegisterScalableTarget", "application-autoscaling:DeregisterScalableTarget",
      "application-autoscaling:PutScalingPolicy", "application-autoscaling:DeleteScalingPolicy",
    ]
    resources = ["arn:${local.partition}:application-autoscaling:${local.region}:${local.account}:scalable-target/*"]
    condition {
      test     = "StringEquals"
      variable = "application-autoscaling:service-namespace"
      values   = ["ecs"]
    }
  }

  # The tag actions have no service-namespace key, and tag-on-create runs before the
  # target carries a tag: account and Region scope (the target ARN is a generated ID).
  statement {
    sid       = "EcsScalingTargetTags"
    effect    = "Allow"
    actions   = ["application-autoscaling:TagResource", "application-autoscaling:UntagResource", "application-autoscaling:ListTagsForResource"]
    resources = ["arn:${local.partition}:application-autoscaling:${local.region}:${local.account}:scalable-target/*"]
  }

  statement {
    sid    = "NamedRuntimeResources"
    effect = "Allow"
    actions = [
      "states:CreateStateMachine", "states:DeleteStateMachine", "states:UpdateStateMachine",
      "states:DescribeStateMachine", "states:ListStateMachineVersions", "states:StartExecution",
      "states:DescribeExecution", "states:StopExecution", "states:ListExecutions",
      "states:GetExecutionHistory", "states:TagResource", "states:UntagResource",
      "states:ListTagsForResource",
      "sqs:CreateQueue", "sqs:DeleteQueue", "sqs:GetQueueAttributes", "sqs:SetQueueAttributes",
      "sqs:GetQueueUrl", "sqs:TagQueue", "sqs:UntagQueue", "sqs:ListQueueTags",
      "ecr:CreateRepository", "ecr:DeleteRepository", "ecr:DescribeRepositories",
      "ecr:PutImageTagMutability", "ecr:PutImageScanningConfiguration", "ecr:PutLifecyclePolicy",
      "ecr:GetLifecyclePolicy", "ecr:DeleteLifecyclePolicy", "ecr:SetRepositoryPolicy",
      "ecr:GetRepositoryPolicy", "ecr:DeleteRepositoryPolicy", "ecr:TagResource", "ecr:UntagResource",
      "ecr:ListTagsForResource", "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage",
      "ecr:DescribeImages", "ecr:ListImages", "ecr:DescribeImageScanFindings",
      "logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy",
      "logs:DeleteRetentionPolicy", "logs:TagResource", "logs:UntagResource",
      "logs:ListTagsForResource", "logs:TagLogGroup", "logs:ListTagsLogGroup",
      "logs:PutMetricFilter", "logs:DeleteMetricFilter", "logs:DescribeMetricFilters",
      "logs:FilterLogEvents", "logs:GetLogEvents",
      "cloudwatch:PutMetricAlarm", "cloudwatch:DeleteAlarms", "cloudwatch:DescribeAlarms",
      "cloudwatch:PutDashboard", "cloudwatch:GetDashboard", "cloudwatch:DeleteDashboards",
      "cloudwatch:TagResource", "cloudwatch:UntagResource", "cloudwatch:ListTagsForResource",
      "secretsmanager:CreateSecret", "secretsmanager:DeleteSecret", "secretsmanager:DescribeSecret",
      "secretsmanager:TagResource", "secretsmanager:UntagResource",
      "secretsmanager:GetResourcePolicy", "secretsmanager:PutResourcePolicy",
      "secretsmanager:DeleteResourcePolicy",
    ]
    resources = [
      "arn:${local.partition}:states:${local.region}:${local.account}:stateMachine:${var.name}-*",
      "arn:${local.partition}:states:${local.region}:${local.account}:execution:${var.name}-*:*",
      "arn:${local.partition}:sqs:${local.region}:${local.account}:${var.name}-*",
      "arn:${local.partition}:ecr:${local.region}:${local.account}:repository/${var.name}-*",
      "${local.logs}/*",
      "${local.logs}/*:*",
      "arn:${local.partition}:cloudwatch:${local.region}:${local.account}:alarm:${var.name}-*",
      "arn:${local.partition}:cloudwatch::${local.account}:dashboard/${var.name}-*",
      local.secret_prefix,
    ]
  }
}

# Edge (app stack): API Gateway, Cognito, Cloud Map with its private hosted zone, and the
# vended-log delivery of the API stage and the state machine.
data "aws_iam_policy_document" "edge" {
  statement {
    sid       = "ApiGatewayApisAndVpcLinks"
    effect    = "Allow"
    actions   = ["apigateway:GET", "apigateway:POST", "apigateway:PUT", "apigateway:PATCH", "apigateway:DELETE", "apigateway:TagResource", "apigateway:UntagResource"]
    resources = ["${local.apigw}/apis", "${local.apigw}/apis/*", "${local.apigw}/vpclinks", "${local.apigw}/vpclinks/*", "${local.apigw}/tags/*"]
  }

  # No resource type for these (Service Authorization Reference); creation must carry
  # the project tags.
  statement {
    sid       = "CreateTaggedPoolAndNamespace"
    effect    = "Allow"
    actions   = ["cognito-idp:CreateUserPool", "servicediscovery:CreatePrivateDnsNamespace", "servicediscovery:TagResource"]
    resources = ["*"]
    condition {
      test     = local.in_region.test
      variable = local.in_region.variable
      values   = local.in_region.values
    }
    dynamic "condition" {
      for_each = local.tag_keys
      content {
        test     = "StringEquals"
        variable = "aws:RequestTag/${condition.key}"
        values   = [condition.value]
      }
    }
  }

  statement {
    sid       = "CreateTaggedPoolTagsAndServices"
    effect    = "Allow"
    actions   = ["cognito-idp:TagResource", "servicediscovery:CreateService"]
    resources = ["${local.cognit}:userpool/*", "${local.cmap}:service/*"]
    dynamic "condition" {
      for_each = local.tag_keys
      content {
        test     = "StringEquals"
        variable = "aws:RequestTag/${condition.key}"
        values   = [condition.value]
      }
    }
  }

  statement {
    sid    = "TaggedPoolsAndNamespaces"
    effect = "Allow"
    actions = [
      "cognito-idp:DeleteUserPool", "cognito-idp:DescribeUserPool", "cognito-idp:UpdateUserPool",
      "cognito-idp:CreateUserPoolClient", "cognito-idp:DeleteUserPoolClient", "cognito-idp:DescribeUserPoolClient",
      "cognito-idp:UpdateUserPoolClient", "cognito-idp:CreateResourceServer", "cognito-idp:DeleteResourceServer",
      "cognito-idp:DescribeResourceServer", "cognito-idp:UpdateResourceServer", "cognito-idp:GetUserPoolMfaConfig",
      "cognito-idp:SetUserPoolMfaConfig", "cognito-idp:TagResource", "cognito-idp:UntagResource",
      "cognito-idp:ListTagsForResource", "cognito-idp:CreateUserPoolDomain", "cognito-idp:DeleteUserPoolDomain",
      "cognito-idp:DescribeUserPoolDomain", "cognito-idp:UpdateUserPoolDomain",
      "servicediscovery:CreateService", "servicediscovery:DeleteService", "servicediscovery:GetService",
      "servicediscovery:UpdateService", "servicediscovery:DeleteNamespace", "servicediscovery:GetNamespace",
      "servicediscovery:GetOperation", "servicediscovery:ListInstances",
    ]
    resources = ["${local.cognit}:userpool/*", "${local.cmap}:namespace/*", "${local.cmap}:service/*"]
    dynamic "condition" {
      for_each = local.tag_keys
      content {
        test     = "StringEquals"
        variable = "aws:ResourceTag/${condition.key}"
        values   = [condition.value]
      }
    }
  }

  # Route 53 hosted zone and change ARNs carry no account, Region or tag condition key;
  # zone membership is limited to the project VPC and record names to the namespace.
  # route53:VPCs values are "VPCId=<id>,VPCRegion=<region>" (Route 53 Developer Guide,
  # "Specifying conditions", read 2026-11-26). CreateHostedZone has no resource type.
  dynamic "statement" {
    for_each = {
      CreatePrivateZoneInProjectVpc = { actions = ["route53:CreateHostedZone"], resources = ["*"] }
      AssociateOnlyProjectVpc       = { actions = ["route53:AssociateVPCWithHostedZone", "route53:DisassociateVPCFromHostedZone"], resources = ["arn:${local.partition}:route53:::hostedzone/*"] }
    }
    content {
      sid       = statement.key
      effect    = "Allow"
      actions   = statement.value.actions
      resources = statement.value.resources
      condition {
        test     = "ForAllValues:StringEquals"
        variable = "route53:VPCs"
        values   = ["VPCId=${var.vpc_id},VPCRegion=${local.region}"]
      }
      condition {
        test     = "Null"
        variable = "route53:VPCs"
        values   = ["false"]
      }
    }
  }

  statement {
    sid       = "NamespaceRecordsOnly"
    effect    = "Allow"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = ["arn:${local.partition}:route53:::hostedzone/*"]
    condition {
      test     = "ForAllValues:StringLike"
      variable = "route53:ChangeResourceRecordSetsNormalizedRecordNames"
      values   = [local.zone_name, "*.${local.zone_name}"]
    }
  }

  statement {
    sid       = "HostedZoneReadAndDelete"
    effect    = "Allow"
    actions   = ["route53:GetHostedZone", "route53:DeleteHostedZone", "route53:ListResourceRecordSets", "route53:GetChange"]
    resources = ["arn:${local.partition}:route53:::hostedzone/*", "arn:${local.partition}:route53:::change/*"]
  }

  statement {
    sid    = "NoResourceType"
    effect = "Allow"
    actions = [
      "route53:ListHostedZonesByName",
      "servicediscovery:ListNamespaces", "servicediscovery:ListServices", "servicediscovery:ListOperations",
      "servicediscovery:ListTagsForResource", "servicediscovery:UntagResource",
      # The caller that creates a state machine or an HTTP API stage with logging needs
      # the vended-log delivery actions (Step Functions guide, documented on "*").
      "logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery", "logs:ListLogDeliveries", "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies", "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }
}

# IAM (named roles only), state bucket, budget read, identity, and the guard denies.
data "aws_iam_policy_document" "control" {
  statement {
    sid       = "ReadProjectIam"
    effect    = "Allow"
    actions   = ["iam:GetRole", "iam:GetRolePolicy", "iam:ListRolePolicies", "iam:ListAttachedRolePolicies", "iam:ListInstanceProfilesForRole", "iam:ListRoleTags", "iam:GetPolicy", "iam:GetPolicyVersion", "iam:ListPolicyVersions", "iam:ListPolicyTags", "iam:GetInstanceProfile"]
    resources = [local.project_roles, local.project_policies, local.project_profiles]
  }

  # The inline policies the data and app stacks own, on these exact roles. Never the EMR
  # service role (its policies are foundation resources) or any guard role.
  statement {
    sid       = "InlinePoliciesOfNamedWorkloadRoles"
    effect    = "Allow"
    actions   = ["iam:PutRolePolicy", "iam:DeleteRolePolicy"]
    resources = [for k in ["runtime-query", "runtime-graph", "runtime-worker", "ecs-execution", "emr-instance", "step-functions", "neptune-loader"] : local.wl[k]]
  }

  statement {
    sid       = "PassTaskRolesToEcsOnly"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [for k in ["runtime-query", "runtime-graph", "runtime-worker", "ecs-execution"] : local.wl[k]]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "PassPipelineRoleToStatesOnly"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [local.wl["step-functions"]]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["states.amazonaws.com"]
    }
  }

  statement {
    sid       = "PassLoaderRoleToNeptuneOnly"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [local.wl["neptune-loader"]]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["rds.amazonaws.com"]
    }
  }

  # AWS sets a service-linked role's trust (to that service); the caller cannot.
  statement {
    sid       = "ServiceLinkedRolesForUsedServices"
    effect    = "Allow"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["arn:${local.partition}:iam::${local.account}:role/aws-service-role/*"]
    condition {
      test     = "StringEquals"
      variable = "iam:AWSServiceName"
      values = [
        "ecs.amazonaws.com",
        "ecs.application-autoscaling.amazonaws.com",
        "rds.amazonaws.com",
        "ops.apigateway.amazonaws.com",
      ]
    }
  }

  statement {
    sid       = "StateBucketList"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = ["arn:${local.partition}:s3:::${var.state_bucket_name}"]
    # "env:/" is the S3 backend's default workspace prefix, which Terraform lists to find
    # workspaces (no workspace is used; the listing only returns key names).
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["scholarchain/${var.environment}/*", "env:/*"]
    }
  }

  statement {
    sid       = "StateObjectsAndLocks"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["arn:${local.partition}:s3:::${var.state_bucket_name}/scholarchain/${var.environment}/*"]
  }

  statement {
    sid    = "BudgetReadOnly"
    effect = "Allow"
    actions = [
      "budgets:ViewBudget", "budgets:DescribeBudgetAction", "budgets:DescribeBudgetActionsForBudget",
      "budgets:ListTagsForResource",
    ]
    resources = [local.budget_arn, "${local.budget_arn}/action/*"]
  }

  # No resource type (Service Authorization Reference, AWS STS).
  statement {
    sid       = "Identity"
    effect    = "Allow"
    actions   = ["sts:GetCallerIdentity"]
    resources = ["*"]
  }
}

# Every explicit Deny of the deployment role (split from "control" in repair round 2 so
# each managed policy stays under 6,144 characters). A Deny on "*" is intentionally broad.
data "aws_iam_policy_document" "guards" {
  # Repair C1: never touch the role, its policies, the boundaries or the budget guards.
  statement {
    sid    = "NeverTouchTheGuards"
    effect = "Deny"
    actions = [
      "iam:Attach*", "iam:Detach*", "iam:Put*", "iam:Delete*", "iam:Update*", "iam:Create*",
      "iam:SetDefaultPolicyVersion", "iam:Tag*", "iam:Untag*", "iam:PassRole",
    ]
    resources = [
      local.role_arn, local.boundary_arn, local.deny_policy_arn, local.budget_role_arn,
      local.deploy_policies, "${local.iam_prefix}:policy/${var.name}-emr-service-boundary",
      "${local.iam_prefix}:policy/${var.name}-pipeline-boundary",
    ]
  }

  # Human decision 1: no role trust change by any route (create, re-trust, delete and
  # re-create), no new role, policy, version, profile or user, and no inline policy or
  # pass outside the named grants above.
  statement {
    sid    = "NeverSetRoleTrustOrCreateIam"
    effect = "Deny"
    actions = [
      "iam:CreateRole", "iam:DeleteRole", "iam:UpdateAssumeRolePolicy", "iam:PutRolePermissionsBoundary",
      "iam:AttachRolePolicy", "iam:CreatePolicy", "iam:CreatePolicyVersion", "iam:SetDefaultPolicyVersion",
      "iam:CreateInstanceProfile", "iam:AddRoleToInstanceProfile", "iam:CreateUser", "iam:CreateAccessKey",
      "iam:CreateLoginProfile", "iam:UpdateLoginProfile",
    ]
    resources = ["*"]
  }

  statement {
    sid           = "InlineOnlyOnNamedRoles"
    effect        = "Deny"
    actions       = ["iam:PutRolePolicy"]
    not_resources = [for k in ["runtime-query", "runtime-graph", "runtime-worker", "ecs-execution", "emr-instance", "step-functions", "neptune-loader"] : local.wl[k]]
  }

  statement {
    sid           = "PassOnlyNamedRoles"
    effect        = "Deny"
    actions       = ["iam:PassRole"]
    not_resources = [for k in ["runtime-query", "runtime-graph", "runtime-worker", "ecs-execution", "step-functions", "neptune-loader"] : local.wl[k]]
  }

  # Human decision 1: EC2 launch only through the EMR service role. The deployment role
  # starts the pipeline (states:StartExecution), whose role asks EMR to launch.
  # Repair round 3 (human, 2026-11-26): every EMR cluster write (create, steps, instance
  # groups and fleets, scaling and auto-termination policies, protection and visibility
  # flags, tags, terminate; Service Authorization Reference, list_emr.html, read
  # 2026-11-26) is the step-functions role's alone. The security configuration actions stay
  # allowed (deploy-runtime): a configuration is read only at RunJobFlow and changes no
  # running cluster.
  statement {
    sid    = "NeverLaunchOrChangeCompute"
    effect = "Deny"
    actions = concat(local.launch_actions, [for a in [
      "RunJobFlow", "Terminate*", "Add*", "Cancel*", "Modify*", "Put*", "Remove*", "Set*", "Start*", "Stop*",
      "Update*", "CreatePersistentAppUI", "CreateStudio*",
      ] : "elasticmapreduce:${a}"], [
      "batch:CreateComputeEnvironment", "ecs:CreateCapacityProvider", "ecs:RunTask", "ecs:StartTask",
    ])
    resources = ["*"]
  }

  # Repair C1: a role trust policy that names this role is enough to assume a role in the
  # same account. It never needs to assume a role: deny it.
  statement {
    sid       = "NeverAssumeAnotherRole"
    effect    = "Deny"
    actions   = ["sts:AssumeRole", "sts:AssumeRoleWithWebIdentity", "sts:AssumeRoleWithSAML"]
    resources = ["*"]
  }

  statement {
    sid    = "NeverChangeTheBudget"
    effect = "Deny"
    actions = [
      "budgets:ModifyBudget", "budgets:DeleteBudget", "budgets:DeleteBudgetAction",
      "budgets:UpdateBudgetAction", "budgets:CreateBudgetAction", "budgets:ExecuteBudgetAction",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "NeverReadSecretValues"
    effect    = "Deny"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue", "ssm:GetParameterHistory"]
    resources = ["*"]
  }

  statement {
    sid       = "NeverRemoveABoundary"
    effect    = "Deny"
    actions   = ["iam:DeleteRolePermissionsBoundary"]
    resources = ["*"]
  }
}

locals {
  deployment_policies = {
    network = data.aws_iam_policy_document.network.json
    data    = data.aws_iam_policy_document.data.json
    runtime = data.aws_iam_policy_document.runtime.json
    edge    = data.aws_iam_policy_document.edge.json
    control = data.aws_iam_policy_document.control.json
    guards  = data.aws_iam_policy_document.guards.json
  }
}

resource "aws_iam_policy" "deployment" {
  for_each = local.deployment_policies

  name        = "${var.name}-deploy-${each.key}"
  description = "ScholarChain ${var.environment} deployment permissions: ${each.key}."
  policy      = each.value
}

resource "aws_iam_role_policy_attachment" "deployment" {
  for_each = aws_iam_policy.deployment

  role       = aws_iam_role.deployment.name
  policy_arn = each.value.arn
}

output "deployment_role_arn" {
  value = aws_iam_role.deployment.arn
}

output "deployment_role_name" {
  value = aws_iam_role.deployment.name
}

output "permissions_boundary_arn" {
  value = aws_iam_policy.boundary.arn
}

output "emr_service_boundary_arn" {
  value = aws_iam_policy.emr_service_boundary.arn
}

output "pipeline_boundary_arn" {
  value = aws_iam_policy.pipeline_boundary.arn
}
