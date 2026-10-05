import type { components } from "./api-types";

export const BACKEND_URL: string = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

// These types are generated from the backend's OpenAPI contract (`npm run generate:api`),
// so a field that is renamed or removed on the server becomes a compile error here.
export type Habit = components["schemas"]["HabitResponse"];
export type HabitInput = components["schemas"]["HabitRequest"];
export type Achievement = components["schemas"]["UserAchievementDto"];
export type WeeklySummary = components["schemas"]["WeeklySummaryResponse"];
export type Profile = components["schemas"]["ProfileResponse"];
type AuthResponse = components["schemas"]["AuthResponse"];
type Problem = components["schemas"]["ProblemDetail"];

function authHeaders(): HeadersInit {
  const token = localStorage.getItem("token");
  if (!token) return {};
  return {
    Authorization: `Bearer ${token}`,
  };
}

/**
 * An error response from the API. The status lets callers react to specific cases, such as 401;
 * it is 0 when the server could not be reached at all.
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

const UNREACHABLE = "Can't reach the server. Check that the API is running, then try again.";

/**
 * fetch, except that a network failure (the API is down, the connection dropped, a CORS refusal)
 * becomes an ApiError a person can read instead of the browser's "Failed to fetch". Aborts pass through.
 */
async function request(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (err) {
    if (init?.signal?.aborted) throw err;
    throw new ApiError(0, UNREACHABLE);
  }
}

async function toError(res: Response, fallback: string): Promise<ApiError> {
  return new ApiError(res.status, await errorMessage(res, fallback));
}

/** Turns an error response (an RFC 9457 problem document) into a message a person can read. */
async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const problem: Problem = await res.json();
    const fieldErrors = Object.entries(problem.errors ?? {}).map(([field, message]) => `${field} ${message}`);
    if (fieldErrors.length > 0) return fieldErrors.join("; ");
    return problem.detail || problem.title || fallback;
  } catch {
    return fallback;
  }
}

/** The browser's IANA time zone, e.g. "America/Los_Angeles"; the API counts the user's days in it. */
export function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  } catch {
    return undefined;
  }
}

