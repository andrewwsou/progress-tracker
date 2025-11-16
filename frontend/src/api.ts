export const BACKEND_URL = "http://localhost:8080";

export type Habit = {
  id: number;
  name: string;
  description: string;
  frequency: string;
};

export type AuthResponse = {
  token: string;
};

function authHeaders(): HeadersInit {
  const token = localStorage.getItem("token");
  console.log("authHeaders token:", token);
  if (!token) return {};
  return {
    Authorization: `Bearer ${token}`,
  };
}


export async function registerUser(email: string, password: string): Promise<string> {
  const res = await fetch(`${BACKEND_URL}/api/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || "Failed to register");
  }

  const data: AuthResponse = await res.json();
  return data.token;
}

export async function loginUser(email: string, password: string): Promise<string> {
  const res = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });

  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || "Failed to login");
  }

  const data: AuthResponse = await res.json();
  return data.token; 
}
 

export async function fetchHabits(): Promise<Habit[]> {
  const res = await fetch(`${BACKEND_URL}/api/habits`, {
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw new Error("Failed to fetch habits");
  return res.json();
}

export async function createHabit(payload: {
  name: string;
  description: string;
  frequency: string;
}) {
  const res = await fetch(`${BACKEND_URL}/api/habits`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error("Failed to create habit");
}

export async function updateHabit(
  id: number,
  payload: { name: string; description: string; frequency: string }
) {
  const res = await fetch(`${BACKEND_URL}/api/habits/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error("Failed to update habit");
}

export async function deleteHabit(id: number) {
  const res = await fetch(`${BACKEND_URL}/api/habits/${id}`, {
    method: "DELETE",
    headers: {
      ...authHeaders(),
    },
  });
  if (!res.ok) throw new Error("Failed to delete habit");
}
