#!/usr/bin/env bash
# Load tests for the completion pipeline.
#
# Every scenario starts a fresh stack (so the database only holds what the scenario wrote),
# drives it with k6 from inside the Compose network, and then checks the database for the
# properties the pipeline promises. Output goes to load/results/. See load/README.md.
#
# The stack is its own Compose project (progress-tracker-load) on its own port, so running
# this never touches a stack you started with a plain `docker compose up`, or its data.
#
#   ./load/run.sh contention   many requests completing ONE habit at the same time (sync and async)
#   ./load/run.sh compare      the same steady request rate in sync mode and in async mode
#   ./load/run.sh streak       how long the worker takes to reward streaks of 1, 30 and 365 days
#   ./load/run.sh reset        how long the nightly streak-reset job takes over 20,000 lapsed habits
#   ./load/run.sh chaos        steady load while the worker is killed and the queue is frozen
#   ./load/run.sh drain        how fast the worker clears a backlog of queued completions
#   ./load/run.sh all          all of the above
#
# Tunables (environment variables): REQUESTS, VUS, RATE, DURATION, USERS, P95_LIMIT_MS, API_PORT,
# WORKER_CONCURRENCY.
set -euo pipefail
cd "$(dirname "$0")/.."

SCENARIO="${1:-}"
case "$SCENARIO" in
  contention|compare|streak|reset|chaos|drain|all) ;;
  *) sed -n '2,20p' load/run.sh | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

REQUESTS="${REQUESTS:-8000}"     # contention: total requests against the one habit
VUS="${VUS:-200}"                # contention: how many are in flight at a time
RATE="${RATE:-100}"              # compare/chaos: completions per second
DURATION="${DURATION:-30}"       # compare: seconds of steady load (chaos always runs 40 s)
USERS="${USERS:-50}"             # compare/chaos: users the completions are spread over
P95_LIMIT_MS="${P95_LIMIT_MS:-200}"

# Read by every `docker compose` call below.
export COMPOSE_PROJECT_NAME=progress-tracker-load
export SUMMARY_LLM_ENABLED=false   # load tests never call Claude, whatever the shell has set
export API_PORT="${API_PORT:-18080}"
BASE_URL="http://localhost:${API_PORT}"

RESULTS_DIR=load/results
FAILED=false

for tool in docker curl jq python3; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 2; }
done

# Start from an empty results directory so the summary only shows this run.
rm -rf "$RESULTS_DIR"
mkdir -p "$RESULTS_DIR"

# --- helpers -------------------------------------------------------------------------------

log() { printf '\n>> %s\n' "$*"; }

sql() {
  docker compose exec -T postgres psql -U ptrack -d progresstracker -v ON_ERROR_STOP=1 -At -c "$1"
}

# Starts a fresh stack. $1: true = async (worker rewards through the queue), false = sync.
# $2 (optional): token that unlocks the scheduled-job endpoints.
# Both are exported, so any later `docker compose` call in the scenario sees the same settings
# and does not recreate the containers in a different mode.
stack_up() {
  export STACK_QUEUE_ENABLED="$1"
  export AUTOMATION_TOKEN="${2:-}"
  docker compose --profile load down -v --remove-orphans >/dev/null 2>&1 || true
  if ! docker compose up -d --build --wait --wait-timeout 300 >"$RESULTS_DIR/stack.log" 2>&1; then
    cat "$RESULTS_DIR/stack.log"
    echo "the stack did not start" >&2
    exit 1
  fi
}

# Runs when the script exits, however it exits. Keeps the container logs if anything failed.
stack_down() {
  local status=$?
  if [[ "$status" != "0" || "$FAILED" == "true" ]]; then
    docker compose logs --no-color >"$RESULTS_DIR/containers.log" 2>&1 || true
  fi
  docker compose --profile load down -v --remove-orphans >/dev/null 2>&1 || true
}

# Runs the k6 script. $1 = label for the results file, the rest are -e NAME=value pairs.
# Returns non-zero when k6 could not run or one of its thresholds failed.
run_k6() {
  local label="$1"; shift
  docker compose --profile load run --rm -T -e LABEL="$label" "$@" k6 \
    run --quiet --no-usage-report /load/completions.js | tee "$RESULTS_DIR/$label.txt"
}

k6_failed() {
  echo "  FAIL  $1: k6 reported failed thresholds or could not run"
  FAILED=true
}

