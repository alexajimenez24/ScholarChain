# Non-secret values come from infra/environments/<env>/app.json (-var-file).
# owner, revision and image_tag are supplied at plan time (TF_VAR_owner; scripts/plan.sh
# passes -var revision=<git HEAD> -var image_tag=<git HEAD>), never written into a file.

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

variable "image_tag" {
  description = "Git SHA tag of the three images in ECR (immutable). deploy.sh pushes them before the apply."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full 40-character git commit SHA (images are tagged by Git SHA only)."
  }
}

variable "persistent" {
  description = "true turns on Cognito deletion protection. dev: false."
  type        = bool
  default     = false
}

variable "park_services" {
  description = "true sets every service to 0 tasks (minimum 0) after a run; false keeps the bounded minimum."
  type        = bool
  default     = false
}

variable "min_tasks" {
  description = "Minimum tasks per service (addendum: 1)."
  type        = number
  default     = 1
}

variable "max_tasks" {
  description = "Maximum tasks per service (addendum: 3)."
  type        = number
  default     = 3

  validation {
    condition     = var.max_tasks >= 1 && var.max_tasks <= 3
    error_message = "max_tasks must be 1 to 3."
  }
}

variable "task_size" {
  description = "Fargate CPU units and memory MiB per service."
  type        = map(object({ cpu = number, memory = number }))
  default = {
    query-service    = { cpu = 512, memory = 1024 }
    graph-service    = { cpu = 512, memory = 1024 }
    embedding-worker = { cpu = 512, memory = 1024 }
  }
}

variable "service_port" {
  description = "Container port of the HTTP services (config QUERY_SERVICE_PORT and the others)."
  type        = number
  default     = 8080
}

variable "llm_enabled" {
  description = "Model-written answers (config LLM_ENABLED). Off: the services have no model synthesizer wired yet (modules/services/MODULE.md); turning it on also creates the synthesis grant."
  type        = bool
  default     = false
}

variable "synthesis_model_id" {
  description = "Bedrock synthesis model (config SYNTHESIS_MODEL_ID): a US geographic inference-profile ID (us.anthropic.*), because the grant in main.tf is the geo-profile shape. Human decision 2026-11-26: us.anthropic.claude-haiku-4-5-20251001-v1:0."
  type        = string
  default     = ""

  validation {
    condition     = var.synthesis_model_id == "" || can(regex("^us\\.anthropic\\.[a-z0-9.:-]+$", var.synthesis_model_id))
    error_message = "synthesis_model_id must be empty or a US geographic inference-profile ID of an Anthropic model (us.anthropic.<model>); other forms need a different grant."
  }
}

variable "synthesis_destination_regions" {
  description = "Destination Regions of the US geo profile from source us-east-2 (Bedrock model card, Claude Haiku 4.5, read 2026-11-26)."
  type        = list(string)
  default     = ["us-east-1", "us-east-2", "us-west-2"]

  validation {
    condition     = length(var.synthesis_destination_regions) > 0 && alltrue([for r in var.synthesis_destination_regions : can(regex("^us-[a-z]+-[0-9]$", r))])
    error_message = "synthesis_destination_regions must be US Regions."
  }
}

variable "api_throttle_rate" {
  description = "Steady-state requests per second for the whole API (cost and abuse bound)."
  type        = number
  default     = 10
}

variable "api_throttle_burst" {
  type    = number
  default = 20
}

variable "access_token_minutes" {
  type    = number
  default = 60
}

variable "log_retention_days" {
  type    = number
  default = 14
}
