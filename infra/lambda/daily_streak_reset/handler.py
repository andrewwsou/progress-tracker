"""Nightly EventBridge target: tells the API to zero out streaks for habits
nobody completed recently. Calls the API instead of touching Postgres
directly so the Lambda needs no VPC/DB access - just outbound HTTPS.

Stdlib-only (urllib) so the deployment package needs no pip install/layer.
"""
import json
import os
import urllib.error
import urllib.request

ENDPOINT_PATH = "/api/internal/automations/reset-streaks"


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
            print(f"reset-streaks succeeded: {body}")
            return {"statusCode": response.status, "body": body}
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        print(f"reset-streaks failed: {e.code} {error_body}")
        raise
    except urllib.error.URLError as e:
        print(f"reset-streaks unreachable: {e.reason}")
        raise
