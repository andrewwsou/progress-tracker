#!/usr/bin/env bash
# End-to-end check against a running stack (docker compose up --build --wait):
# register -> create a habit -> complete it -> wait for the worker to grant the reward,
# then ask for this week's summary and wait for the worker to write it.
# Passing proves both paths work end to end: API -> queue -> worker -> database, and
# scheduled job -> worker -> database -> API.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-60}"
# compose.yaml's local placeholder for the scheduled-job endpoints.
AUTOMATION_TOKEN="${AUTOMATION_TOKEN:-local-development-only-token}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

email="smoke-$(date +%s)-${RANDOM}@example.com"

echo "Registering ${email}"
token=$(curl -fsS -X POST "${BASE_URL}/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${email}\",\"password\":\"smoke-test-password\"}" | jq -er '.token')
auth=(-H "Authorization: Bearer ${token}")

echo "Creating a habit"
habit_id=$(curl -fsS -X POST "${BASE_URL}/api/habits" "${auth[@]}" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Smoke test","description":"created by scripts/smoke-test.sh","frequency":"DAILY"}' | jq -er '.id')

echo "Completing habit ${habit_id}"
curl -fsS -X POST "${BASE_URL}/api/habits/${habit_id}/complete" "${auth[@]}" >/dev/null

echo "Waiting for the worker to grant the reward"
deadline=$((SECONDS + TIMEOUT_SECONDS))
xp=0
while (( SECONDS < deadline )); do
  xp=$(curl -fsS "${BASE_URL}/api/habits" "${auth[@]}" | jq -r ".[] | select(.id == ${habit_id}) | .xpTotal")
  if [[ "${xp}" == "10" ]]; then
    break
  fi
  sleep 1
done

if [[ "${xp}" != "10" ]]; then
  echo "FAIL: expected 10 XP on habit ${habit_id} within ${TIMEOUT_SECONDS}s, got '${xp}'" >&2
  exit 1
fi

achievements=$(curl -fsS "${BASE_URL}/api/achievements" "${auth[@]}" | jq -r '[.[].code] | join(",")')
if [[ ",${achievements}," != *",FIRST_COMPLETION,"* ]]; then
  echo "FAIL: expected the FIRST_COMPLETION achievement, got '${achievements}'" >&2
  exit 1
fi

echo "Reward granted: 10 XP and ${achievements}"

# Ask for this week's summaries, as the weekly scheduler would for last week. The API records the
# request; the worker writes the summary (with Claude if SUMMARY_LLM_ENABLED=true and a key are set,
# from a template otherwise).
# The date is UTC because that is the server's clock.
echo "Requesting this week's summary"
curl -fsS -X POST "${BASE_URL}/api/internal/automations/weekly-summary?weekStart=$(date -u +%F)" \
  -H "X-Internal-Token: ${AUTOMATION_TOKEN}" >/dev/null

echo "Waiting for the worker to write it"
deadline=$((SECONDS + TIMEOUT_SECONDS))
summary=""
while (( SECONDS < deadline )); do
  # The body, then the status code on its own last line. 204 means not written yet.
  response=$(curl -sS -w '\n%{http_code}' "${BASE_URL}/api/summaries/latest" "${auth[@]}")
  if [[ "${response##*$'\n'}" == "200" ]]; then
    summary="${response%$'\n'*}"
    break
  fi
  sleep 1
done

if [[ -z "${summary}" ]]; then
  echo "FAIL: no weekly summary within ${TIMEOUT_SECONDS}s" >&2
  exit 1
fi
if [[ "$(jq -r '"\(.completions) \(.xpEarned)"' <<<"${summary}")" != "1 10" ]]; then
  echo "FAIL: expected the summary to report 1 completion and 10 XP, got: ${summary}" >&2
  exit 1
fi

echo "Summary written by $(jq -r .source <<<"${summary}"): $(jq -r .headline <<<"${summary}")"
echo "PASS: habit ${habit_id} earned 10 XP and unlocked ${achievements}, and its weekly summary was written"
