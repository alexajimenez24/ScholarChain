# Non-secret values come from infra/environments/<env>/data.json (-var-file).
# owner is supplied at plan time (TF_VAR_owner), never written into a file.

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
  description = "Git commit (40 hex) the plan was made from. scripts/plan.sh passes it; scripts/deploy.sh checks /scholarchain/<env>/<stack>/deploy/revision after the apply."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.revision))
    error_message = "revision must be a full 40-character git commit SHA."
  }
}

variable "persistent" {
  description = "true turns on deletion protection, PITR, Neptune final snapshot and 7-day backups. dev: false."
  type        = bool
  default     = false
}

variable "enable_neptune" {
  description = "Create the Neptune Serverless cluster (bills per NCU-hour). true only inside a cloud window."
  type        = bool
  default     = false
}

variable "embedding_model_id" {
  description = "Bedrock embedding model (core config EMBEDDING_MODEL_ID). Verified in us-east-2 on 2026-11-26."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "embedding_dimension" {
  type    = number
  default = 256
}

variable "embedding_queue_name_suffix" {
  description = "The compute stack must create the embedding queue as <name>-<suffix>; the IAM grants use that name."
  type        = string
  default     = "embedding-jobs"
}

variable "log_retention_days" {
  type    = number
  default = 30
}
