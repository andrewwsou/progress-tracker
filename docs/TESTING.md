# Testing

What the test suites cover and how to run them. See also [load/RESULTS.md](../load/RESULTS.md) for the load tests.

Run from the repository root:

```
# Unit tests only: fast, no Docker or database needed
(cd backend/progresstracker && ./mvnw test)
(cd backend/progress-worker && ../progresstracker/mvnw test)

# Unit + integration tests + coverage gate: needs Docker running
(cd backend/progresstracker && ./mvnw clean verify)
(cd backend/progress-worker && ../progresstracker/mvnw clean verify)

# Frontend (Vitest, no server needed) and the Lambda handlers (Python 3, stdlib only)
(cd frontend && npm install && npm test)
python3 -m unittest discover -s infra/lambda -p "test_*.py"
```

**Unit tests** (`*Test`, Mockito) cover streak/XP calculation, goal-period no-ops, JWT handling,
the sign-in throttle, the outbox relay, the event streams, and idempotent message processing.
`SqsPollerTest` runs the poller
against a fake queue: it never holds more messages than idle threads, a stop lets the messages in
flight finish before the client closes, a message still running at the deadline is left for
redelivery, and failed polls back off.

**Frontend tests** (`cd frontend && npm test`, [Vitest](https://vitest.dev/) with jsdom, no
network) cover the hand-written event-stream reader (keep-alive comments, events split across
chunks, CRLF line endings, a stream that goes silent), reconnecting with backoff and stopping when
evicted, readable messages for network failures and `429`, and the dashboard's rules: a stale
response never overwrites a newer one, a failed load offers a retry instead of looking like an
empty account, an expired session says so, and focus returns where it was.

**Integration tests** (`*IT`, [Testcontainers](https://testcontainers.com/)) start a real
PostgreSQL and an SQS-compatible broker in Docker and run the actual services against them:

| Test | What it proves |
|---|---|
| [`HabitCompletionConcurrencyIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/HabitCompletionConcurrencyIT.java) | 50 simultaneous completions of one habit over HTTP all return 200, write exactly one row, and grant XP once. In sync mode the row locks (the user's, then the habit's) make the requests take turns; the unique-constraint race itself is reproduced on the async path by `CompletionOutboxIT`. |
| [`HabitAccessControlIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/HabitAccessControlIT.java) | A create request carrying another user's habit id, or its own XP, cannot take over that habit or grant itself a reward. Users only see their own habits and get 403 on anyone else's; requests without a valid token get 401. |
| [`RequestValidationIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/RequestValidationIT.java) | Blank names, out-of-range goals, unknown enum values, malformed JSON, bad emails, and short passwords are refused with a problem document and write nothing. |
| [`OpenApiContractIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/OpenApiContractIT.java) | The committed `openapi.json` matches what the running API serves. |
| [`CompletionOutboxIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/CompletionOutboxIT.java) | The completion and its event are written together and the relay publishes the event; if the event cannot be written, the completion is rolled back with it; 20 simultaneous completions leave exactly one event; an event written while the queue is down is delivered once it is back. |
| [`OutboxRelayIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/OutboxRelayIT.java) | A relay skips rows another relay has locked instead of waiting for them; six relays released together publish each of 30 events exactly once; the purge removes old published events and never an unpublished one. |
| [`CompletionPipelineIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/CompletionPipelineIT.java) | The worker grants XP, streaks, and achievements from a queued event; duplicate deliveries grant the reward once; malformed messages are deleted; a failure mid-processing rolls back and the redelivery succeeds; a message that always fails moves to the dead-letter queue after 5 attempts; a user whose reward is stuck on a lock does not hold up another user's (this fails with one thread); health and metrics report the worker. |
| [`HabitEventNotifierIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/HabitEventNotifierIT.java) | A reward applied from the queue notifies the API once it has committed; a notification sent in a transaction that rolls back is never delivered. |
| [`EventStreamIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/EventStreamIT.java) | A notification reaches the open event stream of the user it names and no one else's; the stream needs a login; signing out ends the user's open streams, on this instance and, through `NOTIFY`, on every other, and the old token cannot reopen one. |
| [`StreakResetRaceIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/StreakResetRaceIT.java) | The streak reset committing in the middle of a reward cannot leave the just-completed habit with a zero streak. The test forces the interleaving with a second connection; the previous code failed it. |
| [`ConcurrentProcessingIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/ConcurrentProcessingIT.java) | Several workers at once: the same event handled twice rewards once and records one completion-email notice; two days of one habit handled together lose no XP; different habits of one new user unlock the first achievement once. All of these failed before the event-id table and the per-user lock. Also: an older day handled late still earns its streak without rewinding the current one, and a reward does not undo an edit made meanwhile. |
| [`StreakQueryIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/StreakQueryIT.java) | The single-query streak calculation gives the same answer as counting back one day (or week) at a time, on 120 random completion histories, including across a year boundary. |
| [`AchievementUnlockIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/AchievementUnlockIT.java) | The XP achievement unlocks when a user's total across habits reaches 100, the streak achievement on the seventh day in a row, and each only once. |
| [`StreakResetIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/StreakResetIT.java) | The streak reset, a single UPDATE, zeroes exactly the streaks that have lapsed and changes nothing else; the day and week boundaries fall in the right place (including ISO week 53); the same history lapses for an owner on Kiritimati (UTC+14) but not for one in Los Angeles; an owner whose zone PostgreSQL does not know is judged on UTC instead of failing the reset for everyone. |
| [`TimeZoneIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/TimeZoneIT.java) | With the clock fixed at 02:30 UTC, a completion counts for 4 October in Los Angeles and 5 October in UTC; moving the account to another zone moves its "today"; unknown zones are refused. |
| [`CompletionDuringStreakResetIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/CompletionDuringStreakResetIT.java) | In sync mode, the streak reset committing in the middle of a completion cannot leave the habit with a zero streak. Forced with a second connection; the previous code failed it. |
| [`WeeklySummaryIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/WeeklySummaryIT.java) | The weekly job needs the internal token, asks once per active user (a repeated run adds nothing), and users read only their own newest finished summary. |
| [`WeeklySummaryJobIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/WeeklySummaryJobIT.java) | The worker against WireMock standing in for the Claude API: a summary is written and its cost recorded; with retries turned on for the test (production uses 0), a timeout or server error is retried once (both attempts count against the budget), and if the retry fails too the template writes the summary; refusals, an invented focus habit, and invented numbers fall back too; the daily token budget stops calls; expired claims are taken over; a stale worker cannot overwrite a newer claim; two workers write each summary once. |
| [`AuthenticationIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/AuthenticationIT.java) | An email matches however it is capitalised, a second account in different case is refused, and a token issued before emails were lower-cased still works; the sixth failed sign-in from one address gets 429 with `Retry-After`, even with the right password, while the owner can still sign in from another address; guesses sent all at once get no more tries; signing out ends every session of that user and no one else's. |
| [`HabitListIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/HabitListIT.java) | Habits stay in creation order after completions and edits; the list costs the same number of SQL statements for 1 habit as for 10; a 101st habit is refused. |
| [`StreakHistoryIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/StreakHistoryIT.java) | A sync completion counts the streak from history, as the worker does: after a daily habit is switched to weekly its streak is counted in weeks, and runs carry across the ISO-year boundary. |
| [`CorsIT`](../backend/progresstracker/src/test/java/com/progresstracker/progresstracker/integration/CorsIT.java) | The frontend's dev and preview origins may call the API; other origins may not. |
| [`LlmDailyUsageIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/LlmDailyUsageIT.java) | The daily cap's reservation SQL, run on PostgreSQL: the token cap refuses a call once the day has reserved too much, including the day's first call, and a call cap of 0 refuses every call. |
| [`ProcessedEventPurgeIT`](../backend/progress-worker/src/test/java/com/progresstracker/progressworker/integration/ProcessedEventPurgeIT.java) | Old event ids are purged in batches while every id the queue could still redeliver is kept; an event redelivered after its id was purged still earns no second reward. |

**End-to-end**: `scripts/smoke-test.sh` drives the Docker Compose stack over HTTP and waits for
the worker's reward to appear, then asks for this week's summary and waits for the worker to
write it, covering both paths between the two services.

**CI** ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs all of the above on every
pull request and every push to `main`, along with a JaCoCo line-coverage gate, the frontend lint,
tests, build, and generated-types check, `terraform validate`, and the Lambda handler tests.
[CodeQL](../.github/workflows/codeql.yml) scans the Java, TypeScript, and Python code and the workflows
themselves for security bugs on every pull request and weekly. The workflows pin every action to a
full commit SHA, so a moved tag cannot change what runs.
[Dependabot](../.github/dependabot.yml) opens weekly pull requests for minor and patch dependency
updates (Maven, npm, Terraform providers) and for every new release of the GitHub Actions the
workflows use, majors included (it updates the SHA and its version comment together), which CI
then checks.
