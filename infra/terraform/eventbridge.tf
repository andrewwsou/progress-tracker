resource "aws_cloudwatch_event_rule" "daily_streak_reset" {
  name                = "progresstracker-daily-streak-reset"
  schedule_expression = var.daily_reset_schedule
}

resource "aws_cloudwatch_event_target" "daily_streak_reset" {
  rule = aws_cloudwatch_event_rule.daily_streak_reset.name
  arn  = aws_lambda_function.daily_streak_reset.arn
}

resource "aws_lambda_permission" "allow_eventbridge_daily_streak_reset" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.daily_streak_reset.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.daily_streak_reset.arn
}

resource "aws_cloudwatch_event_rule" "weekly_summary" {
  name                = "progresstracker-weekly-summary"
  schedule_expression = var.weekly_summary_schedule
}

resource "aws_cloudwatch_event_target" "weekly_summary" {
  rule = aws_cloudwatch_event_rule.weekly_summary.name
  arn  = aws_lambda_function.weekly_summary.arn
}

resource "aws_lambda_permission" "allow_eventbridge_weekly_summary" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.weekly_summary.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.weekly_summary.arn
}
