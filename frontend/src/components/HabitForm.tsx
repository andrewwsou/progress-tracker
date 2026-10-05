import { useState } from "react";
import type { Habit, HabitInput } from "../api";

type Props = {
  /** The habit being edited, or null for a new one. */
  habit: Habit | null;
  onSave: (input: HabitInput) => Promise<void>;
  onCancel: () => void;
};

// A habit can be completed once per day (daily) or once per week (weekly), so these are the
// only goals that can be met: every day or N days a week for a daily habit, once a week for a
// weekly one. "EVERY_DAY" is a goal of 1 per day; a number is that many completions per week.
type Goal = "EVERY_DAY" | number;
const DAYS_A_WEEK = [1, 2, 3, 4, 5, 6, 7];

function initialGoal(habit: Habit | null): Goal {
  if (!habit || habit.goalPeriod !== "WEEKLY") return "EVERY_DAY";
  return Math.min(7, Math.max(1, habit.goalTargetCount));
}

export function HabitForm({ habit, onSave, onCancel }: Props) {
  const [name, setName] = useState(habit?.name ?? "");
  const [description, setDescription] = useState(habit?.description ?? "");
  const [frequency, setFrequency] = useState<Habit["frequency"]>(habit?.frequency ?? "DAILY");
  const [goal, setGoal] = useState<Goal>(() => initialGoal(habit));
  const [saving, setSaving] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (saving || !name.trim()) return;
    setSaving(true);
    const target =
      frequency === "WEEKLY"
        ? { goalPeriod: "WEEKLY" as const, goalTargetCount: 1 }
        : goal === "EVERY_DAY"
          ? { goalPeriod: "DAILY" as const, goalTargetCount: 1 }
          : { goalPeriod: "WEEKLY" as const, goalTargetCount: goal };
    try {
      await onSave({ name: name.trim(), description, frequency, ...target });
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="panel habit-form" onSubmit={handleSubmit}>
      <h2 className="panel__title">{habit ? "Edit habit" : "New habit"}</h2>

      <div className="habit-form__grid">
        <label className="field habit-form__name">
          <span className="field__label">Name</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} required autoFocus />
        </label>

        <label className="field habit-form__description">
          <span className="field__label">
            Description <span className="field__optional">optional</span>
          </span>
          <input className="input" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={255} />
        </label>

        <label className="field">
          <span className="field__label">Repeats</span>
          <select
            className="input"
            value={frequency}
            onChange={(e) => setFrequency(e.target.value as Habit["frequency"])}
          >
            <option value="DAILY">Daily</option>
            <option value="WEEKLY">Weekly</option>
          </select>
        </label>

        <label className="field">
          <span className="field__label">Goal</span>
          {frequency === "WEEKLY" ? (
            <select className="input" value="ONCE" disabled aria-describedby="weekly-goal-hint">
              <option value="ONCE">Once a week</option>
            </select>
          ) : (
            <select
              className="input"
              value={String(goal)}
              onChange={(e) => setGoal(e.target.value === "EVERY_DAY" ? "EVERY_DAY" : Number(e.target.value))}
            >
              <option value="EVERY_DAY">Every day</option>
              {DAYS_A_WEEK.map((days) => (
                <option key={days} value={days}>
                  {days} {days === 1 ? "day" : "days"} a week
                </option>
              ))}
            </select>
          )}
          {frequency === "WEEKLY" && (
            <span id="weekly-goal-hint" className="field__hint">
              A weekly habit is checked off once per week.
            </span>
          )}
        </label>
      </div>

      <div className="habit-form__actions">
        <button className="button" type="button" onClick={onCancel}>
          Cancel
        </button>
        {/* aria-disabled while saving, so focus stays on the button if the save fails. */}
        <button className="button button--primary" type="submit" disabled={!name.trim()} aria-disabled={saving}>
          {saving ? "Saving…" : habit ? "Save changes" : "Add habit"}
        </button>
      </div>
    </form>
  );
}
