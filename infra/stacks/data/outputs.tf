# For the human reading a plan or apply; later stacks read SSM, not these outputs.

output "bucket_names" {
  value = module.data_lake.bucket_names
}

output "table_names" {
  value = module.tables.table_names
}

output "neptune_enabled" {
  value = var.enable_neptune
}

output "neptune_endpoint" {
  value = module.neptune.endpoint
}

output "vector_bucket_name" {
  value = module.vectors.vector_bucket_name
}

output "vector_index_names" {
  value = module.vectors.index_names
}

output "role_arns" {
  value = module.roles.role_arns
}

output "ssm_parameter_names" {
  value = module.published.names
}
