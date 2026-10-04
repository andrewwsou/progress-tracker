import type { Habit } from "./api";

export function cadenceLabel(habit: Habit): string {
  return habit.frequency === "WEEKLY" ? "Weekly" : "Daily";
}

/** "today" or "this week", for the habit's goal. */
export function goalPeriodLabel(habit: Habit): string {
  return habit.goalPeriod === "WEEKLY" ? "this week" : "today";
}

/** "Sunday, October 4". */
export function longDate(now = new Date()): string {
  return now.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });
}

/** "Sep 28" from a YYYY-MM-DD date, read as a calendar date rather than a UTC instant. */
export function shortDate(iso: string): string {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export function errorText(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback;
}
