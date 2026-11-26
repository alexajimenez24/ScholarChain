# Non-secret values come from infra/environments/<env>/compute.json (-var-file).
# owner and revision are supplied at plan time (TF_VAR_owner; scripts/plan.sh passes
# -var revision=<git HEAD>), never written into a file.

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

variable "persistent" {
  description = "true keeps ECR images on destroy (force_delete off). dev: false."
  type        = bool
  default     = false
}

variable "enable_emr_endpoint" {
  description = "Create the interface endpoint emr-spark-8.0.0 needs for clusters in private subnets (billed per hour, one AZ). true only inside a cloud window; run-bounded-pipeline.sh refuses to start without it."
  type        = bool
  default     = false
}

variable "emr_release_label" {
  description = "EMR on EC2 release. emr-spark-8.0.0 is the only release with Spark 4 on Scala 2.13 (project/Versions.scala); it also ships hadoop-mapred 3.4.2."
  type        = string
  default     = "emr-spark-8.0.0"
}

variable "emr_instance_type" {
  description = "Instance type of the primary and core nodes (Graviton; EMR and EC2 hourly prices in the phase report)."
  type        = string
  default     = "m6g.xlarge"
}

variable "emr_core_count" {
  description = "Core nodes at cluster start (the addendum: two small core nodes)."
  type        = number
  default     = 2

  validation {
    condition     = var.emr_core_count >= 1 && var.emr_core_count <= 3
    error_message = "emr_core_count must be 1 to 3 (the guard's SCALE_MAX is 4 instances in all)."
  }
}

variable "emr_max_instances" {
  description = "Managed scaling ceiling, primary node included."
  type        = number
  default     = 4

  validation {
    condition     = var.emr_max_instances >= 2 && var.emr_max_instances <= 4
    error_message = "emr_max_instances must be 2 to 4 (SCALE_MAX in docs/agents.md section 6)."
  }
}

variable "emr_ebs_gb" {
  description = "gp3 volume per node, GB."
  type        = number
  default     = 32
}

variable "emr_idle_timeout_seconds" {
  description = "Auto-termination after this much idle time (addendum: 30 minutes)."
  type        = number
  default     = 1800

  validation {
    condition     = var.emr_idle_timeout_seconds >= 60 && var.emr_idle_timeout_seconds <= 1800
    error_message = "emr_idle_timeout_seconds must be 60 to 1800."
  }
}

variable "pipeline_timeout_seconds" {
  description = "Hard limit of one pipeline execution; the cluster is terminated on timeout by the idle policy."
  type        = number
  default     = 14400
}

variable "step_timeout_seconds" {
  description = "Hard limit of one EMR step inside the pipeline."
  type        = number
  default     = 5400
}

variable "embedding_queue_visibility_seconds" {
  description = "Must exceed the worker request timeout (30 s) times its retries."
  type        = number
  default     = 180
}

variable "embedding_max_receives" {
  description = "Deliveries before a message moves to the dead-letter queue."
  type        = number
  default     = 5
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "ecr_keep_images" {
  description = "Images kept per repository by the lifecycle rule."
  type        = number
  default     = 10
}
