import { useEffect, useState } from "react";

type Habit = {
  id: number;
  name: string;
  description: string;
  frequency: string;
};

const BACKEND_URL = "http://localhost:8080";

function App() {
  const [habits, setHabits] = useState<Habit[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [frequency, setFrequency] = useState("DAILY");
  const [loading, setLoading] = useState(false);

  async function loadHabits() {
    setLoading(true);
    try {
      const res = await fetch(`${BACKEND_URL}/api/habits`);
      const data = await res.json();
      setHabits(data);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadHabits();
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;

    await fetch(`${BACKEND_URL}/api/habits`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, description, frequency }),
    });

    setName("");
    setDescription("");
    setFrequency("DAILY");
    loadHabits();
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
      <h1>Progress Tracker</h1>

      <form onSubmit={handleSubmit} style={{ marginBottom: "2rem" }}>
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

        <button type="submit">Add Habit</button>
      </form>

      <section>
        <h2>Your Habits</h2>
        {loading && <p>Loading...</p>}
        {!loading && habits.length === 0 && <p>No habits yet.</p>}
        <ul>
          {habits.map((habit) => (
            <li key={habit.id}>
              <strong>{habit.name}</strong> ({habit.frequency}) —{" "}
              {habit.description}
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}

export default App;
