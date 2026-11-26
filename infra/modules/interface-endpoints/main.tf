# Optional interface VPC endpoints, each in ONE subnet (one AZ) to halve the hourly cost.
# Every endpoint bills per hour while it exists, so the default set is empty: the NAT
# gateway covers egress. Add a service here only with a written justification (for
# example "bedrock-runtime" if the NAT gateway is removed). Callers in the second AZ reach
# the endpoint across AZs, which is acceptable for a course environment.

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

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "The single private subnet that hosts every interface endpoint."
  type        = string
}

variable "security_group_id" {
  type = string
}

variable "services" {
  description = "Interface endpoint service suffixes, for example [\"bedrock-runtime\"]."
  type        = set(string)
  default     = []
}

data "aws_region" "current" {}

resource "aws_vpc_endpoint" "this" {
  for_each = var.services

  vpc_id              = var.vpc_id
  service_name        = "com.amazonaws.${data.aws_region.current.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [var.subnet_id]
  security_group_ids  = [var.security_group_id]
  private_dns_enabled = true

  tags = { Name = "${var.name}-${each.key}" }
}

output "ids" {
  value = { for k, e in aws_vpc_endpoint.this : k => e.id }
}
