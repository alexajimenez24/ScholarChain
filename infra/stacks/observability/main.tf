# Observability stack: one CloudWatch dashboard and the alarms. Log groups live with
# their producers (compute: the pipeline; app: the services and the API access log),
# because a producer must find its log group the moment it starts, and this stack is
# applied last. The services emit CloudWatch Embedded Metric Format lines on stdout
# (namespace ScholarChain/Services; HTTP services: Requests, ClientErrors, ServerErrors,
# LatencyMs by Service and Operation; embedding-worker: Received, Acknowledged,
# Malformed, Rejected, FailedBatches, VectorsWritten by Service), so there is no scrape.
#
# Metric coverage (work order area 12): EMR (AWS/ElasticMapReduce), ECS (AWS/ECS), SQS
# (AWS/SQS), Neptune (AWS/Neptune), DynamoDB (AWS/DynamoDB), S3 Vectors clients (the
# worker's VectorsWritten, Rejected and FailedBatches, and query latency), API latency
# (AWS/ApiGateway and the services' LatencyMs), LLM usage (AWS/Bedrock tokens and
# invocations, all models) and errors (5xx, ServerErrors, worker errors, the DLQ, failed
# pipeline executions, Bedrock errors).
#
# Hourly cost while it exists: alarms bill per alarm metric per month (pro-rated); one
# dashboard. Figures in the phase report.

locals {
  name = "scholarchain-${var.environment}"

  tags = {
    course      = var.course
    project     = "scholarchain"
    environment = var.environment
    owner       = var.owner
    expiration  = var.expiration
    managed-by  = "terraform"
    stack       = "observability"
  }

  data    = "/scholarchain/${var.environment}/data"
  compute = "/scholarchain/${var.environment}/compute"
  app     = "/scholarchain/${var.environment}/app"

  services = ["query-service", "graph-service", "embedding-worker"]
}

data "aws_ssm_parameter" "in" {
  for_each = toset(concat(
    [
      "${local.data}/neptune/enabled",
      "${local.data}/models/embedding-model-id",
      "${local.compute}/sqs/embedding-queue-name",
      "${local.compute}/sqs/embedding-dlq-name",
      "${local.compute}/pipeline/state-machine-arn",
      "${local.app}/api/id",
      "${local.app}/ecs/cluster-name",
    ],
    [for s in local.services : "${local.app}/ecs/service/${s}"],
  ))

  name = each.value
}

locals {
  p = {
    for k, v in data.aws_ssm_parameter.in :
    trimprefix(trimprefix(trimprefix(k, "${local.data}/"), "${local.compute}/"), "${local.app}/") => v.insecure_value
  }

  neptune_enabled = local.p["neptune/enabled"] == "true"
  # The data stack names the cluster <name>-graph (modules/neptune-serverless).
  neptune_cluster = "${local.name}-graph"
  api_id          = local.p["api/id"]
  cluster         = local.p["ecs/cluster-name"]
  queue           = local.p["sqs/embedding-queue-name"]
  dlq             = local.p["sqs/embedding-dlq-name"]
  state_machine   = local.p["pipeline/state-machine-arn"]
  embedding_model = local.p["models/embedding-model-id"]

  ns = "ScholarChain/Services"
}

# ----- Alarms -------------------------------------------------------------------------
# Single-metric alarms.
locals {
  simple_alarms = merge(
    {
      api-5xx = {
        description = "API Gateway returned 5xx responses."
        namespace   = "AWS/ApiGateway", metric = "5xx", stat = "Sum", period = 300, periods = 1
        dimensions  = { ApiId = local.api_id }, op = "GreaterThanOrEqualToThreshold", threshold = 5
      }
      embedding-dlq-not-empty = {
        description = "A message reached the embedding dead-letter queue (malformed, oversized or rejected)."
        namespace   = "AWS/SQS", metric = "ApproximateNumberOfMessagesVisible", stat = "Maximum", period = 300, periods = 1
        dimensions  = { QueueName = local.dlq }, op = "GreaterThanOrEqualToThreshold", threshold = 1
      }
      embedding-queue-stalled = {
        description = "The oldest embedding job waited more than 30 minutes (worker down or too slow)."
        namespace   = "AWS/SQS", metric = "ApproximateAgeOfOldestMessage", stat = "Maximum", period = 300, periods = 2
        dimensions  = { QueueName = local.queue }, op = "GreaterThanOrEqualToThreshold", threshold = 1800
      }
    },
    local.neptune_enabled ? {
      neptune-at-max-capacity = {
        description = "Neptune Serverless sat at its maximum NCU for 30 minutes (cost and saturation)."
        namespace   = "AWS/Neptune", metric = "ServerlessDatabaseCapacity", stat = "Minimum", period = 300, periods = 6
        dimensions  = { DBClusterIdentifier = local.neptune_cluster }, op = "GreaterThanOrEqualToThreshold", threshold = var.neptune_max_ncu
      }
    } : {},
  )
}

