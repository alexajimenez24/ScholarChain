# Publishes a stack's outputs to SSM Parameter Store under one prefix
# (/scholarchain/<env>/<stack>/). Later stacks read these parameters, never this stack's
# state file. Values are plain identifiers, names and ARNs, never secrets: every parameter
# is a Standard-tier String or StringList (free), so no SecureString or KMS key is needed.

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.74.0"
    }
  }
}

variable "prefix" {
  description = "Parameter path prefix without a trailing slash, for example /scholarchain/dev/foundation."
  type        = string

  validation {
    condition     = can(regex("^/scholarchain/[a-z0-9-]+/[a-z0-9-]+$", var.prefix))
    error_message = "The prefix must look like /scholarchain/<env>/<stack>."
  }
}

variable "parameters" {
  description = "Map of relative parameter name to string value. Keys must be known at plan time."
  type        = map(string)
  default     = {}
}

variable "list_parameters" {
  description = "Map of relative parameter name to a list of strings, stored as StringList."
  type        = map(list(string))
  default     = {}
}

resource "aws_ssm_parameter" "string" {
  for_each = var.parameters

  name           = "${var.prefix}/${each.key}"
  description    = "Published by Terraform for later stacks; not a secret."
  type           = "String"
  tier           = "Standard"
  insecure_value = each.value
}

resource "aws_ssm_parameter" "list" {
  for_each = var.list_parameters

  name           = "${var.prefix}/${each.key}"
  description    = "Published by Terraform for later stacks; not a secret."
  type           = "StringList"
  tier           = "Standard"
  insecure_value = join(",", each.value)
}

output "names" {
  description = "Every parameter name this module writes."
  value = concat(
    [for p in aws_ssm_parameter.string : p.name],
    [for p in aws_ssm_parameter.list : p.name],
  )
}
