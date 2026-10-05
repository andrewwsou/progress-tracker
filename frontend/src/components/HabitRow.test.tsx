import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Habit } from "../api";
import { HabitRow } from "./HabitRow";

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

describe("HabitRow", () => {
  it("keeps the confirming Delete button focusable while the delete runs", () => {
    const onDelete = vi.fn();
    const props = { habit: read, ids: { check: "check", edit: "edit" }, onComplete: vi.fn(), onEdit: vi.fn(), onDelete };
    const { rerender } = render(<HabitRow {...props} busy={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    rerender(<HabitRow {...props} busy={true} />);

    const deleting = screen.getByRole("button", { name: "Deleting…" });
    expect((deleting as HTMLButtonElement).disabled).toBe(false);
    expect(deleting.getAttribute("aria-disabled")).toBe("true");
    fireEvent.click(deleting);
    expect(onDelete).not.toHaveBeenCalled();
  });
});
