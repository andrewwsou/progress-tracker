import { useCallback, useEffect, useRef, useState } from "react";
import type { Achievement, Habit, HabitInput, WeeklySummary } from "../api";
import {
  ApiError,
  completeHabit,
  createHabit,
  deleteHabit,
  fetchAchievements,
  fetchHabits,
  fetchLatestSummary,
  streamEvents,
  syncTimeZone,
  updateHabit,
} from "../api";
import { errorText, longDate } from "../format";
import { AppHeader } from "./AppHeader";
import { HabitForm } from "./HabitForm";
import { HabitRow } from "./HabitRow";
import { PlusIcon } from "./Icons";
import { AchievementsPanel, StatsPanel, SummaryPanel } from "./SidePanels";

/** Closed, adding a new habit, or editing the given one. */
type FormState = { open: false } | { open: true; habit: Habit | null };

type DashboardData = { habits: Habit[]; achievements: Achievement[]; summary: WeeklySummary | null };

/** Everything the dashboard shows. Achievements and the summary are optional extras. */
async function fetchDashboard(): Promise<DashboardData> {
  const [habits, achievements, summary] = await Promise.all([
    fetchHabits(),
    fetchAchievements().catch(() => []),
    fetchLatestSummary().catch(() => null),
  ]);
  return { habits, achievements, summary };
}

// In async mode the worker applies a reward a moment after the request returns. The live stream
// says when; without it (stream down), look again after this long instead.
const REWARD_REFRESH_DELAY_MS = 1500;
const MAX_RECONNECT_DELAY_MS = 30_000;
// Events that mean "your data changed, read it again".
const REFRESH_EVENTS = new Set(["ready", "reward", "summary", "resync"]);

