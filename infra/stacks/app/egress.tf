# Egress of the ECS tasks (Phase 13; the Phase 12 gate asked for it in the app plan).
#
# What the tasks call outside the VPC: ECR (image manifests; the layers come from S3,
# through the free S3 gateway endpoint), CloudWatch Logs (awslogs driver), Bedrock
# Runtime (Titan embeddings; synthesis once LLM_ENABLED is true), S3 Vectors, SQS and the
# Cognito JWKS URL (cognito-idp). S3 and DynamoDB already go through the free gateway
# endpoints of the foundation stack.
#
# Choice: ONE zonal NAT gateway in the first public subnet, plus the 0.0.0.0/0 route of
# the shared private route table, created only while the services are not parked.
# Why it is the cheapest working path (us-east-2 public price files, read in Phase 12):
#   - NAT: $0.045/h plus $0.005/h for its public IPv4 address = $0.050/h, plus
#     $0.045/GB processed; it serves every endpoint above, including the Cognito JWKS URL.
#   - Interface endpoints at $0.01/h per AZ each: ecr.api, ecr.dkr, logs, bedrock-runtime,
#     s3vectors, sqs and cognito-idp are 7 endpoints = $0.070/h in ONE AZ (0.140 in two),
#     before the per-GB charge, and whether the JWKS path is served through the
#     cognito-idp endpoint is UNVERIFIED.
# Security rules: no new rule and no widening. The services' groups already allow only
# tcp 443 egress (foundation, security-groups module), no inbound path from the internet
# exists (the NAT is outbound only, no task has a public IP), and the workload boundary
# still denies every workload role ec2:CreateNatGateway and ec2:AllocateAddress (only
# the deployment role creates this NAT, through the signed app plan).
# The route lives here, not in foundation, so the app plan shows the whole path and
# parking the services (park_services = true) also removes the NAT and its hourly cost.
# The EMR pipeline does not use the NAT: its groups have no 0.0.0.0/0 egress, and it
# reaches the EMR service and SQS through the compute stack's interface endpoints.

locals {
  nat_enabled       = !var.park_services
  public_subnet_ids = split(",", data.aws_ssm_parameter.egress["subnets/public"].insecure_value)
}

data "aws_ssm_parameter" "egress" {
  for_each = toset(["subnets/public", "network/private-route-table"])

  name = "${local.foundation}/${each.key}"
}

resource "aws_eip" "nat" {
  count = local.nat_enabled ? 1 : 0

  domain = "vpc"

  tags = { Name = "${local.name}-nat" }
}

resource "aws_nat_gateway" "this" {
  count = local.nat_enabled ? 1 : 0

  availability_mode = "zonal"
  connectivity_type = "public"
  allocation_id     = aws_eip.nat[0].id
  subnet_id         = local.public_subnet_ids[0]

  tags = { Name = "${local.name}-nat" }
}

resource "aws_route" "private_default" {
  count = local.nat_enabled ? 1 : 0

  route_table_id         = data.aws_ssm_parameter.egress["network/private-route-table"].insecure_value
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[0].id
}
