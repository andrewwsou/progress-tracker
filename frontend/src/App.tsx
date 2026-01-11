import { useEffect, useState } from "react";
import type { Habit } from "./api";
import { fetchHabits, createHabit, updateHabit, deleteHabit, loginUser, registerUser, completeHabit } from "./api";

type AuthMode = "login" | "register";

function App() {
  const [token, setToken] = useState<string | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState<string | null>(null);

  const [habits, setHabits] = useState<Habit[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [frequency, setFrequency] = useState("DAILY");
  const [loading, setLoading] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);

  useEffect(() => {
    const stored = localStorage.getItem("token");
    if (stored) setToken(stored);
  }, []);

  useEffect(() => {
    if (token) loadHabits();
  }, [token]);

  async function loadHabits() {
    setLoading(true);
    try {
      const data = await fetchHabits();
      setHabits(data);
    } finally {
      setLoading(false);
    }
  }

  async function handleAuthSubmit(e: React.FormEvent) {
    e.preventDefault();
    setAuthError(null);
    try {
      const newToken = authMode === "login"
        ? await loginUser(email, password)
        : await registerUser(email, password);

      localStorage.setItem("token", newToken);
      setToken(newToken);
      setPassword("");
    } catch (err: any) {
      setAuthError(err.message || "Authentication failed");
    }
  }

  function handleLogout() {
    localStorage.removeItem("token");
    setToken(null);
    setHabits([]);
  }

  async function handleSubmitHabit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    const payload = { name, description, frequency };
    if (editingId === null) {
      await createHabit(payload);
    } else {
      await updateHabit(editingId, payload);
    }
    setEditingId(null);
    setName("");
    setDescription("");
    setFrequency("DAILY");
    loadHabits();
  }

  async function handleDeleteHabit(id: number) {
    await deleteHabit(id);
    loadHabits();
  }

  async function handleCompleteHabit(id: number) {
    await completeHabit(id);
    loadHabits();
  }

  function startEditHabit(h: Habit) {
    setEditingId(h.id);
    setName(h.name);
    setDescription(h.description);
    setFrequency(h.frequency);
  }

  if (!token) {
    return (
      <main style={{ maxWidth: 400, margin: "0 auto", padding: "2rem" }}>
        <h1>HabitHero</h1>
        <h2>{authMode === "login" ? "Login" : "Register"}</h2>

        <form onSubmit={handleAuthSubmit}>
          <input placeholder="Email" value={email} onChange={e => setEmail(e.target.value)} />
          <input placeholder="Password" type="password" value={password} onChange={e => setPassword(e.target.value)} />
          {authError && <p style={{ color: "red" }}>{authError}</p>}
          <button type="submit">{authMode === "login" ? "Log In" : "Register"}</button>
        </form>

        <button onClick={() => setAuthMode(authMode === "login" ? "register" : "login")}>
          {authMode === "login" ? "Create account" : "Login"}
        </button>
      </main>
    );
  }

  return (
    <main style={{ maxWidth: 900, margin: "0 auto", padding: "2rem" }}>
      <header style={{ display: "flex", justifyContent: "space-between" }}>
        <h1>HabitHero</h1>
        <button onClick={handleLogout}>Logout</button>
      </header>

      <form onSubmit={handleSubmitHabit}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="Habit name" />
        <input value={description} onChange={e => setDescription(e.target.value)} placeholder="Description" />
        <select value={frequency} onChange={e => setFrequency(e.target.value)}>
          <option value="DAILY">Daily</option>
          <option value="WEEKLY">Weekly</option>
        </select>
        <button type="submit">{editingId ? "Save" : "Add"}</button>
      </form>

      <h2>Your Habits</h2>

      {loading && <p>Loading…</p>}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: "1rem" }}>
        {habits.map(h => (
          <div key={h.id} style={{ border: "1px solid #ccc", padding: "1rem", borderRadius: 8 }}>
            <h3>{h.name}</h3>
            <p>{h.description}</p>
            <p>{h.frequency}</p>
            <p>Current Streak: {h.currentStreak}</p>
            <p>Longest Streak: {h.longestStreak}</p>
            <p>XP: {h.xpTotal}</p>
            <button onClick={() => handleCompleteHabit(h.id)}>Complete</button>
            <button onClick={() => startEditHabit(h)}>Edit</button>
            <button onClick={() => handleDeleteHabit(h.id)}>Delete</button>
          </div>
        ))}
      </div>
    </main>
  );
}

export default App;
