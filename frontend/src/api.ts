import type { components } from "./api-types";

export const BACKEND_URL: string = import.meta.env.VITE_API_URL ?? "http://localhost:8080";

// These types are generated from the backend's OpenAPI contract (`npm run generate:api`),
// so a field that is renamed or removed on the server becomes a compile error here.
export type Habit = components["schemas"]["HabitResponse"];
export type HabitInput = components["schemas"]["HabitRequest"];
export type Achievement = components["schemas"]["UserAchievementDto"];
export type WeeklySummary = components["schemas"]["WeeklySummaryResponse"];
type AuthResponse = components["schemas"]["AuthResponse"];
type Problem = components["schemas"]["ProblemDetail"];

function authHeaders(): HeadersInit {
  const token = localStorage.getItem("token");
  if (!token) return {};
  return {
    Authorization: `Bearer ${token}`,
  };
}

/** An error response from the API. The status lets callers react to specific cases, such as 401. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
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

export async function registerUser(email: string, password: string): Promise<string> {
  const res = await fetch(`${BACKEND_URL}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw await toError(res, "Failed to register");

  const data: AuthResponse = await res.json();
  return data.token;
}

export async function loginUser(email: string, password: string): Promise<string> {
  const res = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw await toError(res, "Failed to login");

  const data: AuthResponse = await res.json();
  return data.token;
}

export async function fetchHabits(): Promise<Habit[]> {
  const res = await fetch(`${BACKEND_URL}/api/habits`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to fetch habits");
  return res.json();
}

export async function createHabit(payload: HabitInput): Promise<Habit> {
  const res = await fetch(`${BACKEND_URL}/api/habits`, {
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
  const res = await fetch(`${BACKEND_URL}/api/habits/${id}`, {
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
  const res = await fetch(`${BACKEND_URL}/api/achievements`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to fetch achievements");
  return res.json();
}

/** The newest finished weekly summary, or null if none has been written yet (204). */
export async function fetchLatestSummary(): Promise<WeeklySummary | null> {
  const res = await fetch(`${BACKEND_URL}/api/summaries/latest`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (res.status === 204) return null;
  if (!res.ok) throw await toError(res, "Failed to fetch the weekly summary");
  return res.json();
}

export async function deleteHabit(id: number): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/habits/${id}`, {
    method: "DELETE",
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to delete habit");
}

export async function completeHabit(habitId: number): Promise<Habit> {
  const res = await fetch(`${BACKEND_URL}/api/habits/${habitId}/complete`, {
    method: "POST",
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw await toError(res, "Failed to complete habit");
  return res.json();
}
