# Non-secret values come from infra/environments/<env>/foundation.json (-var-file).
# Personal or account-bound values (owner, alert email, state bucket name) are NOT in any
# file: the human supplies them at plan time as TF_VAR_owner, TF_VAR_budget_alert_email
# and TF_VAR_state_bucket_name.

variable "region" {
  type    = string
  default = "us-east-2"

  validation {
    condition     = var.region == "us-east-2"
    error_message = "ScholarChain runs only in us-east-2."
  }
}

variable "environment" {
  description = "Environment name, for example dev."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,7}$", var.environment))
    error_message = "environment: 2 to 8 lowercase letters or digits, starting with a letter."
  }
}

variable "course" {
  description = "Tag value: course identifier."
  type        = string
}

variable "owner" {
  description = "Tag value: who owns these resources. Supplied at plan time; no email or account ID."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9 ._:/=+-]{1,128}$", var.owner))
    error_message = "owner must be a plain tag value: letters, digits, space and ._:/=+- only (no email, no placeholder brackets)."
  }
}

variable "expiration" {
  description = "Tag value: date (YYYY-MM-DD) after which every resource may be deleted."
  type        = string

  validation {
    condition     = can(regex("^20[0-9]{2}-[01][0-9]-[0-3][0-9]$", var.expiration))
    error_message = "expiration must be YYYY-MM-DD."
  }
}

variable "revision" {
  description = "Git commit (40 hex) the plan was made from. scripts/plan.sh passes it; scripts/deploy.sh checks /scholarchain/<env>/<stack>/deploy/revision after the apply."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.revision))
    error_message = "revision must be a full 40-character git commit SHA."
  }
}

variable "persistent" {
  description = "true for a persistent environment (deletion protection, longer backups). dev: false."
  type        = bool
  default     = false
}

variable "enable_nat" {
  description = "One NAT gateway in the first AZ. true only inside a cloud window."
  type        = bool
  default     = false
}

variable "interface_endpoints" {
  description = "Optional interface endpoints (each billed per hour, one AZ each). Default none: the NAT gateway covers egress."
  type        = set(string)
  default     = []
}

variable "create_emr_service_linked_role" {
  description = "Create AWSServiceRoleForEMRCleanup. true only when the account lacks it (check in infra/README.md, \"EMR cleanup service-linked role\"); creating an existing one fails."
  type        = bool
  default     = false
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "budget_limit_usd" {
  description = "Whole-project cap from AGENTS.md."
  type        = number
  default     = 25
}

variable "budget_alert_email" {
  description = "Budget alert address. Supplied at plan time (TF_VAR_budget_alert_email); never in a file."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[^@<>\\s]+@[^@<>\\s]+\\.[^@<>\\s]+$", var.budget_alert_email))
    error_message = "budget_alert_email must be an email address."
  }
}

variable "state_bucket_name" {
  description = "Terraform state bucket (created by the human). Same value as in the backend file."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid S3 bucket name (replace the placeholder)."
  }
}
