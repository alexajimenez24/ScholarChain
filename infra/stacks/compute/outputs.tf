# For the human reading a plan or apply; later stacks read SSM, not these outputs.

output "state_machine_arn" {
  value = aws_sfn_state_machine.pipeline.arn
}

output "embedding_queue_url" {
  value = aws_sqs_queue.embedding.url
}

output "ecr_repository_urls" {
  value = { for k, r in aws_ecr_repository.image : k => r.repository_url }
}

output "emr_endpoint_enabled" {
  value = var.enable_emr_endpoint
}

output "ssm_parameter_names" {
  value = module.published.names
}
