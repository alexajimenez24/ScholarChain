# Security groups chained by service role. No ingress rule names a CIDR range: every
# ingress source is another group. Groups are created without inline rules, so Terraform
# removes the default allow-all egress, and each permitted flow is one rule resource.
#
# Flows:
#   API Gateway VPC Link  -> query-service, graph-service   tcp service_port
#   query-service         -> Neptune                        tcp neptune_port
#   graph-service         -> Neptune                        tcp neptune_port
#   EMR primary and core  -> Neptune                        tcp neptune_port (bulk loader start, checks)
#   query, graph, worker  -> AWS APIs                       tcp 443 (see the egress note below)
#   workloads             -> interface endpoints            tcp 443 (only used if endpoints exist)
#   EMR primary and core  -> EMR primary and core           all traffic (egress, see below)
#   EMR primary and core  -> S3 gateway endpoint            all tcp to the S3 prefix list
#   EMR primary and core  -> DynamoDB gateway endpoint      tcp 443 to the DynamoDB prefix list
#   EMR primary and core  -> EMR service endpoint           tcp 443 (compute stack, with the endpoint)
# The embedding worker accepts no inbound traffic; it only drains SQS.
# EMR groups are EMR-managed groups. EMR adds only the missing INBOUND rules between them
# at cluster start; it adds no egress rule to custom managed groups (Amazon EMR Management
# Guide, "Working with Amazon EMR-managed security groups", read 2026-11-26:
# https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-man-sec-groups.html). So,
# repair M1, Terraform owns the egress that page lists as the minimum for private
# subnets (S3 prefix list, all traffic to the primary and core groups, 443 to the
# service access group for emr-spark-8.0.0) plus DynamoDB, the interface endpoints and
# Neptune. They set revoke_rules_on_delete so the EMR-added inbound rules go on destroy.
# No EMR egress reaches 0.0.0.0/0: the pipeline steps call only S3, Neptune and the EMR
# service (scripts/emr/scholarchain-step.sh, ER adjudicator off).
#
# Egress note: tcp 443 to 0.0.0.0/0 for the three services is needed because Bedrock,
# S3 Vectors, SQS, ECR, CloudWatch Logs and the Cognito JWKS URL are public AWS endpoints
# reached through the app stack's NAT gateway (S3 and DynamoDB go through the gateway
# endpoints; SQS through the compute stack's endpoint while it exists). It is egress
# only and HTTPS only.
# Phase 13: the pipeline's loader steps run on the EMR primary node and call S3,
# DynamoDB (gateway endpoints), Neptune (rule above) and SQS through the compute stack's
# SQS interface endpoint (the emr_to_endpoints rule below), so EMR still has no
# 0.0.0.0/0 egress.

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

variable "service_port" {
  description = "Container port of the HTTP services."
  type        = number
  default     = 8080
}

variable "neptune_port" {
  type    = number
  default = 8182
}

variable "s3_prefix_list_id" {
  description = "Prefix list of the S3 gateway endpoint (EMR egress)."
  type        = string
}

variable "dynamodb_prefix_list_id" {
  description = "Prefix list of the DynamoDB gateway endpoint (EMR egress)."
  type        = string
}

locals {
  emr_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }

  groups = {
    vpc-link           = { description = "API Gateway HTTP API VPC Link ENIs", emr = false }
    query-service      = { description = "query-service ECS tasks", emr = false }
    graph-service      = { description = "graph-service ECS tasks", emr = false }
    embedding-worker   = { description = "embedding-worker ECS tasks (no inbound)", emr = false }
    neptune            = { description = "Neptune Serverless cluster", emr = false }
    endpoints          = { description = "Optional interface VPC endpoints", emr = false }
    emr-primary        = { description = "EMR-managed group for the primary node", emr = true }
    emr-core           = { description = "EMR-managed group for core and task nodes", emr = true }
    emr-service-access = { description = "EMR-managed service access group (private subnet)", emr = true }
  }

  https_clients = ["query-service", "graph-service", "embedding-worker"]
  neptune_clients = {
    query-service = "query-service"
    graph-service = "graph-service"
    emr-primary   = "emr-primary"
    emr-core      = "emr-core"
  }
  endpoint_clients = ["query-service", "graph-service", "embedding-worker", "emr-primary", "emr-core"]

  emr_nodes = ["emr-primary", "emr-core"]
  # Every (source, destination) pair of the two node groups, itself included.
  emr_pairs = {
    for pair in setproduct(local.emr_nodes, local.emr_nodes) : "${pair[0]}-to-${pair[1]}" => {
      from = pair[0]
      to   = pair[1]
    }
  }
}

