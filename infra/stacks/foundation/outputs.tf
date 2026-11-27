# Outputs are for the human reading a plan or apply. Other stacks never read them: they
# read the SSM parameters listed in ssm_parameter_names.

output "vpc_id" {
  value = module.network.vpc_id
}

output "private_subnet_ids" {
  value = module.network.private_subnet_ids
}

output "security_group_ids" {
  value = module.security_groups.ids
}

output "deployment_role_arn" {
  value = module.deployment_iam.deployment_role_arn
}

output "permissions_boundary_arn" {
  value = module.deployment_iam.permissions_boundary_arn
}

output "ssm_parameter_names" {
  value = module.published.names
}
