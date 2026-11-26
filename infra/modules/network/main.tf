# Network: one VPC across two Availability Zones.
# - Private subnets (one per AZ) hold every workload: ECS tasks, EMR, Neptune and the
#   API Gateway VPC Link ENIs.
# - Public subnets (one per AZ) hold only the optional NAT gateway. No workload, load
#   balancer or instance gets a public address (map_public_ip_on_launch = false).
# - Gateway endpoints for S3 and DynamoDB (free) keep that traffic off the NAT, and the
#   Neptune bulk loader requires the S3 gateway endpoint.
# - Egress: at most one NAT gateway in the first AZ, created only while enable_nat is
#   true. Interface endpoints are optional (see the interface-endpoints module); the
#   default set is empty, because one NAT gateway covers every service the workloads call
#   for less per hour than the six or more interface endpoints that would otherwise be
#   needed (decision recorded in infra/README.md).
# - The default security group of the VPC is stripped of all rules.

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.74.0"
    }
  }
}

variable "name" {
  description = "Resource name prefix, for example scholarchain-dev."
  type        = string
}

variable "cidr" {
  description = "VPC CIDR block."
  type        = string
  default     = "10.42.0.0/16"
}

variable "enable_nat" {
  description = "Create one NAT gateway (billed per hour and per GB) in the first AZ. Off between cloud windows."
  type        = bool
  default     = false
}

locals {
  az_count = 2
  azs      = slice(data.aws_availability_zones.this.names, 0, local.az_count)
}

data "aws_availability_zones" "this" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_region" "current" {}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.name}-vpc" }
}

resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  # No ingress or egress blocks: every rule of the default group is removed.
  tags = { Name = "${var.name}-default-unused" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-igw" }
}

resource "aws_subnet" "public" {
  count = local.az_count

  vpc_id                  = aws_vpc.this.id
  cidr_block              = cidrsubnet(var.cidr, 8, count.index)
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name}-public-${local.azs[count.index]}"
    tier = "public"
  }
}

resource "aws_subnet" "private" {
  count = local.az_count

  vpc_id            = aws_vpc.this.id
  cidr_block        = cidrsubnet(var.cidr, 6, 4 + count.index)
  availability_zone = local.azs[count.index]

  tags = {
    Name = "${var.name}-private-${local.azs[count.index]}"
    tier = "private"
    # Required by the AWS managed policy AmazonEMRServicePolicy_v2 on the EMR service role.
    "for-use-with-amazon-emr-managed-policies" = "true"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = local.az_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count = var.enable_nat ? 1 : 0

  domain = "vpc"

  tags = { Name = "${var.name}-nat" }
}

resource "aws_nat_gateway" "this" {
  count = var.enable_nat ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  subnet_id     = aws_subnet.public[0].id

  tags = { Name = "${var.name}-nat" }

  depends_on = [aws_internet_gateway.this]
}

# One private route table for both private subnets, because there is at most one NAT.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-private" }
}

resource "aws_route" "private_nat" {
  count = var.enable_nat ? 1 : 0

  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[0].id
}

resource "aws_route_table_association" "private" {
  count = local.az_count

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

resource "aws_vpc_endpoint" "gateway" {
  for_each = toset(["s3", "dynamodb"])

  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.${each.key}"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = { Name = "${var.name}-${each.key}-gateway" }
}

output "vpc_id" {
  value = aws_vpc.this.id
}

output "vpc_cidr" {
  value = aws_vpc.this.cidr_block
}

output "azs" {
  value = local.azs
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "private_route_table_id" {
  value = aws_route_table.private.id
}

output "s3_prefix_list_id" {
  value = aws_vpc_endpoint.gateway["s3"].prefix_list_id
}

output "dynamodb_prefix_list_id" {
  value = aws_vpc_endpoint.gateway["dynamodb"].prefix_list_id
}
