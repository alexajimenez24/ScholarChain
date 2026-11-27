# Compute stack: the embedding queue with its dead-letter queue, the three ECR
# repositories, the EMR security configuration, the EMR service endpoint (toggle), and
# the Step Functions pipeline (pipeline.tf) that starts one transient EMR cluster per run.
# It reads the foundation and data stacks only through SSM and publishes its own
# parameters under /scholarchain/<env>/compute/.
#
# There is no long-running EMR cluster: the state machine creates the cluster, runs the
# steps and terminates it on success and on every failure path; the cluster also carries
# an idle auto-termination policy (30 minutes) in case an execution is stopped by hand.
#
# Why ECR is here and not in the app stack: deploy.sh pushes the Git-SHA-tagged images
# BEFORE it applies the app plan, so the repositories must exist one stack earlier.
#
# Hourly cost while it exists: 0 with enable_emr_endpoint = false (SQS, ECR, Step
# Functions and the security configuration bill per use or per GB-month). With it: the
# two pipeline interface endpoints (EMR service and SQS), each in one AZ, per hour. A
# pipeline run adds the EMR nodes for its duration. Figures and sources are in the phase
# report.

locals {
  name = "scholarchain-${var.environment}"

  tags = {
    course      = var.course
    project     = "scholarchain"
    environment = var.environment
    owner       = var.owner
    expiration  = var.expiration
    managed-by  = "terraform"
    stack       = "compute"
  }

  foundation = "/scholarchain/${var.environment}/foundation"
  data       = "/scholarchain/${var.environment}/data"

  images = toset(["query-service", "graph-service", "embedding-worker"])
}

# ----- Inputs from lower stacks (SSM only) --------------------------------------------
data "aws_ssm_parameter" "in" {
  for_each = toset([
    "${local.foundation}/vpc/id",
    "${local.foundation}/subnets/private",
    "${local.foundation}/sg/emr-primary",
    "${local.foundation}/sg/emr-core",
    "${local.foundation}/sg/emr-service-access",
    "${local.foundation}/sg/endpoints",
    "${local.data}/buckets/artifacts",
    "${local.data}/buckets/curated",
    "${local.data}/buckets/graph",
    "${local.data}/buckets/logs",
    "${local.data}/roles/emr-service-arn",
    "${local.data}/roles/step-functions-arn",
    "${local.data}/roles/emr-instance-profile-name",
    "${local.data}/contract/embedding-queue-name",
    "${local.data}/neptune/enabled",
    "${local.data}/neptune/endpoint",
    "${local.data}/neptune/port",
    "${local.data}/neptune/loader-role-arn",
    "${local.data}/dynamodb/run-ledger",
    "${local.data}/dynamodb/researcher-metric",
    "${local.data}/vectors/dimension",
  ])

  name = each.value
}

locals {
  p = { for k, v in data.aws_ssm_parameter.in : trimprefix(trimprefix(k, "${local.foundation}/"), "${local.data}/") => v.insecure_value }

  private_subnet_ids   = split(",", local.p["subnets/private"])
  embedding_queue_name = local.p["contract/embedding-queue-name"]
}

# ----- Embedding queue and dead-letter queue ------------------------------------------
resource "aws_sqs_queue" "embedding_dlq" {
  name                      = "${local.embedding_queue_name}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "embedding" {
  name                       = local.embedding_queue_name
  visibility_timeout_seconds = var.embedding_queue_visibility_seconds
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20
  max_message_size           = 262144
  sqs_managed_sse_enabled    = true

  # A malformed, oversized or loader-rejected message is never acknowledged by the worker
  # (docs/api.md), so it lands here after embedding_max_receives deliveries.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.embedding_dlq.arn
    maxReceiveCount     = var.embedding_max_receives
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "embedding_dlq" {
  queue_url = aws_sqs_queue.embedding_dlq.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.embedding.arn]
  })
}

data "aws_iam_policy_document" "tls_only" {
  for_each = {
    embedding     = aws_sqs_queue.embedding.arn
    embedding_dlq = aws_sqs_queue.embedding_dlq.arn
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value]
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
}

resource "aws_sqs_queue_policy" "tls_only" {
  for_each = {
    embedding     = aws_sqs_queue.embedding.id
    embedding_dlq = aws_sqs_queue.embedding_dlq.id
  }

  queue_url = each.value
  policy    = data.aws_iam_policy_document.tls_only[each.key].json
}

# ----- ECR: one repository per image, immutable Git SHA tags, scan on push -------------
resource "aws_ecr_repository" "image" {
  for_each = local.images

  name                 = "scholarchain-${var.environment}-${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = !var.persistent

  image_scanning_configuration {
    scan_on_push = true
  }

  # AWS-managed encryption (AES256); no cross-service grant needs a customer key.
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "image" {
  for_each = aws_ecr_repository.image

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the newest ${var.ecr_keep_images} images (rollback window)"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.ecr_keep_images
      }
      action = { type = "expire" }
    }]
  })
}

# ----- EMR ----------------------------------------------------------------------------
# IMDSv2 only on every node; S3 objects written by EMR use SSE-S3 (the buckets also
# enforce SSE-S3 by default). No customer KMS key (addendum: AWS-managed keys).
resource "aws_emr_security_configuration" "this" {
  name = "${local.name}-emr"

  configuration = jsonencode({
    EncryptionConfiguration = {
      EnableInTransitEncryption = false
      EnableAtRestEncryption    = true
      AtRestEncryptionConfiguration = {
        S3EncryptionConfiguration = { EncryptionMode = "SSE-S3" }
      }
    }
    InstanceMetadataServiceConfiguration = {
      MinimumInstanceMetadataServiceVersion = 2
      HttpPutResponseHopLimit               = 1
    }
  })
}