# Waits until the API has sent every event to the queue.
wait_for_published() {
  local timeout="$1" waited=0
  until [[ "$(sql "select count(*) from outbox_events where published_at is null")" == "0" ]]; do
    if (( waited >= timeout )); then
      echo "  FAIL  events still unpublished after ${timeout}s"
      FAILED=true
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

# Waits until every completion has its reward and every event has been published.
wait_for_rewards() {
  local timeout="$1" waited=0 pending
  while true; do
    pending=$(sql "select (select count(*) from habit_entries where xp_earned = 0)
                        + (select count(*) from outbox_events where published_at is null)")
    [[ "$pending" == "0" ]] && return 0
    if (( waited >= timeout )); then
      echo "  FAIL  $pending completions still unrewarded or unpublished after ${timeout}s"
      FAILED=true
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

# $1 = what must hold, $2 = a query counting the rows that violate it.
expect_none() {
  local violations
  violations=$(sql "$2")
  if [[ "$violations" == "0" ]]; then
    echo "  ok    $1"
  else
    echo "  FAIL  $1 ($violations violations)"
    FAILED=true
  fi
}

# The promises of the pipeline, checked against what is actually in the database.
# $1: true = async (also checks the outbox and the worker's record of handled events).
# $2: "seeded" when the scenario inserted past completions directly, which have no events.
check_database() {
  echo "  database after the run: $(sql "select count(*) from habit_entries") completions"
  expect_none "every completion was rewarded" \
    "select count(*) from habit_entries where xp_earned = 0"
  expect_none "each habit's XP total equals the sum of its completions (nothing lost, nothing doubled)" \
    "select count(*) from habit h where h.xp_total <> coalesce((select sum(e.xp_earned) from habit_entries e where e.habit_id = h.id), 0)"
  expect_none "every user who completed a habit has the first-completion achievement exactly once" \
    "select count(*) from app_user u
      where exists (select 1 from habit h join habit_entries e on e.habit_id = h.id where h.user_id = u.id)
        and (select count(*) from user_achievement ua join achievement a on a.id = ua.achievement_id
              where ua.user_id = u.id and a.code = 'FIRST_COMPLETION') <> 1"
  if [[ "$1" == "true" ]]; then
    expect_none "every event reached the queue" \
      "select count(*) from outbox_events where published_at is null"
    if [[ "${2:-}" != "seeded" ]]; then
      expect_none "there is exactly one event per completion" \
        "select abs((select count(*) from outbox_events) - (select count(*) from habit_entries))"
    fi
    expect_none "every event was handled by the worker" \
      "select count(*) from outbox_events o where not exists (select 1 from processed_events p where p.event_id = o.id)"
  fi
}

# Time from the API recording a completion to the worker picking its event up.
queue_delay() {
  sql "select 'queue delay ms: p50 ' || round(1000 * percentile_cont(0.5) within group (order by d))
           || '  p95 ' || round(1000 * percentile_cont(0.95) within group (order by d))
           || '  max ' || round(1000 * max(d))
         from (select extract(epoch from (processed_at - occurred_at)) as d from processed_events) t"
}

# --- scenarios -----------------------------------------------------------------------------

# Many requests completing the same habit at once. Exactly one may win; none may fail.
scenario_contention() {
  local mode queue
  for mode in sync async; do
    queue=$([[ "$mode" == "async" ]] && echo true || echo false)
    log "contention ($mode): $REQUESTS requests against one habit, $VUS at a time"
    stack_up "$queue"
    run_k6 "contention-$mode" -e SCENARIO=contention -e REQUESTS="$REQUESTS" -e VUS="$VUS" \
      || k6_failed "contention-$mode"
    wait_for_rewards 60
    expect_none "the habit was completed exactly once" \
      "select abs((select count(*) from habit_entries) - 1)"
    expect_none "and rewarded exactly once (10 XP)" \
      "select count(*) from habit where id in (select habit_id from habit_entries) and xp_total <> 10"
    check_database "$queue"
  done
}

# The same steady request rate with rewards computed in the request (sync) and by the worker (async).
scenario_compare() {
  local mode queue
  for mode in sync async; do
    queue=$([[ "$mode" == "async" ]] && echo true || echo false)
    log "throughput ($mode): $RATE completions/s for ${DURATION}s over $USERS users"
    stack_up "$queue"
    run_k6 "throughput-$mode" -e SCENARIO=throughput -e RATE="$RATE" -e DURATION="$DURATION" \
      -e USERS="$USERS" -e P95_LIMIT_MS="$P95_LIMIT_MS" || k6_failed "throughput-$mode"
    wait_for_rewards 180
    check_database "$queue"
    if [[ "$queue" == "true" ]]; then
      echo "  $(queue_delay)" | tee -a "$RESULTS_DIR/throughput-$mode.txt"
    fi
  done
}

# How long the worker takes to apply one reward, by the length of the streak it has to count.
scenario_streak() {
  local per_length=30 token user_id length id
  log "streak: worker time to reward a completion that makes a streak of 1, 30 and 365 days"
  stack_up true

  token=$(curl -fsS -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"streak-$(date +%s)-${RANDOM}@example.com\",\"password\":\"load-test-password\"}" | jq -er '.token')
  user_id=$(sql "select max(id) from app_user")

  # Habits that already have 0, 29 and 364 completed days in a row ending yesterday. "warmup"
  # habits go first so the JVM is warm before anything is measured.
  for length in warmup 1 30 365; do
    local days=$([[ "$length" == "warmup" ]] && echo 1 || echo "$length")
    sql "with new_habits as (
           insert into habit (user_id, name, description, frequency, goal_period, goal_target_count,
                              xp_total, current_streak, longest_streak, created_at)
           select $user_id, 'streak-$length-' || g, '', 'DAILY', 'DAILY', 1, 10 * ($days - 1), 0, 0, now()
             from generate_series(1, $per_length) g
           returning id)
         insert into habit_entries (habit_id, completed_date, xp_earned, created_at)
         select h.id, current_date - d, 10, now() from new_habits h cross join generate_series(1, $days - 1) d" >/dev/null
  done

  # Complete each one today, one request at a time.
  for id in $(sql "select id from habit where name like 'streak-%' order by id"); do
    curl -fsS -X POST "$BASE_URL/api/habits/$id/complete" -H "Authorization: Bearer $token" >/dev/null
  done
  wait_for_rewards 180

  sql "select id || ' ' || split_part(name, '-', 2) from habit where name like 'streak-%'" > "$RESULTS_DIR/streak-habits.txt"
  docker compose logs --no-log-prefix worker \
    | sed -n 's/.*Processed completion .*habitId=\([0-9]*\) .*elapsedMs=\([0-9.]*\).*/\1 \2/p' > "$RESULTS_DIR/streak-timings.txt"

  python3 - "$RESULTS_DIR/streak-habits.txt" "$RESULTS_DIR/streak-timings.txt" <<'PY' | tee "$RESULTS_DIR/streak.txt"
import statistics, sys
length = dict(line.split() for line in open(sys.argv[1]) if line.strip())
timings = {}
for line in open(sys.argv[2]):
    habit_id, elapsed = line.split()
    if length.get(habit_id, "warmup") != "warmup":
        timings.setdefault(int(length[habit_id]), []).append(float(elapsed))
print("\n== streak ==")
for days in sorted(timings):
    values = timings[days]
    print(f"  streak of {days:>3} days   worker time per reward: median {statistics.median(values):7.2f} ms"
          f"   mean {statistics.mean(values):7.2f} ms   ({len(values)} events)")
    print(f"RESULT label=streak-{days} events={len(values)} median_ms={statistics.median(values):.2f} mean_ms={statistics.mean(values):.2f}")
PY
  check_database true seeded
}

# The nightly job that zeroes streaks nobody kept up. Timed over a table where every streak has lapsed.
scenario_reset() {
  local habits=20000 automation_token=load-test-token seconds response
  log "streak reset: the nightly job over $habits habits whose streaks have lapsed"
  stack_up false "$automation_token"

  curl -fsS -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"reset-$(date +%s)-${RANDOM}@example.com\",\"password\":\"load-test-password\"}" >/dev/null
  sql "insert into habit (user_id, name, description, frequency, goal_period, goal_target_count,
                          xp_total, current_streak, longest_streak, last_completed_date, created_at)
       select (select max(id) from app_user), 'lapsed-' || g, '', 'DAILY', 'DAILY', 1, 50, 5, 5, current_date - 3, now()
         from generate_series(1, $habits) g" >/dev/null

  response="$RESULTS_DIR/reset-response.json"
  seconds=$(curl -fsS -o "$response" -w '%{time_total}' -X POST "$BASE_URL/api/internal/automations/reset-streaks" \
    -H "X-Internal-Token: $automation_token")

  {
    echo
    echo "== streak reset =="
    echo "  $(jq -r '.resetCount' "$response") streaks reset in ${seconds}s"
    echo "RESULT label=streak-reset habits=$habits reset=$(jq -r '.resetCount' "$response") seconds=$seconds"
  } | tee "$RESULTS_DIR/streak-reset.txt"

  expect_none "the job reported every lapsed streak" \
    "select abs($habits - $(jq -r '.resetCount' "$response"))"
  expect_none "no lapsed streak is left" \
    "select count(*) from habit where current_streak > 0"
  expect_none "nothing but the streak was changed" \
    "select count(*) from habit where name like 'lapsed-%' and (xp_total <> 50 or longest_streak <> 5)"
}

# One step of the chaos scenario: a `docker compose` command whose failure must be reported.
fault() {
  if ! docker compose "$@" >>"$RESULTS_DIR/stack.log" 2>&1; then
    echo "  FAIL  docker compose $* failed (see $RESULTS_DIR/stack.log)"
    FAILED=true
  fi
}

# Steady load while parts of the pipeline fail. The API must not notice, and afterwards every
# completion must still have been rewarded exactly once.
scenario_chaos() {
  local duration=40
  log "chaos: $RATE completions/s for ${duration}s; the worker is killed and the queue is frozen mid-run"
  stack_up true

  run_k6 chaos -e SCENARIO=throughput -e RATE="$RATE" -e DURATION="$duration" \
    -e USERS="$USERS" -e P95_LIMIT_MS="$P95_LIMIT_MS" &
  local k6_job=$!

  # Wait for the load itself to start (k6 first registers users and creates habits).
  # Give up if k6 exits first: it failed during setup and there is nothing to disturb.
  until [[ "$(sql "select count(*) from habit_entries")" != "0" ]]; do
    if ! kill -0 "$k6_job" 2>/dev/null; then
      wait "$k6_job" || true
      k6_failed chaos
      return
    fi
    sleep 0.5
  done

  sleep 8;  echo "  +8s   killing the worker (SIGKILL, no clean shutdown)"; fault kill worker
  sleep 8;  echo "  +16s  freezing the queue";                              fault pause sqs
  sleep 8;  echo "  +24s  queue back, worker restarted";                    fault unpause sqs
  fault up -d --no-deps worker
  wait "$k6_job" || k6_failed chaos

  # Messages the killed worker had taken come back after the 60 s visibility timeout.
  wait_for_rewards 240
  check_database true
  echo "  $(queue_delay)" | tee -a "$RESULTS_DIR/chaos.txt"
}

# A backlog built up while the worker was stopped, then the time the worker takes to clear it.
# Measures the worker alone: by the time it starts, the API and the queue have done their part.
# The rate is events over the time between the first and the last event the worker picked up.
scenario_drain() {
  local concurrency events seconds rate
  log "drain: $RATE completions/s for ${DURATION}s over $USERS users queue up with the worker stopped; then it clears them"
  stack_up true
  fault stop worker
  run_k6 drain -e SCENARIO=throughput -e RATE="$RATE" -e DURATION="$DURATION" \
    -e USERS="$USERS" -e P95_LIMIT_MS="$P95_LIMIT_MS" || k6_failed drain
  wait_for_published 120
  fault start worker
  wait_for_rewards 900
  check_database true

  concurrency=$(docker compose exec -T worker printenv WORKER_CONCURRENCY 2>/dev/null || echo 1)
  events=$(sql "select count(*) from processed_events")
  seconds=$(sql "select round(extract(epoch from max(processed_at) - min(processed_at))::numeric, 1) from processed_events")
  rate=$(sql "select round((count(*) / greatest(extract(epoch from max(processed_at) - min(processed_at)), 0.001))::numeric, 1)
              from processed_events")
  {
    echo
    echo "== drain =="
    echo "  $events events cleared in ${seconds}s: $rate events/s (worker concurrency $concurrency)"
    echo "RESULT label=drain concurrency=$concurrency events=$events seconds=$seconds events_per_s=$rate"
  } | tee -a "$RESULTS_DIR/drain.txt"
  # The worker's own view (its metrics endpoint is not published, so ask from inside the container).
  docker compose exec -T worker curl -fsS localhost:8081/actuator/prometheus 2>/dev/null \
    | grep -E '^(worker_|hikaricp_connections_(pending|timeout_total))' >"$RESULTS_DIR/drain-metrics.txt" || true
}

# --- main ----------------------------------------------------------------------------------

trap stack_down EXIT

case "$SCENARIO" in
  contention) scenario_contention ;;
  compare)    scenario_compare ;;
  streak)     scenario_streak ;;
  reset)      scenario_reset ;;
  chaos)      scenario_chaos ;;
  drain)      scenario_drain ;;
  all)        scenario_contention; scenario_compare; scenario_streak; scenario_reset; scenario_chaos; scenario_drain ;;
esac

log "results"
grep -h '^RESULT' "$RESULTS_DIR"/*.txt 2>/dev/null | sed 's/^RESULT //' | sort -u || true

if [[ "$FAILED" == "true" ]]; then
  echo; echo "FAILED: see the FAIL lines above"; exit 1
fi
echo; echo "PASSED"
