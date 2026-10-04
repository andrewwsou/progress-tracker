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
