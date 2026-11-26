terraform {
  # use_lockfile (S3-native state locking) is GA since 1.11; the toolchain pins 1.17.2.
  required_version = ">= 1.17.2, < 2.0.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Exact pin; .terraform.lock.hcl records the checksums. 6.74.0 has the
      # aws_s3vectors_* resources.
      version = "6.74.0"
    }
  }

  # Partial S3 backend. The bucket comes from infra/backend/<env>.s3.tfbackend (no
  # secrets); the key is passed per stack as
  #   -backend-config=key=scholarchain/<env>/foundation/terraform.tfstate
  # Locking is S3-native (use_lockfile): no DynamoDB lock table.
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
