import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Habit, LiveEvent, StreamEnd } from "../api";
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
} from "../api";
import { Dashboard } from "./Dashboard";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  completeHabit: vi.fn(),
  createHabit: vi.fn(),
  deleteHabit: vi.fn(),
  fetchAchievements: vi.fn(),
  fetchHabits: vi.fn(),
  fetchLatestSummary: vi.fn(),
  followEvents: vi.fn(),
  syncTimeZone: vi.fn(),
  updateHabit: vi.fn(),
}));

function habit(id: number, name: string): Habit {
  return {
    id,
    name,
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
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// The live stream: tests send events through it, decide how each connection ends, and check
// whether the dashboard let it go.
let streams: { send: (event: LiveEvent) => void; end: (how: StreamEnd) => void; signal: AbortSignal }[];
let visibility: DocumentVisibilityState;

beforeEach(() => {
  streams = [];
  visibility = "visible";
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });
  vi.mocked(followEvents).mockImplementation((onEvent, _onDrop, signal) => {
    const end = deferred<StreamEnd>();
    streams.push({ send: onEvent, end: end.resolve, signal });
    return end.promise;
  });
  vi.mocked(fetchAchievements).mockResolvedValue([]);
  vi.mocked(fetchLatestSummary).mockResolvedValue(null);
  vi.mocked(syncTimeZone).mockResolvedValue(false);
});

afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});

function renderDashboard() {
  const props = { onSignOut: vi.fn(), onSessionExpired: vi.fn() };
  const { unmount } = render(<Dashboard {...props} />);
  return { ...props, unmount };
}

async function send(type: string) {
  await act(async () => streams[streams.length - 1].send({ type }));
}

function rowOf(name: string): HTMLElement {
  return screen.getByRole("heading", { name }).closest("li")!;
}

