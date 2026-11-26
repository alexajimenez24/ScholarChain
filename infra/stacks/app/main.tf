# App stack: the ECS cluster, the task execution role, one log group and one Fargate
# service per image (query-service, graph-service, embedding-worker), Cloud Map service
# discovery, and the edge (edge.tf: Cognito user pool, API Gateway HTTP API with a JWT
# authorizer and a VPC Link to Cloud Map). It reads lower stacks only through SSM and
# publishes its own parameters under /scholarchain/<env>/app/.
#
# Seven work-order service roles, three images (justification): query, graph retrieval,
# vector retrieval, ranking, answer synthesis and verification run inside one request
# path (rag, called by queryService) with no queue between them, so splitting them would
# add network hops, six more JWT checks and five more always-on tasks without any
# independent scaling need. The two parts with a different load shape keep their own
# image and scaling policy: graph reads (graphService, Neptune bound) and the embedding
# worker (queue bound). Mapping:
#   query            -> query-service     (POST /v1/query, jobs, explain, evidence)
#   graph retrieval  -> graph-service     (GET /v1/graph/nodes/...), and in-process in
#                       query-service for the RAG graph context (rag -> storage templates)
#   vector retrieval -> query-service     (rag retrieval over S3 Vectors)
#   ranking          -> query-service     (rag fusion and ranking)
#   answer synthesis -> query-service     (rag synthesis; LLM_ENABLED=false by default)
#   verification     -> query-service     (rag claim verification)
#   workers          -> embedding-worker  (SQS drain into S3 Vectors)
#
# Hourly cost while it exists: three Fargate tasks at the minimum (1 each), up to nine at
# the maximum; the hosted zone of the private DNS namespace per month; API Gateway,
# Cognito M2M tokens and Cloud Map lookups per use. Figures in the phase report.

locals {
  name = "scholarchain-${var.environment}"

  tags = {
    course      = var.course
    project     = "scholarchain"
    environment = var.environment
    owner       = var.owner
    expiration  = var.expiration
    managed-by  = "terraform"
    stack       = "app"
  }

  foundation = "/scholarchain/${var.environment}/foundation"
  data       = "/scholarchain/${var.environment}/data"
  compute    = "/scholarchain/${var.environment}/compute"

  services = toset(["query-service", "graph-service", "embedding-worker"])
  http     = toset(["query-service", "graph-service"])

  tables = ["run-ledger", "entity-link-cache", "query-session", "evidence-bundle", "artifact-registry", "researcher-metric"]
}

