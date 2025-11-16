export const BACKEND_URL = "http://localhost:8080";

export type Habit = {
  id: number;
  name: string;
  description: string;
  frequency: string;
};

type HabitPayload = {
  name: string;
  description: string;
  frequency: string;
};

export async function fetchHabits(): Promise<Habit[]> {
  const res = await fetch(`${BACKEND_URL}/api/habits`);
  if (!res.ok) throw new Error("Failed to fetch habits");
  return res.json();
}

export async function createHabit(payload: HabitPayload): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/habits`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error("Failed to create habit");
}

export async function updateHabit(id: number, payload: HabitPayload): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/habits/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error("Failed to update habit");
}

export async function deleteHabit(id: number): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/habits/${id}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error("Failed to delete habit");
}
