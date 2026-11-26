# Data stack: data-lake buckets, the six DynamoDB tables, Neptune Serverless with its
# loader policy, the S3 vector bucket with the five indexes, and the inline policies of
# the workload roles, scoped to these resources (the roles themselves, with their trust
# and boundary, are foundation resources). It reads the foundation stack only through
# SSM parameters under
# /scholarchain/<env>/foundation/ and publishes its own under /scholarchain/<env>/data/.
#
# Hourly cost while it exists: Neptune Serverless (at least min_ncu = 1 NCU per hour)
# only while enable_neptune = true. Everything else bills per request or per GB-month
# (S3, DynamoDB on-demand, S3 Vectors storage and queries) and is near zero at the
# bounded scale. Price figures and sources are in the phase report.
#
# Optional OpenSearch: NOT deployed. No hot or hybrid tier using it was implemented or
# evaluated (modules/storage/MODULE.md: "Optional OpenSearch was not implemented").

locals {
  name = "scholarchain-${var.environment}"

  tags = {
    course      = var.course
    project     = "scholarchain"
    environment = var.environment
    owner       = var.owner
    expiration  = var.expiration
    managed-by  = "terraform"
    stack       = "data"
  }

  foundation = "/scholarchain/${var.environment}/foundation"

  embedding_queue_name = "${local.name}-${var.embedding_queue_name_suffix}"
}

# ----- Inputs from the foundation stack (SSM only) ------------------------------------
data "aws_ssm_parameter" "private_subnets" {
  name = "${local.foundation}/subnets/private"
}

data "aws_ssm_parameter" "neptune_sg" {
  name = "${local.foundation}/sg/neptune"
}

# The workload roles are created by the foundation stack (repair round 2); this stack
# only puts their inline policies, so it reads the role ARNs and the instance profile.
data "aws_ssm_parameter" "role" {
  for_each = toset(["runtime-query", "runtime-graph", "runtime-worker", "emr-service", "emr-instance", "step-functions", "neptune-loader"])

  name = "${local.foundation}/iam/roles/${each.value}-arn"
}

data "aws_ssm_parameter" "emr_instance_profile" {
  for_each = toset(["name", "arn"])

  name = "${local.foundation}/iam/emr-instance-profile-${each.value}"
}

locals {
  private_subnet_ids = split(",", data.aws_ssm_parameter.private_subnets.insecure_value)
  neptune_sg_id      = data.aws_ssm_parameter.neptune_sg.insecure_value

  role_arns = { for k, p in data.aws_ssm_parameter.role : k => p.insecure_value }
}

# ----- Resources ----------------------------------------------------------------------
module "data_lake" {
  source = "../../modules/data-lake"

  name               = local.name
  log_retention_days = var.log_retention_days
}

module "tables" {
  source = "../../modules/dynamodb-tables"

  name       = local.name
  persistent = var.persistent
}

module "neptune" {
  source = "../../modules/neptune-serverless"

  name              = local.name
  enabled           = var.enable_neptune
  persistent        = var.persistent
  subnet_ids        = local.private_subnet_ids
  security_group_id = local.neptune_sg_id
  graph_bucket_arn  = module.data_lake.bucket_arns["graph"]
  loader_role_arn   = local.role_arns["neptune-loader"]
}

module "vectors" {
  source = "../../modules/s3-vectors"

  name       = local.name
  persistent = var.persistent
  dimension  = var.embedding_dimension
}

module "roles" {
  source = "../../modules/workload-roles"

  name                        = local.name
  role_arns                   = { for k, v in local.role_arns : k => v if k != "neptune-loader" }
  bucket_arns                 = module.data_lake.bucket_arns
  table_arns                  = module.tables.table_arns
  vector_index_arns           = values(module.vectors.index_arns)
  neptune_enabled             = var.enable_neptune
  neptune_cluster_resource_id = module.neptune.cluster_resource_id
  embedding_model_id          = var.embedding_model_id
  embedding_queue_name        = local.embedding_queue_name
}

# ----- Outputs for later stacks (SSM only) --------------------------------------------
# SSM String values cannot be empty, so a disabled Neptune publishes "disabled".
locals {
  neptune_or_disabled = {
    endpoint            = var.enable_neptune ? module.neptune.endpoint : "disabled"
    reader-endpoint     = var.enable_neptune ? module.neptune.reader_endpoint : "disabled"
    cluster-resource-id = var.enable_neptune ? module.neptune.cluster_resource_id : "disabled"
  }
}

module "published" {
  source = "../../modules/ssm-parameters"

  prefix = "/scholarchain/${var.environment}/data"

  parameters = merge(
    { for zone, b in module.data_lake.bucket_names : "buckets/${zone}" => b },
    { for t, n in module.tables.table_names : "dynamodb/${t}" => n },
    { for k, n in module.vectors.index_names : "vectors/index/${k}" => n },
    { for k, v in local.neptune_or_disabled : "neptune/${k}" => v },
    { for r, arn in module.roles.role_arns : "roles/${r}-arn" => arn },
    {
      "reports/location"                = "s3://${module.data_lake.bucket_names["curated"]}/reports/"
      "neptune/enabled"                 = tostring(var.enable_neptune)
      "neptune/port"                    = tostring(module.neptune.port)
      "neptune/loader-role-arn"         = module.neptune.loader_role_arn
      "vectors/bucket-name"             = module.vectors.vector_bucket_name
      "vectors/bucket-arn"              = module.vectors.vector_bucket_arn
      "vectors/dimension"               = tostring(var.embedding_dimension)
      "models/embedding-model-id"       = var.embedding_model_id
      "contract/embedding-queue-name"   = local.embedding_queue_name
      "roles/emr-instance-profile-arn"  = data.aws_ssm_parameter.emr_instance_profile["arn"].insecure_value
      "roles/emr-instance-profile-name" = data.aws_ssm_parameter.emr_instance_profile["name"].insecure_value
      "deploy/revision"                 = var.revision
    },
  )
}
