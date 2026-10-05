variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-west-1"
}

variable "environment" {
  description = "Deployment environment tag"
  type        = string
  default     = "dev"
}

variable "api_base_url" {
  description = "Base URL of the deployed API (e.g. https://api.progresstracker.example.com). The scheduled Lambdas call this over HTTPS."
  type        = string
}

variable "automation_token" {
  description = "Shared secret sent as X-Internal-Token by the scheduled Lambdas. Must match automation.internal-token / AUTOMATION_TOKEN on the API."
  type        = string
  sensitive   = true
}

variable "streak_reset_schedule" {
  description = "EventBridge cron expression for the streak-reset job (UTC). Each habit is judged on its owner's local date, so it runs hourly to reach every time zone's midnight within the hour."
  type        = string
  default     = "cron(0 * * * ? *)" # at the start of every hour
}

variable "weekly_summary_schedule" {
  description = "EventBridge cron expression for the weekly summary job (UTC)"
  type        = string
  default     = "cron(0 13 ? * MON *)" # 13:00 UTC every Monday
}

variable "alarm_topic_arn" {
  description = "SNS topic to notify when a queue alarm fires. Blank: the alarms still show in CloudWatch but notify no one."
  type        = string
  default     = ""
}
