# For the human reading a plan or apply; later stacks read SSM, not these outputs.

output "api_endpoint" {
  value = aws_apigatewayv2_api.this.api_endpoint
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "services" {
  value = { for s, m in module.service : s => m.task_definition_arn }
}

output "user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "client_id" {
  value = aws_cognito_user_pool_client.service.id
}

output "ssm_parameter_names" {
  value = module.published.names
}
