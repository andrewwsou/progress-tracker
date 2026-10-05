data "archive_file" "daily_streak_reset" {
  type        = "zip"
  source_dir  = "${path.module}/../lambda/daily_streak_reset"
  output_path = "${path.module}/build/daily_streak_reset.zip"
}

data "archive_file" "weekly_summary" {
  type        = "zip"
  source_dir  = "${path.module}/../lambda/weekly_summary"
  output_path = "${path.module}/build/weekly_summary.zip"
}

# Both Lambdas only make an outbound HTTPS call to the API - no DB/VPC
# access, so the execution role needs nothing beyond writing its own logs.
resource "aws_iam_role" "automation_lambda" {
  name = "progresstracker-automation-lambda"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

resource "aws_iam_role_policy_attachment" "automation_lambda_logs" {
  role       = aws_iam_role.automation_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "daily_streak_reset" {
  function_name = "progresstracker-daily-streak-reset"
  role          = aws_iam_role.automation_lambda.arn
  handler       = "handler.handler"
  runtime       = "python3.12"
  timeout       = 15
  memory_size   = 128

  filename         = data.archive_file.daily_streak_reset.output_path
  source_code_hash = data.archive_file.daily_streak_reset.output_base64sha256

  environment {
    variables = {
      API_BASE_URL     = var.api_base_url
      AUTOMATION_TOKEN = var.automation_token
    }
  }

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

resource "aws_lambda_function" "weekly_summary" {
  function_name = "progresstracker-weekly-summary"
  role          = aws_iam_role.automation_lambda.arn
  handler       = "handler.handler"
  runtime       = "python3.12"
  timeout       = 15
  memory_size   = 128

  filename         = data.archive_file.weekly_summary.output_path
  source_code_hash = data.archive_file.weekly_summary.output_base64sha256

  environment {
    variables = {
      API_BASE_URL     = var.api_base_url
      AUTOMATION_TOKEN = var.automation_token
    }
  }

  tags = {
    Environment = var.environment
    Project     = "progress-tracker"
  }
}

# Alarms. A failed call (the API unreachable, an error, or a token it rejects) makes the handler
# raise, and Lambda retries an EventBridge invocation twice before dropping it. Nothing else
# notices, so any error here needs a person to look at it.
resource "aws_cloudwatch_metric_alarm" "daily_streak_reset_errors" {
  alarm_name          = "progresstracker-daily-streak-reset-errors"
  alarm_description   = "The streak-reset job failed to call the API: lapsed streaks are not being zeroed."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.daily_streak_reset.function_name }
  statistic           = "Sum"
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

resource "aws_cloudwatch_metric_alarm" "weekly_summary_errors" {
  alarm_name          = "progresstracker-weekly-summary-errors"
  alarm_description   = "The weekly summary job failed to call the API: that week's summaries were not requested. Ask for them again with ?weekStart= once it is fixed."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.weekly_summary.function_name }
  statistic           = "Sum"
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
