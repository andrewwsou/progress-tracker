# Progress Tracker

[![CI](https://github.com/andrewwsou/progress-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/andrewwsou/progress-tracker/actions/workflows/ci.yml)

A habit-tracking app with streaks, XP, and achievements, built to demonstrate an event-driven
backend: habit completions are recorded synchronously but rewarded (streaks/XP/achievements)
asynchronously via a queue-backed worker service, so the API response never waits on reward
computation.

## Architecture

```
                 ┌─────────────┐        1. write completion event
  React client ─▶│  API        │───────────────────────────┐
  (Vite, :5173)  │  (Spring    │        2. return           ▼
                 │   Boot,     │        immediately    ┌──────────┐
                 │   :8080)    │◀───────────────────── │ Postgres │
                 └──────┬──────┘                       └──────────┘
                        │ 3. enqueue (background thread,               ▲
                        │    off the request path)                     │
                        ▼                                              │
                 ┌─────────────┐                                       │
                 │   AWS SQS   │                                       │
                 └──────┬──────┘                                       │
                        │ 4. long-poll                                 │
                        ▼                                              │
                 ┌─────────────┐        5. compute streak/XP/          │
                 │   Worker    │           achievements, persist ──────┘
                 │  (Spring    │
                 │   Boot)     │────────▶ 6. queue completion email (SES/log)
                 └─────────────┘
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
- **`queue.enabled=true`**: the API writes a zero-XP completion row, enqueues
  `{userId, habitId, date}` to SQS on a background thread pool (not the request thread), and
  returns immediately. The worker long-polls SQS, computes streak/XP/achievement state, persists
  it, and queues a completion email.

**Reliability:**
- SQS message processing in `CompletionProcessor` is **idempotent per `(habit, date)`** — a
  redelivered message (SQS is at-least-once) is a no-op rather than double-granting XP. Covered
  by [`CompletionProcessorTest`](backend/progress-worker/src/test/java/com/progresstracker/progressworker/service/CompletionProcessorTest.java).
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
thread.

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
| [`CompletionEnqueueIT`](backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/CompletionEnqueueIT.java) | In async mode the API records a zero-XP completion and publishes the event to the queue through the real AWS SDK client. |
| [`CompletionPipelineIT`](backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/CompletionPipelineIT.java) | The worker grants XP, streaks, and achievements from a queued event; duplicate deliveries grant the reward once; malformed messages are deleted; a failure mid-processing rolls back and the redelivery succeeds; a message that always fails moves to the dead-letter queue after 5 attempts. |

**End-to-end**: `scripts/smoke-test.sh` drives the Docker Compose stack over HTTP and waits for
the worker's reward to appear, covering the hop between the two services.

**CI** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs all of the above on every
pull request and every push to `main`, along with a JaCoCo line-coverage gate, the frontend lint, build, and generated-types check, and
`terraform validate`.

## Scheduled automations (infra/)

Two batch jobs that don't belong on the request path:

- **Nightly streak reset** — a habit's `currentStreak` is normally only recalculated on its next
  completion, so a habit a user abandoned keeps showing a stale streak indefinitely. This job
  zeroes it out once the gap is long enough (`StreakResetService`).
- **Weekly summary** — aggregates each user's completions/XP for the past week
  (`WeeklySummaryService`).

Both are implemented as pure service logic in the API (unit tested, no AWS needed —
`StreakResetServiceTest`, `WeeklySummaryServiceTest`) behind internal endpoints, plus a thin
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
