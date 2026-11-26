# For the human reading a plan or apply.

output "dashboard_name" {
  value = aws_cloudwatch_dashboard.this.dashboard_name
}

output "alarm_names" {
  value = concat(
    [for a in aws_cloudwatch_metric_alarm.simple : a.alarm_name],
    [aws_cloudwatch_metric_alarm.api_latency.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.math : a.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.insights : a.alarm_name],
  )
}
