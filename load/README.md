# Load tests

Scenarios that put the completion pipeline under load and then check, in the database, that it
kept its promises. The load generator is [k6](https://k6.io/); the runner script starts the
stack, runs a scenario, and queries PostgreSQL afterwards.

Recorded numbers, and what changed between them, are in [RESULTS.md](RESULTS.md).

## Running

Needs Docker, `curl`, `jq`, and `python3`. Nothing else has to be installed: k6 runs as a container.

```
./load/run.sh all          # every scenario, about 12 minutes
./load/run.sh contention   # or one at a time: contention, compare, streak, reset, chaos
```

Every scenario starts a fresh stack with an empty database, so the checks afterwards can look
at whole tables. That stack is its own Compose project (`progress-tracker-load`, API on port
18080), so it never touches a stack you started with a plain `docker compose up`, or its data.

The script exits non-zero if a k6 threshold or a database check fails. It writes its output to
`load/results/` (not committed), including the container logs when something failed.

## Scenarios

| Scenario | Load | Must hold during the run | Checked in the database afterwards |
|---|---|---|---|
| `contention` | 8,000 requests completing **one** habit, 200 in flight at a time. Run in sync mode and in async mode. | Every request returns 200. | The habit was completed exactly once and rewarded exactly once (10 XP). |
| `compare` | 100 completions per second for 30 seconds, each of a different habit, over 50 users. Run in sync mode and in async mode. | Under 1% failed requests, p95 under 200 ms, no request dropped by the load generator. | Every completion was rewarded once. |
| `streak` | One completion at a time for habits with 0, 29, and 364 earlier days in a row. | | Reports how long the worker took to apply each reward, by streak length. |
| `reset` | The nightly streak-reset job over 20,000 habits whose streaks have lapsed. | | Every lapsed streak is zero and nothing else changed. Reports how long the job took. |
| `chaos` | 100 completions per second for 40 seconds. Eight seconds in, the worker is killed with SIGKILL. Sixteen seconds in, the queue is frozen. Both come back at 24 seconds. | The same thresholds as `compare`: the API must not notice. | Every completion was still rewarded exactly once. |

"Sync mode" computes the reward inside the request (`QUEUE_ENABLED=false`). "Async mode" records
the completion and an outbox event, and the worker applies the reward (`QUEUE_ENABLED=true`).

### The database checks

After the load, and after waiting for the worker to catch up, the runner counts the rows that
would break each promise. Every count must be zero.

- Every completion was rewarded (no entry is left at 0 XP).
- Each habit's XP total equals the sum of its completions, so no reward was lost or applied twice.
- Every user who completed a habit has the first-completion achievement exactly once.
- Async only: every event reached the queue, there is exactly one event per completion, and
  the worker handled every event.

## Tunables

Environment variables, with their defaults:

| Variable | Default | Used by | Meaning |
|---|---|---|---|
| `REQUESTS` | 8000 | contention | Total requests against the one habit |
| `VUS` | 200 | contention | Requests in flight at a time |
| `RATE` | 100 | compare, chaos | Completions per second |
| `DURATION` | 30 | compare | Seconds of steady load |
| `USERS` | 50 | compare, chaos | Users the completions are spread over |
| `P95_LIMIT_MS` | 200 | compare, chaos | Fails the run if p95 latency is above this |
| `API_PORT` | 18080 | streak, reset | Host port of the load stack's API |

For example: `VUS=1000 ./load/run.sh contention`.

## Reading the output

- **Latency** is the time of the completion request alone (k6's `http_req_duration`), not
  including the calls that register users and create habits.
- **Completions per second** is measured over the load itself, not over k6's setup.
- **Queue delay** is the time from the API recording a completion to the worker picking its
  event up (`processed_at - occurred_at` in `processed_events`).
- **Worker time per reward** comes from the worker's own log line for each event.

## What these numbers are and are not

Everything runs on one machine: the load generator, the API, the worker, PostgreSQL, and the
queue share the same CPUs. The numbers are good for comparing sync with async, or one version
of the code with the next, on the same machine. They are not capacity figures for a deployment.

The `compare` and `chaos` scenarios use an open model (a fixed arrival rate): requests start on
schedule whether or not earlier ones have finished. A slow server then shows up as higher
latency or dropped requests, instead of quietly lowering the load.
