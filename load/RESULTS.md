# Load test results

Recorded on 2026-10-04 with `./load/run.sh` (see [README.md](README.md) for what each scenario
does). Every run below passed all of its k6 thresholds and database checks.

**Machine.** One laptop running everything: Apple M1 Pro (8 cores), Docker Desktop with 8 CPUs
and 8 GB, PostgreSQL 16, k6 2.3.0. The load generator shares the CPUs with the system under
test, so read these as comparisons, not as capacity figures.

## 1. Many requests for the same habit

8,000 requests completing one habit. All of them must succeed, and the habit must end up
completed once and rewarded once.

| In flight at a time | Mode | Succeeded | Requests/s | p50 | p95 | p99 | Completions recorded | Rewards |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 200 | sync | 8,000 of 8,000 | 805 | 192 ms | 776 ms | 1,297 ms | 1 | 1 |
| 200 | async | 8,000 of 8,000 | 700 | 200 ms | 920 ms | 1,463 ms | 1 | 1 |
| 1,000 | sync | 8,000 of 8,000 | 769 | 1,178 ms | 3,390 ms | 3,962 ms | 1 | 1 |
| 1,000 | async | 8,000 of 8,000 | 690 | 1,217 ms | 3,955 ms | 4,481 ms | 1 | 1 |

No request failed at either level, and the unique constraint on `(habit_id, completed_date)`
let exactly one through. In async mode there was also exactly one outbox event, so the worker
was asked to reward the habit once, not 8,000 times.

Throughput stays near 700 to 800 requests per second while latency grows with the number in
flight. Throughput is at its ceiling on this machine (the API has 10 database connections, and
its CPUs are shared with the load generator), so extra concurrency only adds waiting.

## 2. Sync versus async at the same request rate

100 completions per second for 30 seconds (3,001 completions), each of a different habit, over
50 users. Three runs, because latency on a shared laptop varies from run to run.

| Run | Mode | avg | p50 | p95 | p99 | max |
|---:|---|---:|---:|---:|---:|---:|
| 1 | sync | 13.9 ms | 5.9 ms | 17.4 ms | 313 ms | 558 ms |
| 1 | async | 7.9 ms | 4.8 ms | 15.6 ms | 80 ms | 289 ms |
| 2 | sync | 16.7 ms | 5.7 ms | 26.2 ms | 365 ms | 813 ms |
| 2 | async | 7.5 ms | 4.7 ms | 11.5 ms | 98 ms | 279 ms |
| 3 | sync | 8.6 ms | 4.5 ms | 9.8 ms | 187 ms | 387 ms |
| 3 | async | 4.5 ms | 4.3 ms | 6.9 ms | 10 ms | 35 ms |

Across the three runs, async mode lowered the average by 43 to 55% and p99 by 73 to 95%. The
median moved much less (5 to 19%).

That shape has a cause, and the API's own per-request timings show it. In sync mode the first
completion for each of the 50 users took about 105 ms on the server; every later request took
3 to 8 ms. In async mode those same first requests took about 4 ms.

| Server-side time (one run) | Sync | Async |
|---|---:|---:|
| Each user's first completion (50 requests), average | 105 ms | 4.0 ms |
| The next 150 requests, average | 7.7 ms | 3.0 ms |
| The last 2,000 requests, average | 2.8 ms | 1.5 ms |

The first completion is expensive inline because the first-completion achievement check looks
through the user's habits one query at a time until it finds a completed one. Fifty such
requests out of 3,001 is 1.7%, which is why they move the average and p99 but not the median.
Async mode moves that work to the worker, so the request no longer pays for it.

In async mode the reward arrives after the response. The time from the API recording a
completion to the worker picking it up was p50 0.39 to 0.45 s and p95 0.75 to 2.2 s across the
runs. At 100 events per second the single-threaded worker is at its limit, so a noisy run
builds a short backlog.

## 3. Worker time per reward (optimised in this change)

How long the worker takes to apply one reward, from its own log, for completions that make a
streak of 1, 30, and 365 days. 30 events each, median.

| Streak length | Before | After | Change |
|---:|---:|---:|---:|
| 1 day | 14.2 ms | 7.7 ms | 46% less |
| 30 days | 18.9 ms | 7.8 ms | 59% less |
| 365 days | 77.6 ms | 6.0 ms | 92% less |

Two things changed:

- **The streak is one query instead of one per day.** The old code asked "was it completed on
  this day?" for each day of the streak, so a 365-day streak cost 366 round trips. It is now a
  single window-function query ([`HabitEntryRepository.dailyStreakEndingAt`](../backend/progress-worker/src/main/java/com/progresstracker/progressworker/repository/HabitEntryRepository.java)),
  and the time no longer depends on the streak's length.
- **The achievement check went from about ten queries per event to at most three.** The
  definitions are read once and kept in memory, what the user has unlocked is read in one
  query, and the remaining queries only run for achievements that are still locked.

## 4. Nightly streak reset (optimised in this change)

The job that zeroes streaks nobody kept up, over 20,000 habits whose streaks had all lapsed.

| | Before | After |
|---|---:|---:|
| Time for the job | 5.10 s | 0.20 s |
| Statements | 1 read of every habit, then 20,000 updates | 1 update |

The old version loaded every habit into memory and saved the lapsed ones one at a time. It is
now one `UPDATE`, which is about 26 times faster here and also decides which rows qualify at
the moment it updates them, so it cannot overwrite a completion that lands while it runs.

## 5. Failures during load

100 completions per second for 40 seconds (4,001 completions). The worker was killed with
SIGKILL 8 seconds in, the queue was frozen 16 seconds in, and both came back at 24 seconds.

| | Result |
|---|---|
| Requests that failed | 0 of 4,001 |
| Request latency | p50 5.2 ms, p95 23.6 ms, p99 101 ms |
| Completions left unrewarded | 0 |
| Completions rewarded more than once | 0 |
| Events that never reached the queue | 0 |
| Longest wait for a reward | 60.6 s |

The API did not notice either failure, because a request only writes to the database. Events
written while the queue was frozen waited in the outbox and were published when it came back.
The longest wait is the queue's 60-second visibility timeout: messages the killed worker had
taken were redelivered once it expired, and the worker's record of handled events kept any of
them from being rewarded twice.

## What I would look at next

- **Worker throughput.** One thread handles about 100 events per second here. Processing
  several messages at once, with a cap, would raise that; the per-user lock already makes it safe.
- **The first completion in sync mode.** It is the slow tail in section 2, and the same
  achievement optimisation the worker got would remove most of it.
- **Listing habits.** `GET /api/habits` runs one progress query per habit. It was not under
  load here, and it would be the next thing to measure.
