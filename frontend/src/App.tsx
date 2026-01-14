import { useEffect, useMemo, useState } from "react";
import type { Habit, Achievement } from "./api";
import {
  fetchHabits,
  createHabit,
  updateHabit,
  deleteHabit,
  loginUser,
  registerUser,
  completeHabit,
  fetchAchievements,
} from "./api";

type AuthMode = "login" | "register";

function clampInt(value: string, min: number, max: number): number {
  const n = Number.parseInt(value, 10);
  if (Number.isNaN(n)) return min;
  return Math.min(max, Math.max(min, n));
}

function App() {
  const [token, setToken] = useState<string | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState<string | null>(null);

  const [habits, setHabits] = useState<Habit[]>([]);
  const [achievements, setAchievements] = useState<Achievement[]>([]);

  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [frequency, setFrequency] = useState("DAILY");
  const [goalTargetCount, setGoalTargetCount] = useState("1");
  const [goalPeriod, setGoalPeriod] = useState("DAILY");

  const [loading, setLoading] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);

  useEffect(() => {
    const stored = localStorage.getItem("token");
    if (stored) setToken(stored);
  }, []);

  useEffect(() => {
    if (token) {
      loadAll();
    }
  }, [token]);

  async function loadAll() {
    setLoading(true);
    try {
      const [h, a] = await Promise.all([fetchHabits(), fetchAchievements().catch(() => [])]);
      setHabits(h);
      setAchievements(a as Achievement[]);
    } finally {
      setLoading(false);
    }
  }

  async function handleAuthSubmit(e: React.FormEvent) {
    e.preventDefault();
    setAuthError(null);
    try {
      const newToken =
        authMode === "login" ? await loginUser(email, password) : await registerUser(email, password);
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
    setAchievements([]);
  }

  async function handleSubmitHabit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    const payload = {
      name,
      description,
      frequency,
      goalTargetCount: clampInt(goalTargetCount, 1, 365),
      goalPeriod,
    };
    if (editingId === null) {
      await createHabit(payload);
    } else {
      await updateHabit(editingId, payload);
    }
    setEditingId(null);
    setName("");
    setDescription("");
    setFrequency("DAILY");
    setGoalTargetCount("1");
    setGoalPeriod("DAILY");
    await loadAll();
  }

  async function handleDeleteHabit(id: number) {
    await deleteHabit(id);
    await loadAll();
  }

  async function handleCompleteHabit(id: number) {
    await completeHabit(id);
    await loadAll();
  }

  function startEditHabit(h: Habit) {
    setEditingId(h.id);
    setName(h.name);
    setDescription(h.description);
    setFrequency(h.frequency);
    setGoalTargetCount(String(h.goalTargetCount ?? 1));
    setGoalPeriod(h.goalPeriod ?? (h.frequency === "WEEKLY" ? "WEEKLY" : "DAILY"));
  }

  const unlockedNames = useMemo(() => achievements.map((a) => a.achievement.name), [achievements]);

  if (!token) {
    return (
      <main style={{ maxWidth: 420, margin: "0 auto", padding: "2rem" }}>
        <h1>HabitHero</h1>
        <h2>{authMode === "login" ? "Login" : "Register"}</h2>

        <form onSubmit={handleAuthSubmit}>
          <input placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <input
            placeholder="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
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
    <main style={{ maxWidth: 1000, margin: "0 auto", padding: "2rem" }}>
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div>
          <h1 style={{ margin: 0 }}>HabitHero</h1>
          <p style={{ margin: "0.25rem 0 0", color: "#666" }}>Streaks • XP • Goals • Achievements</p>
        </div>
        <button onClick={handleLogout}>Logout</button>
      </header>

      <section style={{ marginTop: "1.25rem", padding: "1rem", border: "1px solid #eee", borderRadius: 12 }}>
        <h2 style={{ marginTop: 0 }}>Unlocked Achievements</h2>
        {unlockedNames.length === 0 ? (
          <p style={{ margin: 0, color: "#666" }}>No achievements yet — complete a habit to unlock your first!</p>
        ) : (
          <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
            {achievements.map((a) => (
              <span
                key={a.id}
                style={{
                  border: "1px solid #ddd",
                  borderRadius: 999,
                  padding: "0.25rem 0.6rem",
                  fontSize: 12,
                  background: "#fafafa",
                }}
                title={a.achievement.description}
              >
                {a.achievement.name}
              </span>
            ))}
          </div>
        )}
      </section>

      <section style={{ marginTop: "1.25rem" }}>
        <h2>{editingId ? "Edit Habit" : "Add Habit"}</h2>

        <form onSubmit={handleSubmitHabit} style={{ display: "grid", gap: "0.5rem", maxWidth: 720 }}>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Habit name" />
          <input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Description" />

          <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
            <label style={{ display: "flex", gap: "0.4rem", alignItems: "center" }}>
              Frequency
              <select
                value={frequency}
                onChange={(e) => {
                  const next = e.target.value;
                  setFrequency(next);
                  setGoalPeriod(next === "WEEKLY" ? "WEEKLY" : "DAILY");
                }}
              >
                <option value="DAILY">Daily</option>
                <option value="WEEKLY">Weekly</option>
              </select>
            </label>

            <label style={{ display: "flex", gap: "0.4rem", alignItems: "center" }}>
              Goal
              <input
                type="number"
                min={1}
                max={365}
                value={goalTargetCount}
                onChange={(e) => setGoalTargetCount(e.target.value)}
                style={{ width: 90 }}
              />
            </label>

            <label style={{ display: "flex", gap: "0.4rem", alignItems: "center" }}>
              Per
              <select value={goalPeriod} onChange={(e) => setGoalPeriod(e.target.value)}>
                <option value="DAILY">Day</option>
                <option value="WEEKLY">Week</option>
              </select>
            </label>

            <button type="submit">{editingId ? "Save" : "Add"}</button>
            {editingId && (
              <button
                type="button"
                onClick={() => {
                  setEditingId(null);
                  setName("");
                  setDescription("");
                  setFrequency("DAILY");
                  setGoalTargetCount("1");
                  setGoalPeriod("DAILY");
                }}
              >
                Cancel
              </button>
            )}
          </div>
        </form>
      </section>

      <section style={{ marginTop: "1.25rem" }}>
        <h2>Your Habits</h2>
        {loading && <p>Loading…</p>}

        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: "1rem" }}>
          {habits.map((h) => {
            const pc = h.progressCount ?? 0;
            const pt = h.progressTargetCount ?? (h.goalTargetCount ?? 1);
            const ratio = pt <= 0 ? 0 : Math.min(1, pc / pt);
            return (
              <div key={h.id} style={{ border: "1px solid #ddd", padding: "1rem", borderRadius: 12 }}>
                <h3 style={{ marginTop: 0 }}>{h.name}</h3>
                <p style={{ margin: "0.25rem 0 0.75rem", color: "#555" }}>{h.description}</p>

                <p style={{ margin: 0 }}>
                  <strong>Frequency:</strong> {h.frequency}
                </p>
                <p style={{ margin: 0 }}>
                  <strong>Goal:</strong> {h.goalTargetCount ?? 1} per{" "}
                  {h.goalPeriod ?? (h.frequency === "WEEKLY" ? "WEEKLY" : "DAILY")}
                </p>

                <div style={{ marginTop: "0.6rem" }}>
                  <p style={{ margin: "0 0 0.25rem" }}>
                    <strong>Progress:</strong> {pc}/{pt}
                  </p>
                  <div style={{ height: 8, background: "#eee", borderRadius: 999, overflow: "hidden" }}>
                    <div style={{ height: 8, width: `${Math.round(ratio * 100)}%`, background: "#4f46e5" }} />
                  </div>
                </div>

                <div style={{ marginTop: "0.75rem" }}>
                  <p style={{ margin: 0 }}>
                    <strong>Current Streak:</strong> {h.currentStreak ?? 0}
                  </p>
                  <p style={{ margin: 0 }}>
                    <strong>Longest Streak:</strong> {h.longestStreak ?? 0}
                  </p>
                  <p style={{ margin: 0 }}>
                    <strong>XP:</strong> {h.xpTotal ?? 0}
                  </p>
                </div>

                <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.9rem", flexWrap: "wrap" }}>
                  <button onClick={() => handleCompleteHabit(h.id)}>Complete</button>
                  <button onClick={() => startEditHabit(h)}>Edit</button>
                  <button onClick={() => handleDeleteHabit(h.id)}>Delete</button>
                </div>
              </div>
            );
          })}
        </div>
      </section>
    </main>
  );
}

export default App;
