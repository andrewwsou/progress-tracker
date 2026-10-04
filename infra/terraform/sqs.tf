# NOTE: the "progresstracker-completions" queue already exists (created
# manually while wiring up the async pipeline - see application.yaml). If
# adopting this module against that account, `terraform import` the existing
# queue instead of applying this fresh, or it will try to create a duplicate.

resource "aws_sqs_queue" "completions_dlq" {
  name                      = "progresstracker-completions-dlq"
  message_retention_seconds = 1209600 # 14 days, max allowed - gives time to inspect/replay

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

resource "aws_sqs_queue" "completions" {
  name                       = "progresstracker-completions"
  visibility_timeout_seconds = 60     # matches worker.visibilityTimeoutSeconds
  message_retention_seconds  = 345600 # 4 days

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.completions_dlq.arn
    # After 5 failed processing attempts (SqsPoller leaves the message on
    # transient failures so it gets redelivered - see worker/SqsPoller.java),
    # move it to the DLQ instead of retrying forever.
    maxReceiveCount = 5
  })

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

# Alarms. A message lands in the dead-letter queue only after failing maxReceiveCount times, so
# any message there needs a person to look at it (then redrive it: processing is idempotent).
# A growing age of the oldest message means the worker has stopped or cannot keep up.
resource "aws_cloudwatch_metric_alarm" "completions_dlq_not_empty" {
  alarm_name          = "progresstracker-completions-dlq-not-empty"
  alarm_description   = "Completion events failed repeatedly and are waiting in the dead-letter queue."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.completions_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

resource "aws_cloudwatch_metric_alarm" "completions_backlog_age" {
  alarm_name          = "progresstracker-completions-backlog-age"
  alarm_description   = "The oldest completion event has waited over 5 minutes: the worker is down or falling behind."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.completions.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 300
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

output "completions_queue_url" {
  value = aws_sqs_queue.completions.url
}

output "completions_dlq_url" {
  value = aws_sqs_queue.completions_dlq.url
}
