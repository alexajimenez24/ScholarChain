# One ECS Fargate service: task definition, service, optional Cloud Map registration and
# CPU target-tracking autoscaling between min_tasks and max_tasks. Used three times by the
# app stack (query-service, graph-service, embedding-worker), so each image scales on its
# own.
#
# Container hardening: non-root user (the image's 10001), read-only root file system with
# a task-local /tmp volume, no public IP, no secrets in the environment (configuration is
# non-secret identifiers only; any secret would be referenced by ARN in `secrets`).

terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.74.0"
    }
  }
}

variable "name" {
  description = "Service and container name, for example query-service."
  type        = string
}

variable "prefix" {
  description = "Resource name prefix, for example scholarchain-dev."
  type        = string
}

variable "cluster_arn" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "image" {
  description = "Full image reference <repository-url>:<git sha>."
  type        = string
}

variable "cpu" {
  type = number
}

variable "memory" {
  type = number
}

variable "port" {
  description = "Container port; null for a service without inbound traffic."
  type        = number
  default     = null
}

variable "environment" {
  description = "Non-secret environment variables."
  type        = map(string)
}

variable "task_role_arn" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "log_group_name" {
  type = string
}

variable "region" {
  type = string
}

variable "discovery_service_arn" {
  description = "Cloud Map service ARN (SRV records) or null."
  type        = string
  default     = null
}

variable "desired_count" {
  type = number
}

variable "min_tasks" {
  type = number
}

variable "max_tasks" {
  type = number

  validation {
    condition     = var.max_tasks >= 0 && var.max_tasks <= 3
    error_message = "max_tasks must be 0 to 3 (addendum: each service minimum 1, maximum 3)."
  }
}

variable "cpu_target_percent" {
  type    = number
  default = 60
}

locals {
  port_mappings = var.port == null ? [] : [{
    name          = "http"
    containerPort = var.port
    protocol      = "tcp"
  }]
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.prefix}-${var.name}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  task_role_arn            = var.task_role_arn
  execution_role_arn       = var.execution_role_arn

  # Keep earlier revisions ACTIVE when a new one replaces them: scripts/rollback.sh can
  # point the service back at one (an INACTIVE revision cannot be used). Free of charge.
  skip_destroy = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([{
    name                   = var.name
    image                  = var.image
    essential              = true
    user                   = "10001:10001"
    readonlyRootFilesystem = true
    stopTimeout            = 30
    portMappings           = local.port_mappings
    environment            = [for k in sort(keys(var.environment)) : { name = k, value = var.environment[k] }]
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = var.log_group_name
        awslogs-region        = var.region
        awslogs-stream-prefix = var.name
      }
    }
  }])
}

resource "aws_ecs_service" "this" {
  name                               = var.name
  cluster                            = var.cluster_arn
  task_definition                    = aws_ecs_task_definition.this.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  propagate_tags                     = "SERVICE"
  enable_ecs_managed_tags            = true
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  wait_for_steady_state              = false

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  dynamic "service_registries" {
    for_each = var.discovery_service_arn == null ? [] : [1]
    content {
      registry_arn   = var.discovery_service_arn
      container_name = var.name
      container_port = var.port
    }
  }
}

resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this.name}"
  min_capacity       = var.min_tasks
  max_capacity       = var.max_tasks
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.prefix}-${var.name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_percent
    scale_in_cooldown  = 120
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

output "service_name" {
  value = aws_ecs_service.this.name
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.this.arn
}

output "task_family" {
  value = aws_ecs_task_definition.this.family
}
