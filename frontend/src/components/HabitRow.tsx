import { useState } from "react";
import type { Habit } from "../api";
import { cadenceLabel, goalPeriodLabel } from "../format";
import { CheckIcon } from "./Icons";

type Props = {
  habit: Habit;
  busy: boolean;
  onComplete: () => void;
  onEdit: () => void;
  onDelete: () => void;
};

export function HabitRow({ habit, busy, onComplete, onEdit, onDelete }: Props) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const done = habit.completedForPeriod;
  // aria-disabled rather than disabled, so keyboard focus stays on the button after checking off.
  const checkInactive = done || busy;

  const count = habit.progressCount;
  const target = Math.max(1, habit.progressTargetCount);
  const percent = Math.min(100, Math.round((count / target) * 100));
  const goalMet = count >= target;

  return (
    <li className={`habit${done ? " habit--done" : ""}`}>
      <button
        className="habit__check"
        type="button"
        onClick={() => !checkInactive && onComplete()}
        aria-disabled={checkInactive}
        aria-label={done ? `${habit.name}: done` : `Mark ${habit.name} as done`}
        title={done ? "Done" : "Mark as done"}
      >
        {done && <CheckIcon />}
      </button>

      <div className="habit__body">
        <div className="habit__heading">
          <h2 className="habit__name">{habit.name}</h2>
          <span className="habit__cadence">{cadenceLabel(habit)}</span>
        </div>
        {habit.description && <p className="habit__description">{habit.description}</p>}

        <div className="habit__progress">
          <div
            className={`meter${goalMet ? " meter--met" : ""}`}
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={target}
            aria-valuenow={Math.min(count, target)}
            aria-valuetext={`${count} of ${target} ${goalPeriodLabel(habit)}`}
            aria-label={`${habit.name} goal progress`}
          >
            <span className="meter__fill" style={{ width: `${percent}%` }} />
          </div>
          <span className="habit__count">
            {count} of {target} {goalPeriodLabel(habit)}
          </span>
        </div>
      </div>

      <dl className="habit__stats">
        <div>
          <dt>Streak</dt>
          <dd>{habit.currentStreak}</dd>
        </div>
        <div>
          <dt>Best</dt>
          <dd>{habit.longestStreak}</dd>
        </div>
        <div>
          <dt>XP</dt>
          <dd>{habit.xpTotal.toLocaleString()}</dd>
        </div>
      </dl>

      <div className="habit__actions">
        {confirmingDelete ? (
          <>
            <span className="habit__confirm">Delete this habit and its history?</span>
            <button className="button button--small button--danger" type="button" onClick={onDelete} disabled={busy}>
              {busy ? "Deleting…" : "Delete"}
            </button>
            <button className="button button--small" type="button" onClick={() => setConfirmingDelete(false)}>
              Keep
            </button>
          </>
        ) : (
          <>
            <button className="button button--small button--quiet" type="button" onClick={onEdit}>
              Edit
            </button>
            <button className="button button--small button--quiet" type="button" onClick={() => setConfirmingDelete(true)}>
              Delete
            </button>
          </>
        )}
      </div>
    </li>
  );
}
