# Least-privilege inline policies of the workload roles, scoped to the actual data
# resources of the data stack. The roles themselves (trust policy, permissions boundary,
# the EMR managed policy and instance profile) are created by the foundation stack
# (modules/workload-identities, admin-applied; repair round 2), so the deployment role
# that applies this stack only puts and deletes these inline policies on the exact role
# names below and can never set a trust policy.
#
#   runtime-query   query-service task role: Neptune READ ONLY, S3 Vectors query, rag
#                   text reads, query-session and evidence-bundle writes, embedding calls
#   runtime-graph   graph-service task role: Neptune READ ONLY (the read-only runtime role
#                   for Neptune), metric and evidence reads
#   runtime-worker  embedding-worker task role: drains the embedding queue, writes
#                   vectors and rag text objects, records the run ledger
#   emr-instance    EC2 instance profile of EMR nodes: data-lake reads and writes,
#                   pipeline tables, Neptune bulk-loader start and status, enqueue
#   step-functions  the pipeline state machine: EMR cluster and step control, pass only
#                   the two EMR roles, enqueue embedding jobs
# Not here: emr-service (AWS managed policy plus its pass grant, foundation), the ECS
# task execution role policy and the synthesis model grant (app stack), the Neptune
# loader policy (modules/neptune-serverless).
# Names the compute stack must use are published to SSM (embedding queue name).

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

variable "role_arns" {
  description = "Role key => ARN of the foundation-created role (runtime-query, runtime-graph, runtime-worker, emr-service, emr-instance, step-functions)."
  type        = map(string)
}

variable "bucket_arns" {
  description = "Zone => bucket ARN (raw, curated, graph, rag, artifacts, logs)."
  type        = map(string)
}

variable "table_arns" {
  description = "Logical table name => table ARN."
  type        = map(string)
}

variable "vector_index_arns" {
  type = list(string)
}

variable "neptune_enabled" {
  type = bool
}

variable "neptune_cluster_resource_id" {
  description = "Empty when Neptune is disabled; the Neptune statements are then omitted."
  type        = string
}

variable "embedding_model_id" {
  type = string
}

variable "embedding_queue_name" {
  type = string
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  region    = data.aws_region.current.region

  neptune_data_arn = "arn:${local.partition}:neptune-db:${local.region}:${local.account}:${var.neptune_cluster_resource_id}/*"
  neptune_read     = ["neptune-db:ReadDataViaQuery", "neptune-db:GetQueryStatus", "neptune-db:GetEngineStatus"]
  neptune_load     = ["neptune-db:StartLoaderJob", "neptune-db:GetLoaderJobStatus", "neptune-db:ListLoaderJobs", "neptune-db:CancelLoaderJob"]

  embedding_model_arn = "arn:${local.partition}:bedrock:${local.region}::foundation-model/${var.embedding_model_id}"
  queue_arn           = "arn:${local.partition}:sqs:${local.region}:${local.account}:${var.embedding_queue_name}"

  ddb_read  = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query", "dynamodb:DescribeTable"]
  ddb_write = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem"]

  objects = { for z, arn in var.bucket_arns : z => "${arn}/*" }

  # Role names from the foundation-created ARNs (the last path element).
  role_name = { for k, arn in var.role_arns : k => element(split("/", arn), length(split("/", arn)) - 1) }
}

# ----- query-service ------------------------------------------------------------------
data "aws_iam_policy_document" "query" {
  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneReadOnly"
      effect    = "Allow"
      actions   = local.neptune_read
      resources = [local.neptune_data_arn]
    }
  }

  statement {
    sid       = "VectorQuery"
    effect    = "Allow"
    actions   = ["s3vectors:QueryVectors", "s3vectors:GetVectors"]
    resources = var.vector_index_arns
  }

  statement {
    sid       = "ReadSemanticContextAndGraphEvidence"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = [local.objects["rag"], local.objects["graph"]]
  }

  statement {
    sid       = "ReadAllPipelineTables"
    effect    = "Allow"
    actions   = local.ddb_read
    resources = values(var.table_arns)
  }

  statement {
    sid       = "WriteSessionsAndEvidenceBundles"
    effect    = "Allow"
    actions   = local.ddb_write
    resources = [var.table_arns["query-session"], var.table_arns["evidence-bundle"]]
  }

  statement {
    sid       = "EmbedQuestions"
    effect    = "Allow"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.embedding_model_arn]
  }
}

