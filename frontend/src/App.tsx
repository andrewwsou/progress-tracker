import { useEffect, useState } from "react";
import {
  fetchHabits,
  createHabit,
  updateHabit,
  deleteHabit,
  type Habit,
} from "./api";

function App() {
  const [habits, setHabits] = useState<Habit[]>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [frequency, setFrequency] = useState("DAILY");
  const [loading, setLoading] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);

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

  useEffect(() => {
    loadHabits();
  }, []);

  async function handleSubmit(e: React.FormEvent) {
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

      await loadHabits();
    } catch (err) {
      console.error(err);
    }
  }

  async function handleDelete(id: number) {
    try {
      await deleteHabit(id);
      await loadHabits();
    } catch (err) {
      console.error(err);
    }
  }

  function startEdit(habit: Habit) {
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

      <section>
        <h2>Your Habits</h2>
        {loading && <p>Loading...</p>}
        {!loading && habits.length === 0 && <p>No habits yet.</p>}
        <ul>
          {habits.map((habit) => (
            <li key={habit.id}>
              <strong>{habit.name}</strong> ({habit.frequency}) —{" "}
              {habit.description}{" "}
              <button onClick={() => startEdit(habit)}>Edit</button>{" "}
              <button onClick={() => handleDelete(habit.id)}>Delete</button>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}

export default App;
