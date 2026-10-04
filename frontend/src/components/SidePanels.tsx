import type { Achievement, Habit, WeeklySummary } from "../api";
import { shortDate } from "../format";
import { MedalIcon } from "./Icons";

export function StatsPanel({ habits, doneCount }: { habits: Habit[]; doneCount: number }) {
  const totalXp = habits.reduce((sum, h) => sum + h.xpTotal, 0);
  const bestStreak = habits.reduce((best, h) => Math.max(best, h.currentStreak), 0);

  return (
    <section className="panel" aria-labelledby="stats-title">
      <h2 className="panel__title" id="stats-title">
        Overview
      </h2>
      <dl className="stats">
        <div className="stats__item">
          <dt>Done</dt>
          <dd>
            {doneCount}
            <span className="stats__of">/{habits.length}</span>
          </dd>
        </div>
        <div className="stats__item">
          <dt>Top streak</dt>
          <dd>{bestStreak}</dd>
        </div>
        <div className="stats__item">
          <dt>Total XP</dt>
          <dd>{totalXp.toLocaleString()}</dd>
        </div>
      </dl>
    </section>
  );
}

export function SummaryPanel({ summary }: { summary: WeeklySummary }) {
  return (
    <section className="panel" aria-labelledby="summary-title">
      <div className="panel__head">
        <h2 className="panel__title" id="summary-title">
          Week of {shortDate(summary.weekStart)}
        </h2>
        <span className="panel__aside">{summary.source === "AI" ? "Written by Claude" : "Weekly summary"}</span>
      </div>
      <p className="summary__headline">{summary.headline}</p>
      <p className="summary__body">{summary.body}</p>
      <p className="summary__meta">
        {summary.completions.toLocaleString()} {summary.completions === 1 ? "completion" : "completions"} ·{" "}
        {summary.xpEarned.toLocaleString()} XP
        {summary.focusHabit && (
          <>
            {" "}
            · Focus next week: <strong>{summary.focusHabit}</strong>
          </>
        )}
      </p>
    </section>
  );
}

export function AchievementsPanel({ achievements }: { achievements: Achievement[] }) {
  return (
    <section className="panel" aria-labelledby="achievements-title">
      <h2 className="panel__title" id="achievements-title">
        Achievements
      </h2>
      {achievements.length === 0 ? (
        <p className="muted">Complete a habit to unlock your first achievement.</p>
      ) : (
        <ul className="achievements">
          {achievements.map((a) => (
            <li key={a.userAchievementId} className="achievement">
              <span className="achievement__icon">
                <MedalIcon />
              </span>
              <div>
                <p className="achievement__name">{a.name}</p>
                <p className="achievement__description">{a.description}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
