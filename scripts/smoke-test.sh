#!/usr/bin/env bash
# End-to-end check against a running stack (docker compose up --build --wait):
# register -> create a habit -> complete it -> wait for the worker to grant the reward.
# Passing proves the full path works: API -> queue -> worker -> database.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-60}"

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

echo "PASS: habit ${habit_id} earned 10 XP and unlocked ${achievements}"