# ----- Inputs from lower stacks (SSM only) --------------------------------------------
data "aws_ssm_parameter" "in" {
  for_each = toset(concat(
    [
      "${local.foundation}/vpc/id",
      "${local.foundation}/subnets/private",
      "${local.foundation}/sg/vpc-link",
      "${local.foundation}/sg/query-service",
      "${local.foundation}/sg/graph-service",
      "${local.foundation}/sg/embedding-worker",
      "${local.foundation}/iam/roles/ecs-execution-arn",
      "${local.data}/buckets/raw",
      "${local.data}/buckets/curated",
      "${local.data}/buckets/graph",
      "${local.data}/buckets/rag",
      "${local.data}/vectors/bucket-name",
      "${local.data}/vectors/dimension",
      "${local.data}/models/embedding-model-id",
      "${local.data}/neptune/endpoint",
      "${local.data}/neptune/port",
      "${local.data}/roles/runtime-query-arn",
      "${local.data}/roles/runtime-graph-arn",
      "${local.data}/roles/runtime-worker-arn",
      "${local.compute}/sqs/embedding-queue-url",
    ],
    [for t in local.tables : "${local.data}/dynamodb/${t}"],
    [for s in local.services : "${local.compute}/ecr/${s}/repository-url"],
    [for s in local.services : "${local.compute}/ecr/${s}/name"],
  ))

  name = each.value
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  p = {
    for k, v in data.aws_ssm_parameter.in :
    trimprefix(trimprefix(trimprefix(k, "${local.foundation}/"), "${local.data}/"), "${local.compute}/") => v.insecure_value
  }

  account            = data.aws_caller_identity.current.account_id
  partition          = data.aws_partition.current.partition
  private_subnet_ids = split(",", local.p["subnets/private"])

  task_role = {
    query-service    = local.p["roles/runtime-query-arn"]
    graph-service    = local.p["roles/runtime-graph-arn"]
    embedding-worker = local.p["roles/runtime-worker-arn"]
  }

  # Non-secret configuration only (core config placeholders, modules/core/MODULE.md).
  common_env = merge(
    {
      SCHOLARCHAIN_MODE     = "aws"
      SCHOLARCHAIN_ENV      = var.environment
      SCHOLARCHAIN_REVISION = var.image_tag
      AWS_REGION            = var.region
      RAW_BUCKET            = local.p["buckets/raw"]
      CURATED_BUCKET        = local.p["buckets/curated"]
      GRAPH_BUCKET          = local.p["buckets/graph"]
      RAG_BUCKET            = local.p["buckets/rag"]
      VECTOR_BUCKET         = local.p["vectors/bucket-name"]
      EMBEDDING_DIMENSION   = local.p["vectors/dimension"]
      EMBEDDING_MODEL_ID    = local.p["models/embedding-model-id"]
      EMBEDDING_QUEUE_URL   = local.p["sqs/embedding-queue-url"]
      NEPTUNE_ENDPOINT      = local.p["neptune/endpoint"]
      NEPTUNE_PORT          = local.p["neptune/port"]
      COGNITO_ISSUER        = local.issuer
      COGNITO_JWKS_URL      = "${local.issuer}/.well-known/jwks.json"
      COGNITO_AUDIENCE      = aws_cognito_user_pool_client.service.id
      LLM_ENABLED           = tostring(var.llm_enabled)
      SYNTHESIS_MODEL_ID    = var.synthesis_model_id
      QUERY_SERVICE_PORT    = tostring(var.service_port)
      GRAPH_SERVICE_PORT    = tostring(var.service_port)
      WORKER_SERVICE_PORT   = tostring(var.service_port)
    },
    { for t in local.tables : "DDB_${upper(replace(t, "-", "_"))}" => local.p["dynamodb/${t}"] },
  )

  desired = var.park_services ? 0 : var.min_tasks
  minimum = var.park_services ? 0 : var.min_tasks
}

# ----- Task execution role policy (pull images, write logs; nothing else) -------------
# The role, its ecs-tasks trust and the boundary are foundation resources (repair round
# 2: the deployment role never sets a trust policy); this stack puts only this policy.
locals {
  execution_role_arn  = local.p["iam/roles/ecs-execution-arn"]
  execution_role_name = element(split("/", local.execution_role_arn), length(split("/", local.execution_role_arn)) - 1)
}