# ----- graph-service (read-only Neptune runtime role) ---------------------------------
data "aws_iam_policy_document" "graph" {
  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneReadOnly"
      effect    = "Allow"
      actions   = local.neptune_read
      resources = [local.neptune_data_arn]
    }
  }

  statement {
    sid     = "ReadMetricsAndEvidence"
    effect  = "Allow"
    actions = local.ddb_read
    resources = [
      var.table_arns["researcher-metric"],
      var.table_arns["evidence-bundle"],
      var.table_arns["artifact-registry"],
    ]
  }
}

# ----- embedding-worker ---------------------------------------------------------------
data "aws_iam_policy_document" "worker" {
  statement {
    sid    = "DrainEmbeddingQueue"
    effect = "Allow"
    actions = [
      "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes", "sqs:GetQueueUrl",
    ]
    resources = [local.queue_arn]
  }

  statement {
    sid       = "WriteVectors"
    effect    = "Allow"
    actions   = ["s3vectors:PutVectors", "s3vectors:GetVectors"]
    resources = var.vector_index_arns
  }

  statement {
    sid       = "ReadSourceRows"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = [local.objects["graph"], local.objects["rag"]]
  }

  statement {
    sid       = "WriteSemanticContext"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = [local.objects["rag"]]
  }

  statement {
    sid       = "RunLedger"
    effect    = "Allow"
    actions   = concat(local.ddb_read, local.ddb_write)
    resources = [var.table_arns["run-ledger"]]
  }

  statement {
    sid       = "ReadArtifactRegistry"
    effect    = "Allow"
    actions   = local.ddb_read
    resources = [var.table_arns["artifact-registry"]]
  }

  statement {
    sid       = "EmbedChunks"
    effect    = "Allow"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.embedding_model_arn]
  }
}

# ----- EMR instance profile -----------------------------------------------------------
data "aws_iam_policy_document" "emr_instance" {
  statement {
    sid    = "ListDataLake"
    effect = "Allow"
    actions = [
      "s3:ListBucket", "s3:GetBucketLocation", "s3:ListBucketMultipartUploads",
    ]
    resources = values(var.bucket_arns)
  }

  statement {
    sid       = "ReadDataLake"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = values(local.objects)
  }

  statement {
    sid    = "WritePipelineZones"
    effect = "Allow"
    actions = [
      "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts",
    ]
    # raw is read-only to the pipeline; artifacts are written by deploy.sh only.
    resources = [local.objects["curated"], local.objects["graph"], local.objects["rag"]]
  }

  statement {
    sid       = "WriteClusterLogs"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = [local.objects["logs"]]
  }

  statement {
    sid    = "AwsOwnedEmrBuckets"
    effect = "Allow"
    # Buckets EMR 7.x needs from a private subnet (EMR Management Guide, "Sample policies
    # for private subnets that access Amazon S3", read 2026-11-26).
    actions = ["s3:GetObject"]
    resources = [
      "arn:${local.partition}:s3:::al2023-repos-${local.region}-de612dc2/*",
      "arn:${local.partition}:s3:::repo.${local.region}.emr.amazonaws.com/*",
      "arn:${local.partition}:s3:::aws157-instance-data-0-prod-${local.region}/*",
      "arn:${local.partition}:s3:::aws157-instance-data-1-prod-${local.region}/*",
    ]
  }

  statement {
    sid       = "AwsOwnedEmrLogBucket"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["arn:${local.partition}:s3:::aws157-logs-prod-${local.region}/*"]
  }

  statement {
    sid     = "PipelineTables"
    effect  = "Allow"
    actions = concat(local.ddb_read, local.ddb_write)
    resources = [
      var.table_arns["run-ledger"],
      var.table_arns["entity-link-cache"],
      var.table_arns["researcher-metric"],
      var.table_arns["artifact-registry"],
      var.table_arns["evidence-bundle"],
    ]
  }

  dynamic "statement" {
    for_each = var.neptune_enabled ? [1] : []
    content {
      sid       = "NeptuneBulkLoadAndCheck"
      effect    = "Allow"
      actions   = concat(local.neptune_load, local.neptune_read)
      resources = [local.neptune_data_arn]
    }
  }

  statement {
    sid       = "EnqueueEmbeddingJobs"
    effect    = "Allow"
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
    resources = [local.queue_arn]
  }
}

