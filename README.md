# Progress Tracker

[![CI](https://github.com/andrewwsou/progress-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/andrewwsou/progress-tracker/actions/workflows/ci.yml)

A habit-tracking app with streaks, XP, and achievements, built to demonstrate an event-driven
backend: habit completions are recorded synchronously but rewarded (streaks/XP/achievements)
asynchronously via a queue-backed worker service, so the API response never waits on reward
computation.

## Architecture

```
                 ┌─────────────┐        1. one transaction: completion row
  React client ─▶│  API        │           + outbox event ─────────┐
  (Vite, :5173)  │  (Spring    │        2. return                  ▼
                 │   Boot,     │           immediately        ┌──────────┐
                 │   :8080)    │◀──────────────────────────── │ Postgres │
                 └──────┬──────┘                              └──────────┘
                        │ 3. outbox relay publishes                 ▲
                        │    unpublished events (timer,             │
                        ▼    off the request path)                  │
                 ┌─────────────┐                                    │
                 │   AWS SQS   │                                    │
                 └──────┬──────┘                                    │
                        │ 4. long-poll                              │
                        ▼                                           │
                 ┌─────────────┐        5. one transaction: record  │
                 │   Worker    │           the event id, lock the   │
                 │  (Spring    │           user, compute streak/XP/ │
                 │   Boot)     │           achievements ────────────┘
                 └─────────────┘────────▶ 6. queue completion email (SES/log)
```

**Why:** moving reward computation off the request thread keeps the API fast under load and
lets it fail independently of the worker. Both services can also run in a **fully synchronous,
AWS-free mode** for local development (see below).

## Services

| Service | Path | Stack |
|---|---|---|
| API | `backend/progresstracker` | Spring Boot 3.5 (Java 17), Spring Security + JWT, JPA/Hibernate, PostgreSQL |
| Worker | `backend/progress-worker` | Spring Boot 3.5 (Java 17), JPA/Hibernate, PostgreSQL, AWS SQS long-polling |
| Frontend | `frontend` | React 19 + Vite |

## Endpoints (API)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | JWT-based auth |
| GET/POST | `/api/habits` | List the caller's habits; create one (201) |
| PUT/DELETE | `/api/habits/{id}` | Edit or delete (204) a habit the caller owns |
| POST | `/api/habits/{id}/complete` | Record a completion (sync or async, see below) |
| GET | `/api/achievements` | Unlocked achievements for the current user |
| POST | `/api/internal/automations/reset-streaks` | Zero out streaks for habits nobody completed recently. Auth: `X-Internal-Token` header, not JWT — meant for the scheduled Lambda in `infra/lambda`, not end users. |
| POST | `/api/internal/automations/weekly-summary` | Compute last week's per-user completions/XP and queue summary emails. Same auth model. |

**Contract.** The API is described by an OpenAPI document served at `/v3/api-docs`, browsable at
`/swagger-ui.html`, and committed as [`backend/progresstracker/openapi.json`](backend/progresstracker/openapi.json).
The frontend's TypeScript types are generated from that file (`npm run generate:api`), and CI fails
if either the committed contract or the generated types drift from the running API.

**Input and errors.** Requests bind to dedicated request objects with Bean Validation, never to JPA
entities, so a client cannot set a habit's id, owner, XP, or streak. Errors from the controllers, validation,
and authentication are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents
(`application/problem+json`); validation failures list a message per field under `errors`. A missing or invalid token is `401`,
acting on someone else's habit is `403`, and an unknown habit is `404`.

## Async completion pipeline

`POST /api/habits/{id}/complete` behaves differently depending on `queue.enabled`:

- **`queue.enabled=false`** (default local dev): streak, XP, and achievement unlocks are computed
  inline and returned in the response.
- **`queue.enabled=true`**: the API writes a zero-XP completion row and an event describing it
  (`{eventId, userId, habitId, date, occurredAt}`) in one database transaction, then returns
  immediately. A relay publishes the event to SQS. The worker long-polls SQS, computes
  streak/XP/achievement state, persists it, and queues a completion email. The reward is
  therefore eventually consistent: the response shows the completion, and the XP, streak, and
  any achievement appear after the relay's next run (`OUTBOX_RELAY_DELAY_MS`, default 500 ms)
  plus queue and worker time.

**Reliability:** the pipeline applies each completion's reward **exactly once**, even though the
queue only promises at-least-once, unordered delivery.
- **No lost events (transactional outbox).** Writing to the database and then sending to a queue
  are two separate systems; if the send fails after the commit, the reward is lost. So the API
  never sends on the request path. The event goes into an `outbox_events` table in the same
  transaction as the completion, and [`OutboxRelay`](backend/progresstracker/src/main/java/com/progresstracker/progresstracker/outbox/OutboxRelay.java)
  publishes unpublished rows on a timer (`FOR UPDATE SKIP LOCKED`, so several API instances can
  relay at once). If the queue is down or the API restarts mid-send, the row is simply still
  there for the next run. A request that loses the unique-constraint race fails on the
  completion row before it writes an event, so there is exactly one event per completion.
  The relay only runs in async mode: before switching `QUEUE_ENABLED` to false, wait until
  `select count(*) from outbox_events where published_at is null` returns 0.
- **No double rewards (idempotent consumer).** The worker records each event id in a
  `processed_events` table in the same transaction as the reward, so a redelivered message is
  recognised and ignored. Then, holding a row lock on the user, it rewards a completion only if
  that completion has no XP yet. That check is what guarantees one reward per completion, even
  if the same completion arrives as two different events. If processing fails, everything rolls
  back, including the event id, and the redelivery tries again.
- **No lost updates.** The user lock makes reward transactions for one user run one at a time,
  so two workers cannot overwrite each other's totals. The API and the worker write different
  columns of the same habit row, and each updates only the columns it changed, so an edit
  cannot erase a reward or the other way round. Achievement unlocks are inserts that do nothing
  on conflict, so racing unlocks cannot fail.
- **Order does not matter.** The current streak only moves forward and the 7-day-streak
  achievement is judged on the longest streak, so an older event arriving late (a retry, or a
  redrive from the dead-letter queue) gives the same result as arriving on time.
- The poller distinguishes **poison messages** (malformed payload — deleted immediately, retrying
  can't help) from **transient processing failures** (left on the queue so SQS redelivers after
  the visibility timeout; safe because processing is idempotent).
- The sync path (`HabitController`) has its own concurrency edge case: two requests completing the
  same habit at once can both pass the "already completed?" check before either commits, so the
  loser hits the DB's `(habit_id, completed_date)` unique constraint. Found via a concurrent load
  test (50 parallel requests against one habit — 9 failed with a 500 before the fix), fixed by
  treating that constraint violation as "lost the race, return current state" instead of an error.
  Covered by [`HabitControllerTest`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/controller/HabitControllerTest.java);
  100 concurrent completions across 100 distinct habits sustained 100% success at p95 ≈ 195ms.

**Measured impact:** instrumented both code paths with request-level timing
(`COMPLETION_LATENCY` log lines in `HabitController`) and measured 20 completions per mode
locally against real Postgres:

| Mode | Avg latency |
|---|---|
| Sync (`queue.enabled=false`) | ~12.0ms |
| Async (`queue.enabled=true`) | ~4.5ms |

**~63% reduction**, driven by moving the ~10-query achievement-evaluation pass off the request
thread. The transactional outbox added later puts one more insert in the async request's
transaction; measured before and after that change, the async figure did not move.

## Running with Docker

The quickest way to see the whole pipeline work. Running the stack requires only Docker; the
smoke test also needs `bash`, `curl`, and `jq`.

```
docker compose up --build --wait   # API, worker, PostgreSQL, and a local SQS-compatible broker; returns once healthy
./scripts/smoke-test.sh            # register -> create habit -> complete -> wait for the worker's reward
docker compose down -v             # stop and delete the data
```

The API listens on `http://localhost:8080` (loopback only). If that port is taken, start the
stack with `API_PORT=8081` and run the smoke test with `BASE_URL=http://localhost:8081`.
Nothing in this stack talks to AWS: the queue is
[ElasticMQ](https://github.com/softwaremill/elasticmq), configured in
[`infra/local/elasticmq.conf`](infra/local/elasticmq.conf) with the same dead-letter policy as
the Terraform in `infra/terraform/sqs.tf`. The services reach it through `QUEUE_ENDPOINT_OVERRIDE`,
which is left blank in production so the AWS SDK resolves the real SQS endpoint.

## Running locally

Requires Postgres 14+, Java 17, and Node 20.19+ (or 22.12+).

1. **Database**
   ```
   createuser ptrack --pwprompt   # password: ptrack
   createdb progresstracker -O ptrack
   ```
2. **API** (from `backend/progresstracker`)
   ```
   export JWT_SECRET=$(openssl rand -base64 48)
   ./mvnw spring-boot:run
   ```
   Runs fully synchronously by default — no AWS credentials needed. The API refuses to start
   without a `JWT_SECRET` of at least 32 bytes. To exercise the async path against a real SQS
   queue, set `QUEUE_ENABLED=true`, `QUEUE_SQS_URL`, and AWS credentials.
3. **Worker** (from `backend/progress-worker`, only needed in async mode)
   ```
   QUEUE_ENABLED=true QUEUE_SQS_URL=<queue url> ../progresstracker/mvnw -f pom.xml spring-boot:run
   ```
   With `QUEUE_ENABLED` unset the worker starts but does not poll.
4. **Frontend** (from `frontend`)
   ```
   npm install && npm run dev
   ```
   Serves on `http://localhost:5173` and calls the API at `http://localhost:8080`; set
   `VITE_API_URL` to point it elsewhere.

**Configuration.** No secrets live in the repo. Every deployment-specific value (JWT secret,
database credentials, queue URL, automation token) is an environment variable with a
local-dev default where one is safe; [`.env.example`](.env.example) lists them all.

## Tests

Run from the repository root:

```
# Unit tests only: fast, no Docker or database needed
(cd backend/progresstracker && ./mvnw test)
(cd backend/progress-worker && ../progresstracker/mvnw test)

# Unit + integration tests + coverage gate: needs Docker running
(cd backend/progresstracker && ./mvnw clean verify)
(cd backend/progress-worker && ../progresstracker/mvnw clean verify)
```

**Unit tests** (`*Test`, Mockito) cover streak/XP calculation, goal-period no-ops, JWT handling,
the scheduled automations, and idempotent message processing.

**Integration tests** (`*IT`, [Testcontainers](https://testcontainers.com/)) start a real
PostgreSQL and an SQS-compatible broker in Docker and run the actual services against them:

| Test | What it proves |
|---|---|
| [`HabitCompletionConcurrencyIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/HabitCompletionConcurrencyIT.java) | 50 simultaneous completions of one habit over HTTP all return 200, write exactly one row, and grant XP once. This is the race described above, and it needs a real database: a mock cannot violate a unique constraint. |
| [`HabitAccessControlIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/HabitAccessControlIT.java) | A create request carrying another user's habit id, or its own XP, cannot take over that habit or grant itself a reward. Users only see their own habits and get 403 on anyone else's; requests without a valid token get 401. |
| [`RequestValidationIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/RequestValidationIT.java) | Blank names, out-of-range goals, unknown enum values, malformed JSON, bad emails, and short passwords are refused with a problem document and write nothing. |
| [`OpenApiContractIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/OpenApiContractIT.java) | The committed `openapi.json` matches what the running API serves. |
| [`CompletionOutboxIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/CompletionOutboxIT.java) | The completion and its event are written together and the relay publishes the event; if the event cannot be written, the completion is rolled back with it; 20 simultaneous completions leave exactly one event; an event written while the queue is down is delivered once it is back. |
| [`OutboxRelayIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/OutboxRelayIT.java) | A relay skips rows another relay has locked instead of waiting for them; six relays released together publish each of 30 events exactly once; the purge removes old published events and never an unpublished one. |
| [`CompletionPipelineIT`](backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/CompletionPipelineIT.java) | The worker grants XP, streaks, and achievements from a queued event; duplicate deliveries grant the reward once; malformed messages are deleted; a failure mid-processing rolls back and the redelivery succeeds; a message that always fails moves to the dead-letter queue after 5 attempts. |
| [`ConcurrentProcessingIT`](backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/ConcurrentProcessingIT.java) | Several workers at once: the same event handled twice rewards and emails once; two days of one habit handled together lose no XP; different habits of one new user unlock the first achievement once. All of these failed before the event-id table and the per-user lock. Also: an older day handled late still earns its streak without rewinding the current one, and a reward does not undo an edit made meanwhile. |
| [`StreakQueryIT`](backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/StreakQueryIT.java) | The single-query streak calculation gives the same answer as counting back one day (or week) at a time, on 120 random completion histories, including across a year boundary. |
| [`AchievementUnlockIT`](backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/AchievementUnlockIT.java) | The XP achievement unlocks when a user's total across habits reaches 100, the streak achievement on the seventh day in a row, and each only once. |
| [`StreakResetIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/StreakResetIT.java) | The nightly streak reset, a single UPDATE, zeroes exactly the streaks that have lapsed and changes nothing else. |

**End-to-end**: `scripts/smoke-test.sh` drives the Docker Compose stack over HTTP and waits for
the worker's reward to appear, covering the hop between the two services.

**CI** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs all of the above on every
pull request and every push to `main`, along with a JaCoCo line-coverage gate, the frontend lint, build, and generated-types check, and
`terraform validate`.

## Load tests

[`load/run.sh`](load/run.sh) drives the Docker Compose stack with [k6](https://k6.io/) and then
checks the database for what the pipeline promises: every completion rewarded, exactly once.
How to run it is in [`load/README.md`](load/README.md); recorded numbers are in
[`load/RESULTS.md`](load/RESULTS.md). On one laptop:

- **Contention.** 8,000 requests completing the same habit, 200 and then 1,000 in flight at a
  time: all 8,000 succeeded, with one completion and one reward recorded.
- **Failures under load.** At 100 completions per second, the worker was killed and the queue
  frozen mid-run. No request failed, and afterwards every completion had been rewarded exactly once.
- **Sync versus async.** At the same request rate, async mode lowered average completion latency
  by 43 to 55% and p99 by 73 to 95% across three runs.
- **Optimisations it led to.** The worker's time to apply a reward went from 14.2 ms to 7.7 ms
  for a one-day streak and from 77.6 ms to 6.0 ms for a 365-day streak (one window-function query
  for the streak, and about ten achievement queries cut to three). The nightly streak reset over
  20,000 habits went from 5.1 s to 0.2 s (one UPDATE instead of 20,000).

## Scheduled automations (infra/)

Two batch jobs that don't belong on the request path:

- **Nightly streak reset** — a habit's `currentStreak` is normally only recalculated on its next
  completion, so a habit a user abandoned keeps showing a stale streak indefinitely. This job
  zeroes it out once the gap is long enough (`StreakResetService`), in a single UPDATE.
- **Weekly summary** — aggregates each user's completions/XP for the past week
  (`WeeklySummaryService`).

Both are implemented as pure service logic in the API (tested with no AWS needed —
`StreakResetServiceTest`, `StreakResetIT`, `WeeklySummaryServiceTest`) behind internal endpoints, plus a thin
invocation layer meant to run on AWS:

- `infra/lambda/` — stdlib-only Python handlers that POST to the internal endpoints. Verified
  locally by invoking them as plain Python functions against a running API instance (see git
  history) — real behavior, not just read-through.
- `infra/terraform/` — EventBridge schedules, the two Lambda functions, a minimal logs-only IAM
  role, and an SQS dead-letter queue + redrive policy for the completion queue. Runs clean through
  `terraform init` and `terraform validate` with no AWS credentials. **Not applied** — `terraform
  plan`/`apply` need a real AWS account and haven't been run, so treat this as reviewed,
  syntactically-valid IaC rather than verified infrastructure.

## Roadmap (not yet built)

- Deploying the Terraform (`terraform apply`) against a real AWS account
- Full AWS deployment for the app itself (RDS, ECS Fargate behind ALB, S3/CloudFront for the frontend, SSM for config)
- CloudWatch dashboards for queue depth and worker error rate
