# Non-secret values come from infra/environments/<env>/observability.json (-var-file).
# owner and revision are supplied at plan time (TF_VAR_owner; plan.sh passes revision).

variable "region" {
  type    = string
  default = "us-east-2"

  validation {
    condition     = var.region == "us-east-2"
    error_message = "ScholarChain runs only in us-east-2."
  }
}

variable "environment" {
  type = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,7}$", var.environment))
    error_message = "environment: 2 to 8 lowercase letters or digits, starting with a letter."
  }
}

variable "course" {
  type = string
}

variable "owner" {
  type = string

  validation {
    condition     = can(regex("^[A-Za-z0-9 ._:/=+-]{1,128}$", var.owner))
    error_message = "owner must be a plain tag value: letters, digits, space and ._:/=+- only (no email, no placeholder brackets)."
  }
}

variable "expiration" {
  type = string

  validation {
    condition     = can(regex("^20[0-9]{2}-[01][0-9]-[0-3][0-9]$", var.expiration))
    error_message = "expiration must be YYYY-MM-DD."
  }
}

variable "revision" {
  description = "Git commit (40 hex) the plan was made from (scripts/plan.sh)."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.revision))
    error_message = "revision must be a full 40-character git commit SHA."
  }
}

variable "alarm_action_arns" {
  description = "Optional SNS topic ARNs notified by every alarm. Default none: alarms are read in the console and by incident-responder (no extra subscription or email in files)."
  type        = list(string)
  default     = []
}

variable "neptune_max_ncu" {
  description = "Must equal the data stack's Neptune max NCU (addendum: 2.5); the capacity alarm fires when Neptune sits at it."
  type        = number
  default     = 2.5
}

variable "api_latency_p90_ms" {
  type    = number
  default = 5000
}

variable "embedding_tokens_per_hour_alarm" {
  description = "Bedrock input tokens per hour (all models) that raise the cost alarm."
  type        = number
  default     = 20000000
}
