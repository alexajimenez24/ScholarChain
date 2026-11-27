# Foundation stack: tags, network (two AZs, private subnets, gateway endpoints, optional
# interface endpoints), role-chained security groups, the deployment role and workload
# permissions boundary, every workload role (trust and boundary; their inline policies
# live in the data and app stacks), and the project budget with its deny action.
# Any plan here that changes more than the endpoint/rule/SSM resources is applied by the
# human with administrator credentials (deploy.sh, admin-changes).
# It reads nothing from other stacks and publishes its outputs to SSM under
# /scholarchain/<env>/foundation/.
#
# Phase 13: the NAT gateway moved to the app stack (infra/stacks/app/egress.tf), so the
# app plan shows the services' egress path and the NAT exists only while they run.
#
# Hourly cost while it exists: 0 with no interface endpoints (VPC, subnets, route tables,
# gateway endpoints, security groups, IAM, SSM Standard parameters and the budget are
# free). Price figures and sources are in the phase report, not here.

locals {
  name = "scholarchain-${var.environment}"

  tags = {
    course      = var.course
    project     = "scholarchain"
    environment = var.environment
    owner       = var.owner
    expiration  = var.expiration
    managed-by  = "terraform"
    stack       = "foundation"
  }

  budget_name             = "${local.name}-project-cap"
  budget_action_role_name = "${local.name}-budget-action"
  budget_deny_policy_name = "${local.name}-budget-deny-billable"

  # Every workload role (repair round 2: created here, admin-applied, so the deployment
  # role never sets a trust policy). The boundary and the deployment role's PassRole and
  # PutRolePolicy grants name exactly these roles.
  workload_role_names = {
    for k in ["runtime-query", "runtime-graph", "runtime-worker", "ecs-execution", "emr-service", "emr-instance", "step-functions", "neptune-loader"] :
    k => "${local.name}-${k}"
  }
}

module "network" {
  source = "../../modules/network"

  name = local.name
  cidr = var.vpc_cidr
}

module "security_groups" {
  source = "../../modules/security-groups"

  name                    = local.name
  vpc_id                  = module.network.vpc_id
  s3_prefix_list_id       = module.network.s3_prefix_list_id
  dynamodb_prefix_list_id = module.network.dynamodb_prefix_list_id
}

module "interface_endpoints" {
  source = "../../modules/interface-endpoints"

  name              = local.name
  vpc_id            = module.network.vpc_id
  subnet_id         = module.network.private_subnet_ids[0]
  security_group_id = module.security_groups.ids["endpoints"]
  services          = var.interface_endpoints
}

module "deployment_iam" {
  source = "../../modules/deployment-iam"

  name                    = local.name
  environment             = var.environment
  state_bucket_name       = var.state_bucket_name
  budget_name             = local.budget_name
  budget_action_role_name = local.budget_action_role_name
  budget_deny_policy_name = local.budget_deny_policy_name
  workload_role_names     = local.workload_role_names
  vpc_id                  = module.network.vpc_id
}

module "workload_identities" {
  source = "../../modules/workload-identities"

  name                     = local.name
  role_names               = local.workload_role_names
  permissions_boundary_arn = module.deployment_iam.permissions_boundary_arn
  emr_service_boundary_arn = module.deployment_iam.emr_service_boundary_arn
  pipeline_boundary_arn    = module.deployment_iam.pipeline_boundary_arn
}

# AWSServiceRoleForEMRCleanup (repair round 3, human decision 2026-11-26). EMR creates it
# at the first cluster creation only if the caller may iam:CreateServiceLinkedRole, which
# the step-functions role may not, so the admin-applied foundation creates it when the
# account lacks it. Set create_emr_service_linked_role only after the one-line check in
# infra/README.md ("EMR cleanup service-linked role"); a plan with it is admin-only
# (aws_iam_* is an ADMIN-CHANGE). AWS fixes its name, trust and policy.
resource "aws_iam_service_linked_role" "emr_cleanup" {
  count = var.create_emr_service_linked_role ? 1 : 0

  aws_service_name = "elasticmapreduce.amazonaws.com"
}

module "budget_guard" {
  source = "../../modules/budget-guard"

  name                 = local.name
  budget_name          = local.budget_name
  limit_usd            = var.budget_limit_usd
  alert_email          = var.budget_alert_email
  deployment_role_name = module.deployment_iam.deployment_role_name
  deny_policy_name     = local.budget_deny_policy_name
  action_role_name     = local.budget_action_role_name
}

module "published" {
  source = "../../modules/ssm-parameters"

  prefix = "/scholarchain/${var.environment}/foundation"

  parameters = merge(
    {
      "vpc/id"                        = module.network.vpc_id
      "vpc/cidr"                      = module.network.vpc_cidr
      "network/private-route-table"   = module.network.private_route_table_id
      "network/s3-prefix-list"        = module.network.s3_prefix_list_id
      "network/dynamodb-prefix-list"  = module.network.dynamodb_prefix_list_id
      "iam/deployment-role-arn"       = module.deployment_iam.deployment_role_arn
      "iam/permissions-boundary-arn"  = module.deployment_iam.permissions_boundary_arn
      "iam/emr-instance-profile-arn"  = module.workload_identities.emr_instance_profile_arn
      "iam/emr-instance-profile-name" = module.workload_identities.emr_instance_profile_name
      "budget/name"                   = module.budget_guard.budget_name
      "env/persistent"                = tostring(var.persistent)
      "deploy/revision"               = var.revision
    },
    { for role, id in module.security_groups.ids : "sg/${role}" => id },
    { for role, arn in module.workload_identities.role_arns : "iam/roles/${role}-arn" => arn },
  )

  list_parameters = {
    "vpc/azs"         = module.network.azs
    "subnets/private" = module.network.private_subnet_ids
    "subnets/public"  = module.network.public_subnet_ids
  }
}
