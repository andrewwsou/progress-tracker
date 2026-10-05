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
  followEvents,
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
// Events that mean "your data changed, read it again".
const REFRESH_EVENTS = new Set(["ready", "reward", "summary", "resync"]);

// Where keyboard focus goes back to after the habit form closes or a habit is deleted.
const NEW_HABIT_ID = "new-habit";
// Where it goes after Try again works and the button is gone. Always on screen, unlike New habit.
const HABITS_TITLE_ID = "habits-title";
const rowIds = (habit: Habit) => ({ check: `habit-${habit.id}-check`, edit: `habit-${habit.id}-edit` });

type Props = {
  /** The user chose to sign out. */
  onSignOut: () => void;
  /** The API refused the saved token (expired, revoked, or the account is gone). */
  onSessionExpired: () => void;
};

export function Dashboard({ onSignOut, onSessionExpired }: Props) {
  // Null until a load succeeds, so a failed load never looks like an empty account.
  const [data, setData] = useState<DashboardData | null>(null);
  // Why the newest load failed. The next load that succeeds clears it.
  const [loadError, setLoadError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  // Why the last action (add, edit, complete, delete) failed.
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>({ open: false });
  // Habits with a completion or delete request in flight.
  const [busyIds, setBusyIds] = useState<ReadonlySet<number>>(new Set());
  // The element to focus once the next render is on screen. A new object each time, so asking for
  // the same element twice still moves focus.
  const [focusRequest, setFocusRequest] = useState<{ id: string } | null>(null);
  const habits = data?.habits ?? [];

  const refreshTimer = useRef<number | undefined>(undefined);
  // Whether the live stream is connected; when it is, rewards arrive as events instead of by timer.
  const live = useRef(false);
  // Once data is on screen, only the newest refresh may update it: an older response arriving late
  // is dropped. An older failure is always dropped.
  const latestRefresh = useRef(0);
  // Whether data is on screen. A quiet refresh that fails over it stays quiet.
  const shown = useRef(false);

  const showData = useCallback((next: DashboardData, refresh: number) => {
    // Until something is shown, any success beats Loading or the error page, even an older one.
    if (refresh !== latestRefresh.current && shown.current) return;
    shown.current = true;
    setData(next);
    setLoadError(null);
  }, []);

  const showLoadError = useCallback(
    (err: unknown, refresh: number, quiet = false) => {
      // The saved token is no longer accepted (expired, revoked, or the account is gone).
      if (err instanceof ApiError && err.status === 401) {
        onSessionExpired();
        return;
      }
      if (refresh !== latestRefresh.current) return;
      if (quiet && shown.current) return;
      setLoadError(errorText(err, "Could not load your habits."));
    },
    [onSessionExpired],
  );

  const load = useCallback(() => {
    const refresh = ++latestRefresh.current;
    return fetchDashboard().then(
      (next) => showData(next, refresh),
      (err) => showLoadError(err, refresh),
    );
  }, [showData, showLoadError]);

  /**
   * A refresh nobody asked for. A failure is not worth a banner over data already on screen, but
   * it does show if nothing has loaded yet, and a rejected token still signs out.
   */
  const refreshQuietly = useCallback(() => {
    const refresh = ++latestRefresh.current;
    return fetchDashboard().then(
      (next) => showData(next, refresh),
      (err) => showLoadError(err, refresh, true),
    );
  }, [showData, showLoadError]);

  // Live updates: refresh when the worker applies a reward or writes a summary, and on every
  // (re)connect in case an event was missed while disconnected. A hidden tab lets its stream go
  // (browsers allow only a few connections per server) and reconnects, catching up, when it is
  // shown again. That is also when a stream the server closed for having too many open comes back.
  useEffect(() => {
    let controller: AbortController | null = null;

    async function connect(signal: AbortSignal) {
      const end = await followEvents(
        (event) => {
          if (event.type === "ready") live.current = true;
          if (REFRESH_EVENTS.has(event.type)) void refreshQuietly();
        },
        () => {
          live.current = false;
        },
        signal,
      );
      // After "evicted" the controller stays set, so start() does nothing until the tab is hidden.
      if (end === "unauthorized") onSessionExpired();
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
  }, [refreshQuietly, onSessionExpired]);

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
      (next) => active && showData(next, refresh),
      (err) => active && showLoadError(err, refresh),
    );
    return () => {
      active = false;
      window.clearTimeout(refreshTimer.current);
    };
  }, [showData, showLoadError]);

  // Focus moves once the render that asked for it is on screen, and only if it was lost (the
  // focused button went away): it never jumps from somewhere the user has moved on to. If the
  // target has gone too (the habit was deleted elsewhere), the New habit button is next best.
  useEffect(() => {
    if (!focusRequest || document.activeElement !== document.body) return;
    (document.getElementById(focusRequest.id) ?? document.getElementById(NEW_HABIT_ID))?.focus();
  }, [focusRequest]);

  /** Runs an action, then reloads; shows the action's error instead of throwing. True if it succeeded. */
  async function run(action: () => Promise<unknown>, failure: string): Promise<boolean> {
    setError(null);
    try {
      await action();
      await load();
      return true;
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onSessionExpired();
        return false;
      }
      setError(errorText(err, failure));
      return false;
    }
  }

  /** Runs an action for one habit, ignoring repeat clicks while it is in flight. */
  async function runForHabit(habit: Habit, action: () => Promise<unknown>, failure: string): Promise<boolean> {
    if (busyIds.has(habit.id)) return false;
    setBusyIds((ids) => new Set(ids).add(habit.id));
    const succeeded = await run(action, failure);
    setBusyIds((ids) => {
      const next = new Set(ids);
      next.delete(habit.id);
      return next;
    });
    return succeeded;
  }

  async function retry() {
    if (retrying) return;
    setRetrying(true);
    await load();
    setRetrying(false);
    // If it worked, the button has gone and focus with it. If not, focus is still on the button,
    // and the focus effect leaves it there.
    setFocusRequest({ id: HABITS_TITLE_ID });
  }

  /** Closes the form and puts focus back on the button that opened it. */
  function closeForm(habit: Habit | null) {
    setForm({ open: false });
    setFocusRequest({ id: habit ? rowIds(habit).edit : NEW_HABIT_ID });
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

  async function handleDelete(habit: Habit) {
    // Once the row has gone, focus moves to the next one (or the previous one, if it was the last).
    const index = habits.findIndex((h) => h.id === habit.id);
    const neighbour = habits[index + 1] ?? habits[index - 1];
    if (await runForHabit(habit, () => deleteHabit(habit.id), `Could not delete “${habit.name}”.`)) {
      setFocusRequest({ id: neighbour ? rowIds(neighbour).check : NEW_HABIT_ID });
    }
  }

  async function handleSave(input: HabitInput) {
    const editing = form.open ? form.habit : null;
    await run(async () => {
      if (editing) {
        await updateHabit(editing.id, input);
      } else {
        await createHabit(input);
      }
      closeForm(editing);
    }, editing ? "Could not save the habit." : "Could not add the habit.");
  }

  const doneCount = habits.filter((h) => h.completedForPeriod).length;

  return (
    <div className="app">
      <AppHeader onSignOut={onSignOut} />

      <main className="dashboard">
        {data && loadError && (
          <div className="notice notice--error dashboard__notice" role="alert">
            <span>{loadError}</span>
            <button className="link" type="button" onClick={retry} aria-disabled={retrying}>
              {retrying ? "Trying again…" : "Try again"}
            </button>
          </div>
        )}

        {error && (
          <div className="notice notice--error dashboard__notice" role="alert">
            <span>{error}</span>
            <button className="link" type="button" onClick={() => setError(null)}>
              Dismiss
            </button>
          </div>
        )}

        <div className="dashboard__grid">
          <section className="habits" aria-labelledby={HABITS_TITLE_ID}>
            <div className="habits__head">
              <div>
                <h1 className="habits__title" id={HABITS_TITLE_ID} tabIndex={-1}>
                  Habits
                </h1>
                <p className="muted">{longDate()}</p>
              </div>
              {!form.open && (
                <button
                  id={NEW_HABIT_ID}
                  className="button button--primary"
                  type="button"
                  onClick={() => setForm({ open: true, habit: null })}
                >
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
                onCancel={() => closeForm(form.habit)}
              />
            )}

            {!data && loadError ? (
              <div className="empty" role="alert">
                <p className="empty__title">Could not load your habits</p>
                <p className="muted">{loadError}</p>
                <button className="button button--primary" type="button" onClick={retry} aria-disabled={retrying}>
                  {retrying ? "Trying again…" : "Try again"}
                </button>
              </div>
            ) : !data ? (
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
                    ids={rowIds(h)}
                    busy={busyIds.has(h.id)}
                    onComplete={() => handleComplete(h)}
                    onEdit={() => setForm({ open: true, habit: h })}
                    onDelete={() => void handleDelete(h)}
                  />
                ))}
              </ul>
            )}
          </section>

          {/* Nothing until a load succeeds: 0/0 and 0 XP would say the account is empty. */}
          {data && (
            <aside className="sidebar" aria-label="Progress">
              <StatsPanel habits={habits} doneCount={doneCount} />
              {data.summary && <SummaryPanel summary={data.summary} />}
              <AchievementsPanel achievements={data.achievements} />
            </aside>
          )}
        </div>
      </main>
    </div>
  );
}
