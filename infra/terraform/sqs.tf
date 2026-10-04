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

output "completions_queue_url" {
  value = aws_sqs_queue.completions.url
}

output "completions_dlq_url" {
  value = aws_sqs_queue.completions_dlq.url
}