# emr-spark-8.0.0 clusters in private subnets talk to the EMR service through an
# interface endpoint (EMR Release Guide, emr-spark-8.0.0, "VPC endpoint for EMR cluster
# communication", read 2026-11-26). Creating it here, in one AZ, keeps it visible to
# Terraform and cleanup.sh and keeps ec2:CreateVpcEndpoint out of the EMR service role.
resource "aws_vpc_endpoint" "emr" {
  count = var.enable_emr_endpoint ? 1 : 0

  vpc_id              = local.p["vpc/id"]
  service_name        = "aws.api.${var.region}.emr-service-cell01"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [local.private_subnet_ids[0]]
  security_group_ids  = [local.p["sg/emr-service-access"]]
  private_dns_enabled = true

  tags = {
    Name                                       = "${local.name}-emr-service"
    "for-use-with-amazon-emr-managed-policies" = "true"
  }
}

# SQS interface endpoint (Phase 13), on the same window toggle as the EMR endpoint: the
# pipeline's enqueue-embeddings step runs on the EMR primary node, whose groups have no
# 0.0.0.0/0 egress, so SQS is reached through this endpoint (emr_to_endpoints and
# endpoints_from_client rules, foundation) instead of widening EMR egress to the NAT.
# $0.01/h in one AZ. Private DNS makes the services' SQS calls use it too while it exists
# (their groups allow 443 to the endpoints group). Its policy admits only the two project
# queues, so the endpoint cannot reach any other queue.
data "aws_iam_policy_document" "sqs_endpoint" {
  statement {
    sid    = "OnlyTheEmbeddingQueues"
    effect = "Allow"
    actions = [
      "sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl",
    ]
    resources = [aws_sqs_queue.embedding.arn, aws_sqs_queue.embedding_dlq.arn]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_vpc_endpoint" "sqs" {
  count = var.enable_emr_endpoint ? 1 : 0

  vpc_id              = local.p["vpc/id"]
  service_name        = "com.amazonaws.${var.region}.sqs"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [local.private_subnet_ids[0]]
  security_group_ids  = [local.p["sg/endpoints"]]
  private_dns_enabled = true
  policy              = data.aws_iam_policy_document.sqs_endpoint.json

  tags = { Name = "${local.name}-sqs" }
}

# The service access group (on the endpoint) must accept 443 from the cluster instances
# for emr-spark-8.0.0, and the primary and core groups need egress 443 to it (EMR adds
# neither rule to custom managed groups; Amazon EMR Management Guide, "Working with
# Amazon EMR-managed security groups", read 2026-11-26). Repair M1 adds the core node
# rules. Chained by group, no CIDR.
locals {
  emr_endpoint_clients = { primary = "sg/emr-primary", core = "sg/emr-core" }
}

resource "aws_vpc_security_group_ingress_rule" "emr_endpoint_from_node" {
  for_each = local.emr_endpoint_clients

  security_group_id            = local.p["sg/emr-service-access"]
  description                  = "HTTPS from the EMR ${each.key} nodes to the EMR service endpoint"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = local.p[each.value]
}

resource "aws_vpc_security_group_egress_rule" "emr_node_to_endpoint" {
  for_each = local.emr_endpoint_clients

  security_group_id            = local.p[each.value]
  description                  = "HTTPS to the EMR service endpoint"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = local.p["sg/emr-service-access"]
}

# ----- Outputs for later stacks (SSM only) --------------------------------------------
module "published" {
  source = "../../modules/ssm-parameters"

  prefix = "/scholarchain/${var.environment}/compute"

  parameters = merge(
    { for k, r in aws_ecr_repository.image : "ecr/${k}/repository-url" => r.repository_url },
    { for k, r in aws_ecr_repository.image : "ecr/${k}/name" => r.name },
    {
      "deploy/revision"             = var.revision
      "sqs/embedding-queue-url"     = aws_sqs_queue.embedding.url
      "sqs/embedding-queue-arn"     = aws_sqs_queue.embedding.arn
      "sqs/embedding-queue-name"    = aws_sqs_queue.embedding.name
      "sqs/embedding-dlq-name"      = aws_sqs_queue.embedding_dlq.name
      "sqs/embedding-dlq-url"       = aws_sqs_queue.embedding_dlq.url
      "pipeline/state-machine-arn"  = aws_sfn_state_machine.pipeline.arn
      "pipeline/state-machine-name" = aws_sfn_state_machine.pipeline.name
      "pipeline/artifacts-uri"      = "s3://${local.p["buckets/artifacts"]}/pipeline/"
      "pipeline/neptune-endpoint"   = local.p["neptune/endpoint"]
      "pipeline/log-group"          = aws_cloudwatch_log_group.pipeline.name
      "emr/endpoint-enabled"        = tostring(var.enable_emr_endpoint)
      "sqs/endpoint-enabled"        = tostring(var.enable_emr_endpoint)
      "emr/release-label"           = var.emr_release_label
      "emr/security-configuration"  = aws_emr_security_configuration.this.name
      "emr/max-instances"           = tostring(var.emr_max_instances)
      "emr/idle-timeout-seconds"    = tostring(var.emr_idle_timeout_seconds)
    },
  )
}