export function Dashboard({ onSignOut }: { onSignOut: () => void }) {
  const [habits, setHabits] = useState<Habit[]>([]);
  const [achievements, setAchievements] = useState<Achievement[]>([]);
  const [summary, setSummary] = useState<WeeklySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>({ open: false });
  // Habits with a completion or delete request in flight.
  const [busyIds, setBusyIds] = useState<ReadonlySet<number>>(new Set());

  const refreshTimer = useRef<number | undefined>(undefined);
  // Whether the live stream is connected; when it is, rewards arrive as events instead of by timer.
  const live = useRef(false);
  // Only the newest refresh may update the screen; an older response arriving late is dropped.
  const latestRefresh = useRef(0);

  const showData = useCallback((data: DashboardData, refresh: number) => {
    if (refresh !== latestRefresh.current) return;
    setHabits(data.habits);
    setAchievements(data.achievements);
    setSummary(data.summary);
    setLoading(false);
  }, []);

  const showLoadError = useCallback(
    (err: unknown) => {
      // The saved token is no longer accepted (expired, or the account is gone).
      if (err instanceof ApiError && err.status === 401) {
        onSignOut();
        return;
      }
      setError(errorText(err, "Could not load your habits."));
      setLoading(false);
    },
    [onSignOut],
  );

  const load = useCallback(() => {
    const refresh = ++latestRefresh.current;
    return fetchDashboard().then((data) => showData(data, refresh), showLoadError);
  }, [showData, showLoadError]);

  /** A refresh nobody asked for: a failure is not worth a banner, but a rejected token still signs out. */
  const refreshQuietly = useCallback(() => {
    const refresh = ++latestRefresh.current;
    return fetchDashboard().then(
      (data) => showData(data, refresh),
      (err) => {
        if (err instanceof ApiError && err.status === 401) onSignOut();
      },
    );
  }, [showData, onSignOut]);

  // Live updates: refresh when the worker applies a reward or writes a summary. Reconnects with
  // backoff, and refreshes on every (re)connect in case an event was missed while disconnected.
  // A hidden tab lets its stream go (browsers allow only a few connections per server) and
  // reconnects, catching up, when it is shown again.
  useEffect(() => {
    let controller: AbortController | null = null;

    async function connect(signal: AbortSignal) {
      let delay = 1000;
      while (!signal.aborted) {
        try {
          await streamEvents((event) => {
            if (event.type === "ready") {
              live.current = true;
              delay = 1000;
            }
            if (REFRESH_EVENTS.has(event.type)) void refreshQuietly();
          }, signal);
        } catch (err) {
          if (signal.aborted) return;
          if (err instanceof ApiError && err.status === 401) {
            onSignOut();
            return;
          }
        }
        live.current = false;
        await new Promise((resolve) => window.setTimeout(resolve, delay));
        delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
      }
    }

    function start() {
      if (controller) return;
      controller = new AbortController();
      void connect(controller.signal);
    }

    function pause() {
      live.current = false;
      controller?.abort();
      controller = null;
    }

    function onVisibilityChange() {
      if (document.visibilityState === "hidden") pause();
      else start();
    }

    if (document.visibilityState !== "hidden") start();
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      pause();
    };
  }, [refreshQuietly, onSignOut]);

  // The account counts the user's days in its time zone; keep it matching this browser's. If it
  // changed, "today" may have moved, so read everything again. A failure here is not worth a banner.
  useEffect(() => {
    let active = true;
    syncTimeZone().then(
      (changed) => active && changed && void load(),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [load]);

  // First load. A response that arrives after the dashboard has gone (signed out) is dropped.
  useEffect(() => {
    let active = true;
    const refresh = ++latestRefresh.current;
    fetchDashboard().then(
      (data) => active && showData(data, refresh),
      (err) => active && showLoadError(err),
    );
    return () => {
      active = false;
      window.clearTimeout(refreshTimer.current);
    };
  }, [showData, showLoadError]);

  /** Runs an action, then reloads; shows the action's error instead of throwing. */
  async function run(action: () => Promise<unknown>, failure: string) {
    setError(null);
    try {
      await action();
      await load();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onSignOut();
        return;
      }
      setError(errorText(err, failure));
    }
  }

  /** Runs an action for one habit, ignoring repeat clicks while it is in flight. */
  async function runForHabit(habit: Habit, action: () => Promise<unknown>, failure: string) {
    if (busyIds.has(habit.id)) return;
    setBusyIds((ids) => new Set(ids).add(habit.id));
    await run(action, failure);
    setBusyIds((ids) => {
      const next = new Set(ids);
      next.delete(habit.id);
      return next;
    });
  }

  function handleComplete(habit: Habit) {
    void runForHabit(
      habit,
      async () => {
        await completeHabit(habit.id);
        if (!live.current) {
          window.clearTimeout(refreshTimer.current);
          refreshTimer.current = window.setTimeout(() => void refreshQuietly(), REWARD_REFRESH_DELAY_MS);
        }
      },
      `Could not complete “${habit.name}”.`,
    );
  }

  function handleDelete(habit: Habit) {
    void runForHabit(habit, () => deleteHabit(habit.id), `Could not delete “${habit.name}”.`);
  }

  async function handleSave(input: HabitInput) {
    const editing = form.open ? form.habit : null;
    await run(async () => {
      if (editing) {
        await updateHabit(editing.id, input);
      } else {
        await createHabit(input);
      }
      setForm({ open: false });
    }, editing ? "Could not save the habit." : "Could not add the habit.");
  }

  const doneCount = habits.filter((h) => h.completedForPeriod).length;

  return (
    <div className="app">
      <AppHeader onSignOut={onSignOut} />

      <main className="dashboard">
        {error && (
          <div className="notice notice--error dashboard__notice" role="alert">
            <span>{error}</span>
            <button className="link" type="button" onClick={() => setError(null)}>
              Dismiss
            </button>
          </div>
        )}

        <div className="dashboard__grid">
          <section className="habits" aria-labelledby="habits-title">
            <div className="habits__head">
              <div>
                <h1 className="habits__title" id="habits-title">
                  Habits
                </h1>
                <p className="muted">{longDate()}</p>
              </div>
              {!form.open && (
                <button className="button button--primary" type="button" onClick={() => setForm({ open: true, habit: null })}>
                  <PlusIcon />
                  New habit
                </button>
              )}
            </div>

            {form.open && (
              <HabitForm
                key={form.habit?.id ?? "new"}
                habit={form.habit}
                onSave={handleSave}
                onCancel={() => setForm({ open: false })}
              />
            )}

            {loading ? (
              <p className="muted habits__status">Loading your habits…</p>
            ) : habits.length === 0 ? (
              !form.open && (
                <div className="empty">
                  <p className="empty__title">No habits yet</p>
                  <p className="muted">Add one you want to do every day or every week, then check it off here.</p>
                  <button className="button button--primary" type="button" onClick={() => setForm({ open: true, habit: null })}>
                    Add your first habit
                  </button>
                </div>
              )
            ) : (
              <ul className="habit-list">
                {habits.map((h) => (
                  <HabitRow
                    key={h.id}
                    habit={h}
                    busy={busyIds.has(h.id)}
                    onComplete={() => handleComplete(h)}
                    onEdit={() => setForm({ open: true, habit: h })}
                    onDelete={() => handleDelete(h)}
                  />
                ))}
              </ul>
            )}
          </section>

          <aside className="sidebar" aria-label="Progress">
            <StatsPanel habits={habits} doneCount={doneCount} />
            {summary && <SummaryPanel summary={summary} />}
            <AchievementsPanel achievements={achievements} />
          </aside>
        </div>
      </main>
    </div>
  );
}
