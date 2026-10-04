"""Weekly EventBridge target: tells the API to request last week's summary
for every user who completed a habit. The worker then writes each summary
(with Claude when an API key is configured, from a template otherwise) and
queues the email. Same stdlib-only, HTTP-call-out design as daily_streak_reset.
"""
import os
import urllib.error
import urllib.request

ENDPOINT_PATH = "/api/internal/automations/weekly-summary"


def handler(event, context):
    api_base_url = os.environ["API_BASE_URL"].rstrip("/")
    token = os.environ["AUTOMATION_TOKEN"]

    request = urllib.request.Request(
        url=f"{api_base_url}{ENDPOINT_PATH}",
        method="POST",
        headers={"X-Internal-Token": token},
    )

    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8")
            print(f"weekly-summary succeeded: {body}")
            return {"statusCode": response.status, "body": body}
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        print(f"weekly-summary failed: {e.code} {error_body}")
        raise
    except urllib.error.URLError as e:
        print(f"weekly-summary unreachable: {e.reason}")
        raise