# ----- Step Functions pipeline --------------------------------------------------------
data "aws_iam_policy_document" "sfn" {
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
    sid    = "ControlProjectClusters"
    effect = "Allow"
    actions = [
      "elasticmapreduce:DescribeCluster", "elasticmapreduce:TerminateJobFlows",
      "elasticmapreduce:AddJobFlowSteps", "elasticmapreduce:DescribeStep",
      "elasticmapreduce:CancelSteps", "elasticmapreduce:ListSteps",
      "elasticmapreduce:PutManagedScalingPolicy", "elasticmapreduce:PutAutoTerminationPolicy",
      "elasticmapreduce:AddTags",
    ]
    resources = ["arn:${local.partition}:elasticmapreduce:${local.region}:${local.account}:cluster/*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/project"
      values   = ["scholarchain"]
    }
  }

  statement {
    sid       = "PassOnlyTheEmrRoles"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [var.role_arns["emr-service"]]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["elasticmapreduce.amazonaws.com"]
    }
  }

  # RunJobFlow passes the instance profile's role to EC2 (JobFlowRole).
  statement {
    sid       = "PassOnlyTheInstanceRoleToEc2"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = [var.role_arns["emr-instance"]]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }

  statement {
    sid     = "SyncIntegrationRules"
    effect  = "Allow"
    actions = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
    resources = [
      "arn:${local.partition}:events:${local.region}:${local.account}:rule/StepFunctionsGetEventForEMRRunJobFlowRule",
      "arn:${local.partition}:events:${local.region}:${local.account}:rule/StepFunctionsGetEventForEMRAddJobFlowStepsRule",
      "arn:${local.partition}:events:${local.region}:${local.account}:rule/StepFunctionsGetEventForEMRTerminateJobFlowsRule",
    ]
  }

  statement {
    sid    = "ExecutionLogDelivery"
    effect = "Allow"
    # Resource "*" by necessity (infra/README.md, "IAM scope"): the Step Functions guide
    # documents these vended-log actions on "*" and the Service Authorization Reference
    # lists no resource type for them.
    actions = [
      "logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery", "logs:ListLogDeliveries", "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies", "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "EnqueueEmbeddingJobs"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [local.queue_arn]
  }
}

# ----- Inline policies on the foundation-created roles --------------------------------
locals {
  inline = {
    runtime-query  = data.aws_iam_policy_document.query.json
    runtime-graph  = data.aws_iam_policy_document.graph.json
    runtime-worker = data.aws_iam_policy_document.worker.json
    emr-instance   = data.aws_iam_policy_document.emr_instance.json
    step-functions = data.aws_iam_policy_document.sfn.json
  }
}

resource "aws_iam_role_policy" "this" {
  for_each = local.inline

  name   = "least-privilege"
  role   = local.role_name[each.key]
  policy = each.value
}

output "role_arns" {
  value = var.role_arns
}
