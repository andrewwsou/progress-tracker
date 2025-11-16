import { useEffect, useState } from "react";
import type { Habit } from "./api";
import {
  fetchHabits,
  createHabit,
  updateHabit,
  deleteHabit,
  loginUser,
  registerUser,
} from "./api";

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
    if (stored) {
      setToken(stored);
    }
  }, []);

  useEffect(() => {
    if (!token) return;
    loadHabits();
  }, [token]);

  async function loadHabits() {
    setLoading(true);
    try {
      const data = await fetchHabits();
      setHabits(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }


  async function handleAuthSubmit(e: React.FormEvent) {
    e.preventDefault();
    setAuthError(null);
    try {
      let newToken: string;
      if (authMode === "login") {
        newToken = await loginUser(email, password);
      } else {
        newToken = await registerUser(email, password);
      }
      console.log("newToken from auth:", newToken);

      localStorage.setItem("token", newToken);
      setToken(newToken);
      setPassword("");
    } catch (err: any) {
      console.error(err);
      setAuthError(err.message || "Authentication failed");
    }
  }

  function handleLogout() {
    localStorage.removeItem("token");
    setToken(null);
    setHabits([]);
    setEmail("");
    setPassword("");
    setEditingId(null);
    setName("");
    setDescription("");
    setFrequency("DAILY");
  }


  async function handleSubmitHabit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;

    const payload = { name, description, frequency };

    try {
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
    } catch (err) {
      console.error(err);
    }
  }

  async function handleDeleteHabit(id: number) {
    try {
      await deleteHabit(id);
      loadHabits();
    } catch (err) {
      console.error(err);
    }
  }

  function startEditHabit(habit: Habit) {
    setEditingId(habit.id);
    setName(habit.name);
    setDescription(habit.description);
    setFrequency(habit.frequency);
  }

  function cancelEdit() {
    setEditingId(null);
    setName("");
    setDescription("");
    setFrequency("DAILY");
  }


  if (!token) {
    return (
      <main
        style={{
          maxWidth: 400,
          margin: "0 auto",
          padding: "2rem",
          fontFamily: "system-ui, -apple-system, BlinkMacSystemFont",
        }}
      >
        <h1>HabitHero</h1>
        <h2>{authMode === "login" ? "Login" : "Register"}</h2>

        <form onSubmit={handleAuthSubmit}>
          <div style={{ marginBottom: "0.75rem" }}>
            <label>
              Email{" "}
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </label>
          </div>

          <div style={{ marginBottom: "0.75rem" }}>
            <label>
              Password{" "}
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>
          </div>

          {authError && (
            <p style={{ color: "red", marginBottom: "0.75rem" }}>{authError}</p>
          )}

          <button type="submit">
            {authMode === "login" ? "Log In" : "Create Account"}
          </button>
        </form>

        <p style={{ marginTop: "1rem" }}>
          {authMode === "login" ? "Need an account?" : "Already have an account?"}{" "}
          <button
            type="button"
            onClick={() =>
              setAuthMode(authMode === "login" ? "register" : "login")
            }
          >
            {authMode === "login" ? "Register" : "Log In"}
          </button>
        </p>
      </main>
    );
  }

  return (
    <main
      style={{
        maxWidth: 800,
        margin: "0 auto",
        padding: "2rem",
        fontFamily: "system-ui, -apple-system, BlinkMacSystemFont",
      }}
    >
      <header style={{ display: "flex", justifyContent: "space-between", marginBottom: "1.5rem" }}>
        <h1>HabitHero</h1>
        <button type="button" onClick={handleLogout}>
          Logout
        </button>
      </header>

      <section style={{ marginBottom: "2rem" }}>
        <h2>{editingId === null ? "Add Habit" : "Edit Habit"}</h2>
        <form onSubmit={handleSubmitHabit}>
          <div style={{ marginBottom: "0.5rem" }}>
            <label>
              Name{" "}
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </label>
          </div>

          <div style={{ marginBottom: "0.5rem" }}>
            <label>
              Description{" "}
              <input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </label>
          </div>

          <div style={{ marginBottom: "0.5rem" }}>
            <label>
              Frequency{" "}
              <select
                value={frequency}
                onChange={(e) => setFrequency(e.target.value)}
              >
                <option value="DAILY">Daily</option>
                <option value="WEEKLY">Weekly</option>
              </select>
            </label>
          </div>

          <div style={{ marginTop: "0.75rem" }}>
            <button type="submit">
              {editingId === null ? "Add Habit" : "Save Changes"}
            </button>
            {editingId !== null && (
              <button
                type="button"
                onClick={cancelEdit}
                style={{ marginLeft: "0.5rem" }}
              >
                Cancel
              </button>
            )}
          </div>
        </form>
      </section>

      <section>
        <h2>Your Habits</h2>
        {loading && <p>Loading...</p>}
        {!loading && habits.length === 0 && <p>No habits yet.</p>}
        <ul>
          {habits.map((habit) => (
            <li key={habit.id}>
              <strong>{habit.name}</strong> ({habit.frequency}) —{" "}
              {habit.description}{" "}
              <button onClick={() => startEditHabit(habit)}>Edit</button>{" "}
              <button onClick={() => handleDeleteHabit(habit.id)}>
                Delete
              </button>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}

export default App;
