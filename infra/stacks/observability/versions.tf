terraform {
  required_version = ">= 1.17.2, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.74.0"
    }
  }

  # Partial S3 backend; key passed per stack as
  #   -backend-config=key=scholarchain/<env>/observability/terraform.tfstate
  # S3-native locking, no lock table.
  backend "s3" {
    region       = "us-east-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}
