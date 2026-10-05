# ProgressArc design notes

How the parts of ProgressArc work and why they were built this way. The [README](../README.md)
has the overview; this file has the detail.

- [Completion pipeline](#completion-pipeline)
- [Each user's own calendar](#each-users-own-calendar)
- [Live updates](#live-updates)
- [The worker under load and in operation](#the-worker-under-load-and-in-operation)
- [Measured impact of async mode](#measured-impact-of-async-mode)
- [API](#api)
- [Database schema](#database-schema)
- [Weekly summaries (Claude)](#weekly-summaries-claude)
- [Scheduled automations (infra/)](#scheduled-automations-infra)

## Completion pipeline

`POST /api/habits/{id}/complete` behaves differently depending on `queue.enabled`:

- **`queue.enabled=false`** (the default outside Docker Compose): streak, XP, and achievement unlocks are computed
  inline and returned in the response. The API takes the same locks in the same order as the
  worker (the user's row, then the habit's) and counts the streak from the completion history
  with the worker's queries, so both modes give the same streak, XP, and achievements.
- **`queue.enabled=true`**: the API writes a zero-XP completion row and an event describing it
  (`{eventId, userId, habitId, date, occurredAt}`) in one database transaction, then returns
  immediately. A relay publishes the event to SQS. The worker long-polls SQS, computes
  streak/XP/achievement state, persists it, and records a completion-email notice (a log line for
  now; no mail provider is connected). The reward is
  therefore eventually consistent: the response shows the completion, and the XP, streak, and
  any achievement appear after the relay's next run (`OUTBOX_RELAY_DELAY_MS`, default 500 ms)
  plus queue and worker time.

### Reliability

The pipeline applies each completion's reward **exactly once**, even though the
queue only promises at-least-once, unordered delivery.
- **No lost events (transactional outbox).** Writing to the database and then sending to a queue
  are two separate systems; if the send fails after the commit, the reward is lost. So the API
  never sends on the request path. The event goes into an `outbox_events` table in the same
  transaction as the completion, and [`OutboxRelay`](../backend/progresstracker/src/main/java/com/progresstracker/progresstracker/outbox/OutboxRelay.java)
  publishes unpublished rows on a timer (`FOR UPDATE SKIP LOCKED`, so several API instances can
  relay at once). If the queue is down or the API restarts mid-send, the row is simply still
  there for the next run. A request that loses the unique-constraint race fails on the
  completion row before it writes an event, so there is exactly one event per completion.
  The relay and the worker's rewards only run in async mode. Before switching `QUEUE_ENABLED` to
  false, stop new completions and keep the API and the worker running in async mode until
  `select (select count(*) from habit_entries where xp_earned = 0) + (select count(*) from outbox_events where published_at is null)`
  returns 0; then switch. A completion still unrewarded at the switch keeps 0 XP: sync mode
  treats it as already done and never rewards it.
- **No double rewards (idempotent consumer).** The worker records each event id in a
  `processed_events` table in the same transaction as the reward, so a redelivered message is
  recognised and ignored. Then, holding a row lock on the user, it rewards a completion only if
  that completion has no XP yet. That check is what guarantees one reward per completion, even
  if the same completion arrives as two different events. If processing fails, everything rolls
  back, including the event id, and the redelivery tries again. Event ids are kept 15 days, longer
  than SQS keeps a message (14), and then purged in batches.
- **No lost updates.** The user lock makes reward transactions for one user run one at a time,
  so two workers cannot overwrite each other's totals. The API and the worker write different
  columns of the same habit row, and each updates only the columns it changed, so an edit
  cannot erase a reward or the other way round. The worker writes the reward columns in one
  `UPDATE` computed from the row as it is at that moment, so the streak reset running
  mid-reward cannot leave a just-completed habit with a zero streak
  ([`StreakResetRaceIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/StreakResetRaceIT.java)
  forces that interleaving; the earlier read-modify-write failed it). The synchronous path closes
  the same race the other way: it locks the habit's row and reads it again inside the transaction
  before computing the streak ([`CompletionDuringStreakResetIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/CompletionDuringStreakResetIT.java)). Achievement unlocks are inserts that do nothing
  on conflict, so racing unlocks cannot fail.
- **Order does not matter.** The current streak only moves forward and the 7-day-streak
  achievement is judged on the longest streak, so an older event arriving late (a retry, or a
  redrive from the dead-letter queue) gives the same result as arriving on time.
- The poller distinguishes **poison messages** (malformed payload: deleted immediately, since retrying
  can't help) from **transient processing failures** (left on the queue so SQS redelivers after
  the visibility timeout; safe because processing is idempotent). A message that fails 5 times
  moves to the **dead-letter queue**, and a CloudWatch alarm fires as soon as one is there.

## Each user's own calendar

A completion, a goal, and a streak all count in the user's own days:
a habit done at 8 pm in Los Angeles belongs to that day, though it is already tomorrow in UTC.
The browser sends its IANA time zone at sign-up and keeps it current (`PUT /api/me`). A zone is
accepted only if both Java and PostgreSQL know it, since the reset below runs in PostgreSQL; a
stored zone PostgreSQL no longer knows is judged on UTC rather than failing the reset for
everyone. The API works out "today" from one injectable `Clock` and that zone
([`UserCalendar`](../backend/progresstracker/src/main/java/com/progresstracker/progresstracker/service/UserCalendar.java)).
The streak reset stays one `UPDATE` in which PostgreSQL computes every owner's local date
(`now AT TIME ZONE u.time_zone`), and it runs hourly, so it reaches each zone within an hour of
its midnight. A completion keeps the date it had in the zone where it was made. So after
moving west, a habit can already be done "tomorrow" (it counts as done, and its streak never moves
backwards); after moving east, a streak kept every day can lose one day.

## Live updates

When a reward (or a weekly summary) has committed, the worker runs
`pg_notify('habit_events', ...)`, so a browser is never told about a reward that did not happen.
It runs just after the commit rather than inside the reward transaction: a transaction that sends
`NOTIFY` holds a database-wide lock while it commits, and inside the reward transaction that lock
made the eight worker threads' commits queue behind each other (see load/RESULTS.md section 7).
The cost is that a crash between commit and notify loses one notification, which is only a hint. Each API instance holds one `LISTEN` connection outside the pool and
forwards an event to the open streams of the user it names
([`PostgresEventListener`](../backend/progresstracker/src/main/java/com/progresstracker/progresstracker/events/PostgresEventListener.java),
[`UserEventStreams`](../backend/progresstracker/src/main/java/com/progresstracker/progresstracker/events/UserEventStreams.java)).
An event only says "something changed"; the page then reads the new state through the normal
endpoints, so a missed event costs a moment of staleness, never wrong data, and the page also
refreshes whenever its stream reconnects. The browser reads the stream with `fetch` because
`EventSource` cannot send the `Authorization` header. Because notifications sent while nobody
listens are lost, the listener tells every open stream to re-read after each reconnect, and it
checks its connection every 30 seconds so a database that vanished without closing it is noticed.
A user keeps at most five streams (opening a sixth closes the oldest, which is told it was
`evicted` and stays closed until its tab is shown again, so two tabs never take turns evicting
each other), a hidden tab drops its
stream and catches up when shown again, and the client reconnects with backoff if the stream goes
silent. On shutdown the streams are closed before the web server's graceful shutdown, which would
otherwise wait out its timeout for streams that never end.

## The worker under load and in operation

- **Bounded concurrency with backpressure.** Messages are processed by a fixed pool of 8 threads
  (`WORKER_CONCURRENCY`). The poller only asks SQS for as many messages as there are idle threads,
  so a received message never waits in memory while its visibility timeout runs out. One user's
  rewards still apply one at a time (the row lock), so a slow or stuck user no longer holds up
  everyone else. Startup fails if the threads could exhaust the database pool (`DB_POOL_SIZE` − 2).
- **Graceful shutdown.** On SIGTERM the worker stops polling, lets the messages in flight finish
  (up to 25 s), and only then closes its queue client and database pool. Anything still running
  at the deadline is simply redelivered. A failing queue is retried with exponential backoff.
- **Health and metrics.** `/actuator/health` (Docker `HEALTHCHECK`, and a liveness group that
  fails if the poll loop dies or stalls) and `/actuator/prometheus` on port 8081, which Compose
  does not publish. Metrics: events by outcome (`worker_events_total{outcome=applied|skipped|invalid|failed}`),
  processing time and end-to-end lag as histograms, messages in flight, and poll errors.
- **Alarms** ([`infra/terraform/sqs.tf`](../infra/terraform/sqs.tf)): any message in the dead-letter
  queue, and an oldest message older than 5 minutes (the worker is down or falling behind). The
  queue keeps a message for 14 days, the most SQS allows, and nothing sends an expired event
  again, so that is how long the worker can be down before rewards are lost. The alarms notify
  the SNS topic in `alarm_topic_arn`; left blank, they only show in CloudWatch, so a real
  deployment must set it.
- **Racing completions on the API side.** In async mode, two requests completing the same habit at
  once can both pass the "already completed?" check; the loser hits the `(habit_id, completed_date)`
  unique constraint, and `HabitController` returns the current state instead of an error. In sync
  mode the user's row lock and then the habit's (the worker's order, so the two modes cannot
  deadlock) make such requests take turns. Covered by
  [`HabitControllerTest`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/controller/HabitControllerTest.java)
  (unit) and `CompletionOutboxIT` (real database, async); under the load harness, 8,000 requests
  against one habit all succeed in both modes ([load/RESULTS.md](../load/RESULTS.md) section 1).

## Measured impact of async mode

Instrumented both code paths with request-level timing
(`COMPLETION_LATENCY` log lines in `HabitController`) and measured 20 completions per mode
locally against real Postgres:

| Mode | Avg latency |
|---|---|
| Sync (`queue.enabled=false`) | ~12.0ms |
| Async (`queue.enabled=true`) | ~4.5ms |

**~63% reduction**, driven by moving the ~10-query achievement-evaluation pass off the request
thread. The transactional outbox added later puts one more insert in the async request's
transaction; measured before and after that change, the async figure did not move.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | JWT-based auth (emails are matched without regard to case) |
| POST | `/api/auth/logout` | Sign out everywhere: every token issued to the caller stops working (204) |
| GET/POST | `/api/habits` | List the caller's habits, oldest first; create one (201; at most 100 per account) |
| PUT/DELETE | `/api/habits/{id}` | Edit or delete (204) a habit the caller owns |
| POST | `/api/habits/{id}/complete` | Record a completion (sync or async, see Completion pipeline) |
| GET | `/api/achievements` | Unlocked achievements for the current user |
| GET | `/api/summaries/latest` | The caller's newest finished weekly summary (204 if none yet) |
| GET/PUT | `/api/me` | The caller's account: email and time zone (the browser keeps the zone current) |
| GET | `/api/events` | Live updates for the caller as server-sent events: `ready`, then `reward` and `summary` as the worker finishes them; `resync` (re-read everything) and `evicted` (this stream was replaced by a newer one) |
| POST | `/api/internal/automations/reset-streaks` | Zero out streaks for habits nobody completed recently. Auth: `X-Internal-Token` header, not JWT; it is meant for the scheduled Lambda in `infra/lambda`, not end users. |
| POST | `/api/internal/automations/weekly-summary` | Ask for last week's summary for every active user (optional `?weekStart=` for any other week). Same auth model. |

### Contract

The API is described by an OpenAPI document served at `/v3/api-docs`, browsable at
`/swagger-ui.html`, and committed as [`backend/progresstracker/openapi.json`](../backend/progresstracker/openapi.json).
The frontend's TypeScript types are generated from that file (`npm run generate:api`), and CI fails
if either the committed contract or the generated types drift from the running API.

### Input and errors

Requests bind to dedicated request objects with Bean Validation, never to JPA
entities, so a client cannot set a habit's id, owner, XP, or streak. Errors from the controllers, validation,
and authentication are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents
(`application/problem+json`); validation failures list a message per field under `errors`. A missing or invalid token is `401`,
acting on someone else's habit is `403`, and an unknown habit is `404`. A goal the habit can never
reach (say, three times a day when a second completion on the same day does nothing) is refused
with `400`, and a 101st habit with `409`.

### Sign-in

Emails are stored in lower case behind a unique index on `lower(email)`, so one
mailbox has one account however it is typed. Failed sign-ins are counted in a 15-minute window
([`LoginThrottle`](../backend/progresstracker/src/main/java/com/progresstracker/progresstracker/security/LoginThrottle.java)):
5 for one email from one client address, 100 for one email from anywhere, and 50 from one
address. Past a limit, sign-in answers `429` with a `Retry-After` header, even for the right
password. Someone guessing from one address therefore cannot lock the owner out from another. An
attempt is counted before its password is checked, so guesses sent all at once get no more tries
than guesses sent one by one. An unknown email still costs one bcrypt check, so response time
does not reveal which emails have accounts. Each token carries the user's token version;
`POST /api/auth/logout` raises it, which ends every session that user has, and closes their open
event streams on every API instance (through the same `NOTIFY` channel). The browser origins
allowed to call the API come from
`APP_CORS_ALLOWED_ORIGINS` (the Vite dev and preview servers by default).

## Database schema

The schema is defined by versioned SQL migrations in
[`backend/progresstracker/src/main/resources/db/migration`](../backend/progresstracker/src/main/resources/db/migration),
which the API applies with [Flyway](https://flywaydb.org/) when it starts. Both services run
Hibernate with `ddl-auto=validate`: they never change the schema, and they refuse to start if
their entities no longer match it. That matters here because the API and the worker each keep
their own copies of the shared entities; a column one side renames without a migration now fails
at startup (and in every integration test) instead of silently creating a second column. The
worker's tests build their database from the same migration files.

`V1__baseline.sql` is the schema Hibernate used to create, checked column by column against a
database it created. A local database from before migrations is adopted as V1 automatically.
To change the schema, add the next `V<n>__description.sql`; never edit one that has been applied.
Since then: V2 adds each user's time zone, V3 stores emails in lower case behind a unique index on
`lower(email)`, V4 seeds the achievements (so several replicas starting at once cannot race to
insert them), V5 adds the token version that signing out raises, and V6 indexes
`processed_events.processed_at` for the worker's purge. V3 stops with a clear message if two
existing accounts differ only in case (possible before it, since sign-up compared emails
exactly); merge them by hand, and after migrating raise the kept account's `token_version` so a
token of the removed one cannot sign in to it. Tokens issued before V3 keep working: the API
looks their subject up in lower case.

## Weekly summaries (Claude)

Every week each active user gets a short summary of their week in the dashboard's side panel,
written by Claude when it is switched on, and by a template when it is off or anything fails.

```
 EventBridge (weekly) -> Lambda -> POST /api/internal/automations/weekly-summary
      -> one PENDING row per active user in weekly_summaries (one INSERT ... SELECT, repeats ignored)
 worker timer -> claim rows (FOR UPDATE SKIP LOCKED, with a lease) -> read the user's week (one query)
      -> Claude writes it (structured output) -> validate it -> READY        -> GET /api/summaries/latest
                         \-> any failure -> template writes it instead -/
```

**Why the work is split this way.** A model call takes seconds. The API only records the requests,
so the scheduled job returns at once. The worker writes the summaries on its own timer thread,
so a slow model call never delays the queue poller that applies rewards.

**Guardrails.**
- **Structured output.** The answer is constrained to a JSON schema (`headline`, `body`,
  `focusHabit`), so it always parses.
- **Validated against the data.** The focus habit must be one of the user's habits, spelled
  exactly. Every number written in digits in the headline or body must appear in the week's data,
  and number words are rejected unless they are part of one of the user's habit names: zero to
  twenty, the tens, hundred, thousand, dozen, once, twice, thrice, and the ordinals from third
  ("first" and "second" are allowed; they rarely count anything here). Other habit names mentioned
  in the body are not checked. A summary that fails a check is replaced by the template.
- **Off unless switched on.** The worker makes no Claude calls unless `SUMMARY_LLM_ENABLED=true`
  *and* `ANTHROPIC_API_KEY` are both set, and the model is Opus 5.5 or Sonnet 5.5. A key that is
  merely present in your shell does nothing. Tests, CI, and the load harness pin it off.
- **Hard daily caps.** At most 10 API requests and 100,000 tokens per UTC day, across all
  workers (`summary.llm.max-calls-per-day`, `daily-token-budget`). Each call is reserved before
  it is sent, at its worst case (prompt size plus the output cap, doubled if a fallback model may
  re-run it), in one atomic `INSERT ... ON CONFLICT DO UPDATE ... WHERE` on a per-day row, and
  never refunded, so timeouts, errors, and two workers racing all count. A week with more than
  20 habits or a prompt over 8 KB goes to the template. Worst case with the defaults: about
  $1.25 a day at Opus 5.5 prices ($4/$20 per million tokens; a refusal fallback, below, is billed
  at the fallback model's rates); a weekly summary for one user costs about a cent. Setting the
  call cap to 0 stops every call.
- **Always a summary.** Claude off, a cap reached, a timeout, a server error, a refusal, a
  truncated answer, a failed validation, or an unexpected error: in every case a
  deterministic template writes the summary instead, and the reason is stored with it
  (`fallback_reason`). API errors are also logged with the API's message.
- **Bounded time.** Low effort, a cap of 2,048 output tokens, and a 30-second timeout with no
  retries (a failed call means a template summary, not another paid attempt). Tokens (summed over
  every attempt when a fallback model answered), latency, and the model that answered are stored
  on every row, and the day's recorded tokens are checked against the budget too.
- **Refusal fallback.** Requests opt into server-side fallbacks (`fallbacks: "default"`): if the
  model declines, the API may re-run the request on Anthropic's recommended fallback model, which
  is not one of the two models above and is billed at its own rates. That attempt still counts
  against the daily token budget: the reservation is doubled for it, and its tokens are recorded.
- **Safe to run on several workers.** Rows are claimed in batches with `FOR UPDATE SKIP LOCKED`
  and a lease, and each row's lease is renewed just before its summary is written, so a slow
  batch is not claimed twice. A worker that crashes leaves its claim to expire, and another takes
  the row over. The claim's attempt number works as a fencing token, so a worker that stalled past
  its lease cannot overwrite the newer copy. After 3 attempts a row is marked FAILED; asking for
  that week again queues it once more.
- **Privacy and prompt injection.** Only habit names and numbers are sent, never the user's email.
  Habit names are user-written text, so the week goes in a tagged JSON block and the prompt says
  to treat names as data. Whatever comes back still has to pass validation.

**Running it.** Set both `SUMMARY_LLM_ENABLED=true` and `ANTHROPIC_API_KEY` for the worker (see
`.env.example`); otherwise every summary comes from the template and nothing is sent. The model
is `claude-opus-5-5` by default (`SUMMARY_LLM_MODEL=claude-sonnet-5-5` is cheaper). The smoke test
asks for this week's summaries, and you can too. The token below is the Compose stack's
placeholder; an API started with `./mvnw` answers `401` unless `AUTOMATION_TOKEN` was set to the
same value. A week is summarized once, so a summary of the current week covers only the days so far:

```
curl -X POST -H "X-Internal-Token: local-development-only-token" \
  "http://localhost:8080/api/internal/automations/weekly-summary?weekStart=$(date -u +%F)"
```

**Tests** run without a key: unit tests cover the validator, the template, and every branch of
the AI-or-template decision, and `WeeklySummaryJobIT` runs the real Claude SDK against WireMock
(see its row in [TESTING.md](TESTING.md)).

## Scheduled automations (infra/)

Two batch jobs that don't belong on the request path:

- **Hourly streak reset.** A habit's `currentStreak` is normally only recalculated on its next
  completion, so a habit a user abandoned keeps showing a stale streak indefinitely. This job
  zeroes it out once the gap is long enough on the owner's own calendar (`StreakResetService`),
  in a single UPDATE; running hourly reaches every time zone within an hour of its midnight.
- **Weekly summary.** Asks for each active user's summary of the past week
  (`WeeklySummaryService`); the worker writes them (see "Weekly summaries" above).

Both are implemented as service logic in the API (tested with no AWS needed:
`StreakResetServiceTest`, `StreakResetIT`, `WeeklySummaryIT`) behind internal endpoints, plus a thin
invocation layer meant to run on AWS:

- `infra/lambda/`: stdlib-only Python handlers that POST to the internal endpoints.
  [`test_handlers.py`](../infra/lambda/test_handlers.py), run in CI, invokes each handler as a plain
  Python function against a local HTTP server standing in for the API and checks the path, the
  method, the `X-Internal-Token` header, the result, and that a rejected token (401) fails the
  invocation.
- `infra/terraform/`: EventBridge schedules, the two Lambda functions, a minimal logs-only IAM
  role, an SQS dead-letter queue + redrive policy for the completion queue (14-day retention),
  and CloudWatch alarms on the dead-letter queue, on the age of the oldest message, and on errors
  in either scheduled Lambda, all notifying `alarm_topic_arn` (which a real deployment must set).
  `automation_token` must be at least 32 characters and not the example placeholder. Runs clean
  through `terraform init` and `terraform validate` with no AWS credentials. **Not applied:** `terraform
  plan`/`apply` need a real AWS account and haven't been run, so treat this as reviewed,
  syntactically-valid IaC rather than verified infrastructure.