export async function registerUser(email: string, password: string): Promise<string> {
  const res = await request(`${BACKEND_URL}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, timeZone: browserTimeZone() }),
  });
  if (!res.ok) throw await toError(res, "Failed to register");

  const data: AuthResponse = await res.json();
  return data.token;
}

export async function loginUser(email: string, password: string): Promise<string> {
  const res = await request(`${BACKEND_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw await toError(res, "Failed to login");

  const data: AuthResponse = await res.json();
  return data.token;
}

/** Revokes the user's tokens on the server, on every device, so a copy of this one stops working. */
export async function logout(token: string): Promise<void> {
  const res = await request(`${BACKEND_URL}/api/auth/logout`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw await toError(res, "Failed to sign out");
}

export async function fetchHabits(): Promise<Habit[]> {
  const res = await request(`${BACKEND_URL}/api/habits`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to fetch habits");
  return res.json();
}

export async function createHabit(payload: HabitInput): Promise<Habit> {
  const res = await request(`${BACKEND_URL}/api/habits`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw await toError(res, "Failed to create habit");
  return res.json();
}

export async function updateHabit(id: number, payload: HabitInput): Promise<Habit> {
  const res = await request(`${BACKEND_URL}/api/habits/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw await toError(res, "Failed to update habit");
  return res.json();
}

export async function fetchAchievements(): Promise<Achievement[]> {
  const res = await request(`${BACKEND_URL}/api/achievements`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to fetch achievements");
  return res.json();
}

/** The newest finished weekly summary, or null if none has been written yet (204). */
export async function fetchLatestSummary(): Promise<WeeklySummary | null> {
  const res = await request(`${BACKEND_URL}/api/summaries/latest`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (res.status === 204) return null;
  if (!res.ok) throw await toError(res, "Failed to fetch the weekly summary");
  return res.json();
}

export async function deleteHabit(id: number): Promise<void> {
  const res = await request(`${BACKEND_URL}/api/habits/${id}`, {
    method: "DELETE",
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to delete habit");
}

export async function completeHabit(habitId: number): Promise<Habit> {
  const res = await request(`${BACKEND_URL}/api/habits/${habitId}/complete`, {
    method: "POST",
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to complete habit");
  return res.json();
}

export async function fetchProfile(): Promise<Profile> {
  const res = await request(`${BACKEND_URL}/api/me`, { headers: { ...authHeaders() } });
  if (!res.ok) throw await toError(res, "Failed to load your profile");
  return res.json();
}

export async function updateProfile(timeZone: string): Promise<Profile> {
  const res = await request(`${BACKEND_URL}/api/me`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify({ timeZone }),
  });
  if (!res.ok) throw await toError(res, "Failed to update your profile");
  return res.json();
}

/**
 * Makes the account's time zone match this browser's (a new device, or travel). Returns true if
 * it changed, in which case "today" may have moved and the dashboard should be reloaded.
 */
export async function syncTimeZone(): Promise<boolean> {
  const zone = browserTimeZone();
  if (!zone) return false;
  const profile = await fetchProfile();
  if (profile.timeZone === zone) return false;
  await updateProfile(zone);
  return true;
}

/**
 * A live update from the API: something changed, so read the new state. "evicted" is the
 * exception: the server closed this stream because the user opened too many.
 */
export type LiveEvent = { type: "ready" | "reward" | "summary" | "resync" | "evicted" | string };

// The server sends a keep-alive every 25 s; this long without any bytes means the connection is dead.
const STREAM_SILENCE_LIMIT_MS = 60_000;

/**
 * Opens the caller's live-update stream (server-sent events) and calls onEvent for each event
 * until the stream ends or the signal aborts. Uses fetch rather than EventSource because
 * EventSource cannot send the Authorization header.
 */
export async function streamEvents(onEvent: (event: LiveEvent) => void, signal: AbortSignal): Promise<void> {
  const res = await request(`${BACKEND_URL}/api/events`, {
    headers: { Accept: "text/event-stream", ...authHeaders() },
    signal,
  });
  if (!res.ok || !res.body) throw await toError(res, "Live updates are unavailable");

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  let type = "message";
  let hasData = false;
  for (;;) {
    const { value, done } = await readWithin(reader, STREAM_SILENCE_LIMIT_MS);
    if (done) return;
    buffer += value;
    // Events are separated by a blank line; each line is "field:value". Lines starting with ":"
    // are comments (keep-alives). As the SSE spec says, a block without data is not an event.
    let newline: number;
    while ((newline = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, newline).replace(/\r$/, "");
      buffer = buffer.slice(newline + 1);
      if (line === "") {
        if (hasData) onEvent({ type });
        type = "message";
        hasData = false;
      } else if (line.startsWith("event:")) {
        type = line.slice("event:".length).trim();
      } else if (line === "data" || line.startsWith("data:")) {
        hasData = true;
      }
    }
  }
}

const MAX_RECONNECT_DELAY_MS = 30_000;

/** Why followEvents stopped. */
export type StreamEnd = "aborted" | "unauthorized" | "evicted";

/**
 * Keeps the live-update stream open until the signal aborts, reconnecting with backoff whenever it
 * drops (onDrop is called each time). Gives up when the token is refused (401), and when the server
 * closed the stream because the user has too many open: reconnecting would only close another of
 * their streams, which would then reconnect in turn.
 */
export async function followEvents(
  onEvent: (event: LiveEvent) => void,
  onDrop: () => void,
  signal: AbortSignal,
): Promise<StreamEnd> {
  let delay = 1000;
  while (!signal.aborted) {
    let evicted = false;
    try {
      await streamEvents((event) => {
        if (event.type === "ready") delay = 1000;
        if (event.type === "evicted") evicted = true;
        onEvent(event);
      }, signal);
    } catch (err) {
      if (signal.aborted) break;
      if (err instanceof ApiError && err.status === 401) return "unauthorized";
    }
    onDrop();
    if (evicted) return "evicted";
    await new Promise((resolve) => window.setTimeout(resolve, delay));
    delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
  }
  return "aborted";
}

/** One read, or an error (after cancelling the stream) if nothing arrives within the limit. */
async function readWithin(reader: ReadableStreamDefaultReader<string>, limitMs: number) {
  let timer: number | undefined;
  const silence = new Promise<never>((_, reject) => {
    timer = window.setTimeout(() => {
      // Reject first: cancelling settles the pending read (as the end of the stream), and
      // whichever settles first wins the race.
      reject(new Error("The live-update stream went silent"));
      void reader.cancel();
    }, limitMs);
  });
  try {
    return await Promise.race([reader.read(), silence]);
  } finally {
    window.clearTimeout(timer);
  }
}
