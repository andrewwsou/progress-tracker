import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Habit } from "../api";
import { HabitForm } from "./HabitForm";

afterEach(cleanup);

const read: Habit = {
  id: 1,
  name: "Read",
  frequency: "DAILY",
  goalPeriod: "DAILY",
  goalTargetCount: 1,
  progressCount: 0,
  progressTargetCount: 1,
  completedForPeriod: false,
  currentStreak: 0,
  longestStreak: 0,
  xpTotal: 0,
};

describe("HabitForm", () => {
  it("keeps the save button focusable while saving, and saves once", () => {
    const onSave = vi.fn(() => new Promise<void>(() => undefined));
    render(<HabitForm habit={read} onSave={onSave} onCancel={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    const saving = screen.getByRole("button", { name: "Saving…" });
    expect((saving as HTMLButtonElement).disabled).toBe(false);
    expect(saving.getAttribute("aria-disabled")).toBe("true");
    fireEvent.click(saving);
    expect(onSave).toHaveBeenCalledTimes(1);
  });
});