resource "aws_security_group" "this" {
  for_each = local.groups

  name                   = "${var.name}-${each.key}"
  description            = each.value.description
  vpc_id                 = var.vpc_id
  revoke_rules_on_delete = each.value.emr

  tags = merge({ Name = "${var.name}-${each.key}" }, each.value.emr ? local.emr_tag : {})
}

# API Gateway VPC Link -> HTTP services.
resource "aws_vpc_security_group_egress_rule" "vpc_link_to_service" {
  for_each = toset(["query-service", "graph-service"])

  security_group_id            = aws_security_group.this["vpc-link"].id
  description                  = "VPC Link to ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = var.service_port
  to_port                      = var.service_port
  referenced_security_group_id = aws_security_group.this[each.key].id
}

resource "aws_vpc_security_group_ingress_rule" "service_from_vpc_link" {
  for_each = toset(["query-service", "graph-service"])

  security_group_id            = aws_security_group.this[each.key].id
  description                  = "From the API Gateway VPC Link only"
  ip_protocol                  = "tcp"
  from_port                    = var.service_port
  to_port                      = var.service_port
  referenced_security_group_id = aws_security_group.this["vpc-link"].id
}

# Neptune clients.
resource "aws_vpc_security_group_egress_rule" "client_to_neptune" {
  for_each = local.neptune_clients

  security_group_id            = aws_security_group.this[each.value].id
  description                  = "${each.key} to Neptune"
  ip_protocol                  = "tcp"
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  referenced_security_group_id = aws_security_group.this["neptune"].id
}

resource "aws_vpc_security_group_ingress_rule" "neptune_from_client" {
  for_each = local.neptune_clients

  security_group_id            = aws_security_group.this["neptune"].id
  description                  = "From ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  referenced_security_group_id = aws_security_group.this[each.value].id
}

# HTTPS egress of the three services to AWS APIs (see the egress note at the top).
resource "aws_vpc_security_group_egress_rule" "service_https" {
  for_each = toset(local.https_clients)

  security_group_id = aws_security_group.this[each.key].id
  description       = "HTTPS to AWS service endpoints and the Cognito JWKS URL"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# ----- EMR node egress (repair M1; see the header) -------------------------------------
resource "aws_vpc_security_group_egress_rule" "emr_intra_cluster" {
  for_each = local.emr_pairs

  security_group_id            = aws_security_group.this[each.value.from].id
  description                  = "All traffic from ${each.value.from} to ${each.value.to} (EMR minimum egress)"
  ip_protocol                  = "-1"
  referenced_security_group_id = aws_security_group.this[each.value.to].id
}

resource "aws_vpc_security_group_egress_rule" "emr_to_s3" {
  for_each = toset(local.emr_nodes)

  security_group_id = aws_security_group.this[each.key].id
  description       = "All TCP to the S3 gateway endpoint prefix list (EMR minimum egress)"
  ip_protocol       = "tcp"
  from_port         = 0
  to_port           = 65535
  prefix_list_id    = var.s3_prefix_list_id
}

resource "aws_vpc_security_group_egress_rule" "emr_to_dynamodb" {
  for_each = toset(local.emr_nodes)

  security_group_id = aws_security_group.this[each.key].id
  description       = "HTTPS to the DynamoDB gateway endpoint prefix list"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = var.dynamodb_prefix_list_id
}

# Matches the endpoints_from_client ingress below (only used if interface endpoints exist).
resource "aws_vpc_security_group_egress_rule" "emr_to_endpoints" {
  for_each = toset(local.emr_nodes)

  security_group_id            = aws_security_group.this[each.key].id
  description                  = "HTTPS to the interface endpoints"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = aws_security_group.this["endpoints"].id
}

# Interface endpoints accept HTTPS from workload groups only.
resource "aws_vpc_security_group_ingress_rule" "endpoints_from_client" {
  for_each = toset(local.endpoint_clients)

  security_group_id            = aws_security_group.this["endpoints"].id
  description                  = "HTTPS from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = aws_security_group.this[each.key].id
}

output "ids" {
  description = "Security group ID by role."
  value       = { for k, g in aws_security_group.this : k => g.id }
}