resource "aws_cloudwatch_metric_alarm" "simple" {
  for_each = local.simple_alarms

  alarm_name          = "${local.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  statistic           = each.value.stat
  period              = each.value.period
  evaluation_periods  = each.value.periods
  dimensions          = each.value.dimensions
  comparison_operator = each.value.op
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
  ok_actions          = var.alarm_action_arns
}

resource "aws_cloudwatch_metric_alarm" "api_latency" {
  alarm_name          = "${local.name}-api-latency-p90"
  alarm_description   = "API p90 latency above ${var.api_latency_p90_ms} ms for 10 minutes."
  namespace           = "AWS/ApiGateway"
  metric_name         = "Latency"
  extended_statistic  = "p90"
  period              = 300
  evaluation_periods  = 2
  dimensions          = { ApiId = local.api_id }
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.api_latency_p90_ms
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
  ok_actions          = var.alarm_action_arns
}

# Metric-math alarms (sum of a few metrics).
locals {
  math_alarms = {
    worker-errors = {
      description = "embedding-worker failed batches, rejected or malformed messages (S3 Vectors client errors)."
      namespace   = local.ns
      metrics     = ["FailedBatches", "Rejected", "Malformed"]
      dimensions  = { Service = "embedding-worker" }
      threshold   = 1
    }
    pipeline-failed = {
      description = "A pipeline execution failed, timed out or was aborted."
      namespace   = "AWS/States"
      metrics     = ["ExecutionsFailed", "ExecutionsTimedOut", "ExecutionsAborted"]
      dimensions  = { StateMachineArn = local.state_machine }
      threshold   = 1
    }
    bedrock-errors = {
      description = "Embedding model calls failed or were throttled."
      namespace   = "AWS/Bedrock"
      metrics     = ["InvocationClientErrors", "InvocationServerErrors", "InvocationThrottles"]
      dimensions  = { ModelId = local.embedding_model }
      threshold   = 5
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "math" {
  for_each = local.math_alarms

  alarm_name          = "${local.name}-${each.key}"
  alarm_description   = each.value.description
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
  ok_actions          = var.alarm_action_arns

  metric_query {
    id          = "total"
    expression  = join(" + ", [for i, m in each.value.metrics : "FILL(m${i}, 0)"])
    label       = each.key
    return_data = true
  }

  dynamic "metric_query" {
    for_each = each.value.metrics
    content {
      id = "m${metric_query.key}"
      metric {
        namespace   = each.value.namespace
        metric_name = metric_query.value
        dimensions  = each.value.dimensions
        stat        = "Sum"
        period      = 300
      }
    }
  }
}

# Metrics Insights alarms (aggregate over a dimension that varies, such as Operation).
locals {
  insights_alarms = {
    services-server-errors = {
      description = "HTTP services answered 5xx (EMF ServerErrors, all operations)."
      query       = "SELECT SUM(ServerErrors) FROM SCHEMA(\"${local.ns}\", Operation, Service)"
      periods     = 1, threshold = 5
    }
    ecs-cpu-high = {
      description = "An ECS service averaged above 85% CPU for 15 minutes (at max tasks, it cannot scale further)."
      query       = "SELECT MAX(CPUUtilization) FROM SCHEMA(\"AWS/ECS\", ClusterName, ServiceName) WHERE ClusterName = '${local.cluster}'"
      periods     = 3, threshold = 85
    }
    dynamodb-throttles = {
      description = "DynamoDB throttled requests (any project table)."
      query       = "SELECT SUM(ThrottledRequests) FROM SCHEMA(\"AWS/DynamoDB\", Operation, TableName)"
      periods     = 1, threshold = 1
    }
    llm-token-burst = {
      description = "Bedrock input tokens (all models) above the hourly cost bound."
      query       = "SELECT SUM(InputTokenCount) FROM SCHEMA(\"AWS/Bedrock\", ModelId)"
      periods     = 1, threshold = var.embedding_tokens_per_hour_alarm
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "insights" {
  for_each = local.insights_alarms

  alarm_name          = "${local.name}-${each.key}"
  alarm_description   = each.value.description
  evaluation_periods  = each.value.periods
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = each.value.threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_action_arns
  ok_actions          = var.alarm_action_arns

  metric_query {
    id          = "q"
    expression  = each.value.query
    period      = each.key == "llm-token-burst" ? 3600 : 300
    label       = each.key
    return_data = true
  }
}

# ----- Dashboard (one) ----------------------------------------------------------------
locals {
  r = var.region

  widgets = [
    { title = "API requests and errors", metrics = [
      ["AWS/ApiGateway", "Count", "ApiId", local.api_id, { stat = "Sum" }],
      [".", "4xx", ".", ".", { stat = "Sum" }],
      [".", "5xx", ".", ".", { stat = "Sum" }],
    ] },
    { title = "API latency p50 / p90 (ms)", metrics = [
      ["AWS/ApiGateway", "Latency", "ApiId", local.api_id, { stat = "p50" }],
      ["...", { stat = "p90" }],
    ] },
    { title = "Services: requests, 5xx, latency p90 (EMF)", metrics = [
      [{ expression = "SELECT SUM(Requests) FROM SCHEMA(\"${local.ns}\", Operation, Service) GROUP BY Service", id = "req", label = "Requests" }],
      [{ expression = "SELECT SUM(ServerErrors) FROM SCHEMA(\"${local.ns}\", Operation, Service) GROUP BY Service", id = "err", label = "ServerErrors" }],
      [{ expression = "SELECT MAX(LatencyMs) FROM SCHEMA(\"${local.ns}\", Operation, Service) GROUP BY Service", id = "lat", label = "LatencyMs max" }],
    ] },
    { title = "Embedding worker / S3 Vectors writes (EMF)", metrics = [
      for m in ["Received", "Acknowledged", "VectorsWritten", "Rejected", "Malformed", "FailedBatches"] :
      [local.ns, m, "Service", "embedding-worker", { stat = "Sum" }]
    ] },
    { title = "ECS CPU and memory (%)", metrics = concat(
      [for s in local.services : ["AWS/ECS", "CPUUtilization", "ClusterName", local.cluster, "ServiceName", s, { stat = "Average" }]],
      [for s in local.services : ["AWS/ECS", "MemoryUtilization", "ClusterName", local.cluster, "ServiceName", s, { stat = "Average" }]],
    ) },
    { title = "Embedding queue and DLQ", metrics = [
      ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", local.queue, { stat = "Maximum" }],
      [".", "ApproximateAgeOfOldestMessage", ".", ".", { stat = "Maximum", yAxis = "right" }],
      [".", "ApproximateNumberOfMessagesVisible", ".", local.dlq, { stat = "Maximum", label = "DLQ visible" }],
    ] },
    { title = "Pipeline (Step Functions) and EMR", metrics = [
      ["AWS/States", "ExecutionsSucceeded", "StateMachineArn", local.state_machine, { stat = "Sum" }],
      [".", "ExecutionsFailed", ".", ".", { stat = "Sum" }],
      [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"YARNMemoryAvailablePercentage\"', 'Average', 300)", id = "emrmem", label = "EMR YARN memory available %", yAxis = "right" }],
      [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"AppsFailed\"', 'Sum', 300)", id = "emrfail", label = "EMR apps failed" }],
    ] },
    { title = "Neptune Serverless", metrics = [
      ["AWS/Neptune", "ServerlessDatabaseCapacity", "DBClusterIdentifier", local.neptune_cluster, { stat = "Maximum" }],
      [".", "CPUUtilization", ".", ".", { stat = "Average", yAxis = "right" }],
      [".", "GremlinRequestsPerSec", ".", ".", { stat = "Average", yAxis = "right" }],
    ] },
    { title = "DynamoDB consumed capacity and throttles", metrics = [
      [{ expression = "SELECT SUM(ConsumedWriteCapacityUnits) FROM SCHEMA(\"AWS/DynamoDB\", TableName) GROUP BY TableName", id = "wcu", label = "WCU" }],
      [{ expression = "SELECT SUM(ConsumedReadCapacityUnits) FROM SCHEMA(\"AWS/DynamoDB\", TableName) GROUP BY TableName", id = "rcu", label = "RCU" }],
      [{ expression = "SELECT SUM(ThrottledRequests) FROM SCHEMA(\"AWS/DynamoDB\", Operation, TableName)", id = "thr", label = "Throttled" }],
    ] },
    { title = "LLM and embedding usage (Bedrock)", metrics = [
      [{ expression = "SELECT SUM(InputTokenCount) FROM SCHEMA(\"AWS/Bedrock\", ModelId) GROUP BY ModelId", id = "tin", label = "Input tokens" }],
      [{ expression = "SELECT SUM(OutputTokenCount) FROM SCHEMA(\"AWS/Bedrock\", ModelId) GROUP BY ModelId", id = "tout", label = "Output tokens" }],
      [{ expression = "SELECT SUM(Invocations) FROM SCHEMA(\"AWS/Bedrock\", ModelId) GROUP BY ModelId", id = "inv", label = "Invocations", yAxis = "right" }],
    ] },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = "${local.name}-overview"

  dashboard_body = jsonencode({
    widgets = [
      for i, w in local.widgets : {
        type   = "metric"
        x      = (i % 2) * 12
        y      = floor(i / 2) * 6
        width  = 12
        height = 6
        properties = {
          title   = w.title
          region  = local.r
          view    = "timeSeries"
          stacked = false
          period  = 300
          metrics = w.metrics
        }
      }
    ]
  })
}

module "published" {
  source = "../../modules/ssm-parameters"

  prefix = "/scholarchain/${var.environment}/observability"

  parameters = {
    "deploy/revision" = var.revision
    "dashboard/name"  = aws_cloudwatch_dashboard.this.dashboard_name
    "alarms/prefix"   = "${local.name}-"
  }
}
