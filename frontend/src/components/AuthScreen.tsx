import { useState } from "react";
import { loginUser, registerUser } from "../api";
import { errorText } from "../format";
import { LogoMark } from "./Icons";

type Mode = "login" | "register";

export function AuthScreen({ onSignIn }: { onSignIn: (token: string) => void }) {
  const [mode, setMode] = useState<Mode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const registering = mode === "register";

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const token = registering ? await registerUser(email, password) : await loginUser(email, password);
      onSignIn(token);
    } catch (err) {
      setError(errorText(err, registering ? "Could not create the account" : "Could not sign in"));
      setSubmitting(false);
    }
  }

  function switchMode() {
    setMode(registering ? "login" : "register");
    setError(null);
  }

  return (
    <main className="auth">
      <div className="auth__panel">
        <div className="brand brand--large">
          <LogoMark size={28} />
          <span>ProgressArc</span>
        </div>
        <p className="auth__intro">Track daily and weekly habits, keep your streaks, and earn XP for every day you show up.</p>

        <form className="form" onSubmit={handleSubmit} noValidate>
          <h1 className="auth__title">{registering ? "Create your account" : "Sign in"}</h1>

          <label className="field">
            <span className="field__label">Email</span>
            <input
              className="input"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </label>

          <div className="field">
            <label className="field__label" htmlFor="password">
              Password
            </label>
            <input
              id="password"
              className="input"
              type="password"
              autoComplete={registering ? "new-password" : "current-password"}
              minLength={registering ? 8 : undefined}
              maxLength={72}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              aria-describedby={registering ? "password-hint" : undefined}
            />
            {registering && (
              <span id="password-hint" className="field__hint">
                At least 8 characters.
              </span>
            )}
          </div>

          {error && (
            <p className="notice notice--error" role="alert">
              {error}
            </p>
          )}

          <button className="button button--primary button--block" type="submit" disabled={submitting}>
            {submitting ? "Please wait…" : registering ? "Create account" : "Sign in"}
          </button>
        </form>

        <p className="auth__switch">
          {registering ? "Already have an account?" : "New here?"}{" "}
          <button className="link" type="button" onClick={switchMode}>
            {registering ? "Sign in" : "Create an account"}
          </button>
        </p>
      </div>
    </main>
  );
}