describe("loading", () => {
  it("offers Try again after a failed first load, instead of an empty account", async () => {
    vi.mocked(fetchHabits)
      .mockRejectedValueOnce(new ApiError(500, "Failed to fetch habits"))
      .mockResolvedValueOnce([habit(1, "Read")]);
    renderDashboard();

    const retry = await screen.findByRole("button", { name: "Try again" });
    expect(screen.getByText("Could not load your habits")).toBeTruthy();
    expect(screen.queryByText("No habits yet")).toBeNull();
    expect(screen.queryByText("Overview")).toBeNull();

    fireEvent.click(retry);
    expect(await screen.findByRole("heading", { name: "Read" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText("Overview")).toBeTruthy();
  });

  it("shows an older load that worked while nothing is on screen, even if the newer one then fails", async () => {
    const first = deferred<Habit[]>();
    const second = deferred<Habit[]>();
    vi.mocked(fetchHabits).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    renderDashboard();
    await send("ready"); // starts a second load while the first is in flight

    await act(async () => first.resolve([habit(1, "Read")]));
    expect(screen.getByRole("heading", { name: "Read" })).toBeTruthy();

    await act(async () => second.reject(new ApiError(500, "Server error")));
    expect(screen.getByRole("heading", { name: "Read" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows a failed quiet refresh when nothing has loaded yet, instead of Loading forever", async () => {
    const first = deferred<Habit[]>();
    const second = deferred<Habit[]>();
    vi.mocked(fetchHabits).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    renderDashboard();
    await send("ready");

    await act(async () => first.reject(new ApiError(500, "Old error"))); // older, so dropped
    await act(async () => second.reject(new ApiError(500, "Server error")));

    expect(screen.getByText("Could not load your habits")).toBeTruthy();
    expect(screen.getByText("Server error")).toBeTruthy();
    expect(screen.queryByText("Old error")).toBeNull();
    expect(screen.queryByText("Loading your habits…")).toBeNull();
  });

  it("once data is shown, lets only the newest refresh change it", async () => {
    const second = deferred<Habit[]>();
    const third = deferred<Habit[]>();
    vi.mocked(fetchHabits)
      .mockResolvedValueOnce([habit(1, "Read")])
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise);
    renderDashboard();
    await screen.findByRole("heading", { name: "Read" });
    await send("reward");
    await send("summary");

    await act(async () => second.resolve([habit(2, "Run")]));
    expect(screen.getByRole("heading", { name: "Read" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Run" })).toBeNull();

    await act(async () => third.resolve([habit(3, "Write")]));
    expect(screen.getByRole("heading", { name: "Write" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Read" })).toBeNull();
  });

  it("drops an older failure that arrives after newer data", async () => {
    const first = deferred<Habit[]>();
    const second = deferred<Habit[]>();
    vi.mocked(fetchHabits).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    renderDashboard();
    await send("ready");

    await act(async () => second.resolve([habit(1, "Read")]));
    await act(async () => first.reject(new ApiError(500, "Server error")));

    expect(screen.getByRole("heading", { name: "Read" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("drops an older response that arrives after newer data", async () => {
    const first = deferred<Habit[]>();
    const second = deferred<Habit[]>();
    vi.mocked(fetchHabits).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    renderDashboard();
    await send("ready");

    await act(async () => second.resolve([habit(2, "Run")]));
    await act(async () => first.resolve([habit(1, "Read")]));

    expect(screen.getByRole("heading", { name: "Run" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Read" })).toBeNull();
  });

  it("keeps quiet when a background refresh fails over data already shown", async () => {
    vi.mocked(fetchHabits)
      .mockResolvedValueOnce([habit(1, "Read")])
      .mockRejectedValueOnce(new ApiError(500, "Server error"));
    renderDashboard();
    await screen.findByRole("heading", { name: "Read" });

    await send("reward");

    expect(vi.mocked(fetchHabits)).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("heading", { name: "Read" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("clears a load error on the next successful refresh, but not an action's error", async () => {
    vi.mocked(fetchHabits)
      .mockResolvedValueOnce([habit(1, "Read"), habit(2, "Run")])
      .mockRejectedValueOnce(new ApiError(500, "Server error"))
      .mockResolvedValueOnce([habit(2, "Run")]);
    vi.mocked(deleteHabit).mockResolvedValue(undefined);
    vi.mocked(completeHabit).mockRejectedValue(new ApiError(409, "Already done today"));
    renderDashboard();
    await screen.findByRole("heading", { name: "Read" });

    // The delete works, but the reload after it fails.
    fireEvent.click(within(rowOf("Read")).getByRole("button", { name: "Delete" }));
    fireEvent.click(within(rowOf("Read")).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("Server error")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Mark Run as done" }));
    expect(await screen.findByText("Already done today")).toBeTruthy();

    await send("reward");
    expect(screen.queryByText("Server error")).toBeNull();
    expect(screen.getByText("Already done today")).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Read" })).toBeNull();
  });
});

describe("session", () => {
  it("reports a refused token as an expired session, not a sign-out", async () => {
    vi.mocked(fetchHabits).mockRejectedValue(new ApiError(401, "Unauthorized"));
    const { onSignOut, onSessionExpired } = renderDashboard();

    await vi.waitFor(() => expect(onSessionExpired).toHaveBeenCalled());
    expect(onSignOut).not.toHaveBeenCalled();
  });

  it("reports a stream refused for its token as an expired session", async () => {
    vi.mocked(fetchHabits).mockResolvedValue([]);
    const { onSessionExpired } = renderDashboard();

    await act(async () => streams[0].end("unauthorized"));
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });
});

describe("live updates", () => {
  function setVisibility(next: DocumentVisibilityState) {
    visibility = next;
    act(() => {
      document.dispatchEvent(new Event("visibilitychange"));
    });
  }

  it("stays disconnected after an eviction until the tab is shown again", async () => {
    vi.mocked(fetchHabits).mockResolvedValue([]);
    renderDashboard();
    await act(async () => streams[0].end("evicted"));

    setVisibility("visible");
    expect(followEvents).toHaveBeenCalledTimes(1);

    setVisibility("hidden");
    setVisibility("visible");
    expect(followEvents).toHaveBeenCalledTimes(2);
  });

  it("lets its stream go while the tab is hidden, and opens a new one when it is shown", async () => {
    vi.mocked(fetchHabits).mockResolvedValue([]);
    renderDashboard();
    await screen.findByText("No habits yet");
    expect(streams[0].signal.aborted).toBe(false);

    setVisibility("hidden");
    expect(streams[0].signal.aborted).toBe(true);

    setVisibility("visible");
    expect(streams).toHaveLength(2);
    expect(streams[1].signal.aborted).toBe(false);
  });

  it("lets its stream go when the user signs out", async () => {
    vi.mocked(fetchHabits).mockResolvedValue([]);
    // As in App: signing out swaps the dashboard for the sign-in screen.
    function SignedIn() {
      const [signedIn, setSignedIn] = useState(true);
      return signedIn ? <Dashboard onSignOut={() => setSignedIn(false)} onSessionExpired={vi.fn()} /> : null;
    }
    render(<SignedIn />);
    await screen.findByText("No habits yet");

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(screen.queryByText("No habits yet")).toBeNull();
    expect(streams[0].signal.aborted).toBe(true);
  });

  it("lets its stream go when it is unmounted", async () => {
    vi.mocked(fetchHabits).mockResolvedValue([]);
    const { unmount } = renderDashboard();
    await screen.findByText("No habits yet");

    unmount();
    expect(streams[0].signal.aborted).toBe(true);
  });
});

describe("focus", () => {
  beforeEach(() => {
    vi.mocked(fetchHabits).mockResolvedValue([habit(1, "Read"), habit(2, "Run"), habit(3, "Write")]);
  });

  it("goes back to New habit when the new-habit form is cancelled", async () => {
    renderDashboard();
    await screen.findByRole("heading", { name: "Read" });

    fireEvent.click(screen.getByRole("button", { name: "New habit" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(document.activeElement).toBe(screen.getByRole("button", { name: "New habit" }));
  });

  it("goes back to New habit after adding a habit", async () => {
    vi.mocked(createHabit).mockResolvedValue(habit(4, "Stretch"));
    renderDashboard();
    await screen.findByRole("heading", { name: "Read" });

    fireEvent.click(screen.getByRole("button", { name: "New habit" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), { target: { value: "Stretch" } });
    fireEvent.click(screen.getByRole("button", { name: "Add habit" }));

    const newHabit = await screen.findByRole("button", { name: "New habit" });
    await vi.waitFor(() => expect(document.activeElement).toBe(newHabit));
  });

  it("goes back to the habit's Edit button when editing is cancelled", async () => {
    renderDashboard();
    await screen.findByRole("heading", { name: "Run" });

    fireEvent.click(within(rowOf("Run")).getByRole("button", { name: "Edit" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(document.activeElement).toBe(within(rowOf("Run")).getByRole("button", { name: "Edit" }));
  });

  it("goes back to Delete when the delete is kept", async () => {
    renderDashboard();
    await screen.findByRole("heading", { name: "Run" });

    fireEvent.click(within(rowOf("Run")).getByRole("button", { name: "Delete" }));
    const keep = within(rowOf("Run")).getByRole("button", { name: "Keep" });
    keep.focus();
    fireEvent.click(keep);

    expect(document.activeElement).toBe(within(rowOf("Run")).getByRole("button", { name: "Delete" }));
  });

  it("moves to the next habit after a delete", async () => {
    vi.mocked(deleteHabit).mockResolvedValue(undefined);
    renderDashboard();
    await screen.findByRole("heading", { name: "Run" });
    vi.mocked(fetchHabits).mockResolvedValue([habit(1, "Read"), habit(3, "Write")]);

    fireEvent.click(within(rowOf("Run")).getByRole("button", { name: "Delete" }));
    const confirm = within(rowOf("Run")).getByRole("button", { name: "Delete" });
    confirm.focus();
    fireEvent.click(confirm);

    await vi.waitFor(() => expect(screen.queryByRole("heading", { name: "Run" })).toBeNull());
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Mark Write as done" }));
  });

  it("moves to the Habits heading when Try again works", async () => {
    vi.mocked(fetchHabits).mockRejectedValueOnce(new ApiError(500, "Server error"));
    renderDashboard();
    const retry = await screen.findByRole("button", { name: "Try again" });
    retry.focus();
    fireEvent.click(retry);

    await screen.findByRole("heading", { name: "Read" });
    await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Habits" })));
  });

  it("moves to the Habits heading when Try again in the banner works", async () => {
    // The first load works; the reload for a time zone change fails, over the habits already shown.
    const zoneChanged = deferred<boolean>();
    vi.mocked(syncTimeZone).mockReturnValue(zoneChanged.promise);
    vi.mocked(fetchHabits).mockResolvedValueOnce([habit(1, "Read")]).mockRejectedValueOnce(new ApiError(500, "Server error"));
    renderDashboard();
    await screen.findByRole("heading", { name: "Read" });
    await act(async () => zoneChanged.resolve(true));
    const retry = await screen.findByRole("button", { name: "Try again" });
    retry.focus();
    fireEvent.click(retry);

    await vi.waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
    await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole("heading", { name: "Habits" })));
  });

  it("stays on Try again when it fails again", async () => {
    vi.mocked(fetchHabits).mockRejectedValue(new ApiError(500, "Server error"));
    renderDashboard();
    const retry = await screen.findByRole("button", { name: "Try again" });
    retry.focus();
    fireEvent.click(retry);

    expect(retry.textContent).toBe("Trying again…");
    await vi.waitFor(() => expect(retry.textContent).toBe("Try again"));
    await act(async () => undefined); // let the focus effect run
    expect(fetchHabits).toHaveBeenCalledTimes(2);
    expect(document.activeElement).toBe(retry);
  });
});