data "aws_iam_policy_document" "execution" {
  # Resource "*" by necessity: the Service Authorization Reference lists no resource type
  # for ecr:GetAuthorizationToken (infra/README.md, "IAM scope").
  statement {
    sid       = "EcrToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "PullProjectImages"
    effect    = "Allow"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
    resources = [for s in local.services : "arn:${local.partition}:ecr:${var.region}:${local.account}:repository/${local.p["ecr/${s}/name"]}"]
  }

  statement {
    sid       = "WriteServiceLogs"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [for g in aws_cloudwatch_log_group.service : "${g.arn}:*"]
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "least-privilege"
  role   = local.execution_role_name
  policy = data.aws_iam_policy_document.execution.json
}

# Synthesis model grant (repair round 2, human decision 4): Claude Haiku 4.5 through the
# US geographic cross-Region inference profile us.anthropic.claude-haiku-4-5-20251001-v1:0
# (Bedrock model card "Claude Haiku 4.5", read 2026-11-26: on bedrock-runtime the model
# has no in-Region or bare-ID on-demand access in us-east-2, only the geo and global
# profiles; the US profile from us-east-2 routes to us-east-1, us-east-2 and us-west-2).
# The policy is the documented shape (Bedrock User Guide, "Geographic cross-Region
# inference", IAM policy requirements, read 2026-11-26): the profile ARN in the source
# Region, plus the foundation model in every destination Region, only when invoked
# through that profile (bedrock:InferenceProfileArn). Created only while llm_enabled is
# true, so no unused invoke right exists while the model path is off.
locals {
  synthesis_base_model  = trimprefix(var.synthesis_model_id, "us.")
  synthesis_profile_arn = "arn:${local.partition}:bedrock:${var.region}:${local.account}:inference-profile/${var.synthesis_model_id}"
}

data "aws_iam_policy_document" "synthesis" {
  count = var.llm_enabled && var.synthesis_model_id != "" ? 1 : 0

  statement {
    sid       = "InvokeThroughTheUsProfile"
    effect    = "Allow"
    actions   = ["bedrock:InvokeModel"]
    resources = [local.synthesis_profile_arn]
  }

  statement {
    sid       = "ModelInTheProfileDestinations"
    effect    = "Allow"
    actions   = ["bedrock:InvokeModel"]
    resources = [for r in var.synthesis_destination_regions : "arn:${local.partition}:bedrock:${r}::foundation-model/${local.synthesis_base_model}"]
    condition {
      test     = "StringEquals"
      variable = "bedrock:InferenceProfileArn"
      values   = [local.synthesis_profile_arn]
    }
  }
}

resource "aws_iam_role_policy" "synthesis" {
  count = length(data.aws_iam_policy_document.synthesis)

  name   = "synthesis-model"
  role   = basename(local.task_role["query-service"])
  policy = data.aws_iam_policy_document.synthesis[0].json
}

# ----- Logs, cluster, service discovery, services -------------------------------------
resource "aws_cloudwatch_log_group" "service" {
  for_each = local.services

  name              = "/scholarchain/${var.environment}/services/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_cluster" "this" {
  name = local.name

  # Container Insights off: it bills custom metrics; the dashboard uses the free AWS/ECS
  # metrics and the services' own EMF metrics.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = "${var.environment}.scholarchain.internal"
  description = "ScholarChain ${var.environment} internal service discovery"
  vpc         = local.p["vpc/id"]
}

resource "aws_service_discovery_service" "http" {
  for_each = local.http

  name          = each.key
  force_destroy = !var.persistent

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this.id
    routing_policy = "MULTIVALUE"

    # SRV carries the port, which the API Gateway Cloud Map integration needs.
    dns_records {
      type = "SRV"
      ttl  = 10
    }
  }

  health_check_custom_config {}
}

module "service" {
  source   = "../../modules/ecs-service"
  for_each = local.services

  name                  = each.key
  prefix                = local.name
  cluster_arn           = aws_ecs_cluster.this.arn
  cluster_name          = aws_ecs_cluster.this.name
  image                 = "${local.p["ecr/${each.key}/repository-url"]}:${var.image_tag}"
  cpu                   = var.task_size[each.key].cpu
  memory                = var.task_size[each.key].memory
  port                  = contains(local.http, each.key) ? var.service_port : null
  environment           = local.common_env
  task_role_arn         = local.task_role[each.key]
  execution_role_arn    = local.execution_role_arn
  subnet_ids            = local.private_subnet_ids
  security_group_id     = local.p["sg/${each.key}"]
  log_group_name        = aws_cloudwatch_log_group.service[each.key].name
  region                = var.region
  discovery_service_arn = contains(local.http, each.key) ? aws_service_discovery_service.http[each.key].arn : null
  desired_count         = local.desired
  min_tasks             = local.minimum
  max_tasks             = var.max_tasks

  depends_on = [aws_iam_role_policy.execution]
}

# ----- Outputs for later stacks (SSM only) --------------------------------------------
module "published" {
  source = "../../modules/ssm-parameters"

  prefix = "/scholarchain/${var.environment}/app"

  parameters = merge(
    { for s, m in module.service : "ecs/service/${s}" => m.service_name },
    { for s, m in module.service : "ecs/task-family/${s}" => m.task_family },
    {
      "deploy/revision"      = var.revision
      "deploy/image-tag"     = var.image_tag
      "ecs/cluster-name"     = aws_ecs_cluster.this.name
      "ecs/parked"           = tostring(var.park_services)
      "api/id"               = aws_apigatewayv2_api.this.id
      "api/endpoint"         = aws_apigatewayv2_api.this.api_endpoint
      "api/access-log-group" = aws_cloudwatch_log_group.api.name
      "auth/user-pool-id"    = aws_cognito_user_pool.this.id
      "auth/issuer"          = local.issuer
      "auth/client-id"       = aws_cognito_user_pool_client.service.id
      "auth/token-url"       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${var.region}.amazoncognito.com/oauth2/token"
      "auth/scopes"          = join(" ", local.scope_names)
      "discovery/namespace"  = aws_service_discovery_private_dns_namespace.this.name
    },
  )
}
