import { useCallback, useEffect, useState } from "react";
import { logout } from "./api";
import { AuthScreen } from "./components/AuthScreen";
import { Dashboard } from "./components/Dashboard";

// api.ts reads the token from here for every request.
const TOKEN_KEY = "token";

function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));
  // Shown on the sign-in screen when the API refused the token. The user's own sign-out shows nothing.
  const [notice, setNotice] = useState<string | null>(null);

  const signIn = useCallback((newToken: string) => {
    localStorage.setItem(TOKEN_KEY, newToken);
    setNotice(null);
    setToken(newToken);
  }, []);

  // Revoking the token on the server is best effort: the screen never waits for it, and a failure
  // (the API is down) still signs this browser out.
  const signOut = useCallback(() => {
    if (token) logout(token).catch(() => undefined);
    localStorage.removeItem(TOKEN_KEY);
    setNotice(null);
    setToken(null);
  }, [token]);

  const sessionExpired = useCallback(() => {
    // A late 401 for a token another tab has already signed out or replaced changes nothing.
    if (localStorage.getItem(TOKEN_KEY) !== token) return;
    localStorage.removeItem(TOKEN_KEY);
    setNotice("Your session expired. Sign in again.");
    setToken(null);
  }, [token]);

  // Another tab signed out, or signed in (perhaps as someone else). Follow it, so this tab never
  // shows one account while sending another's token. The browser fires this only in other tabs.
  useEffect(() => {
    function onStorage(e: StorageEvent) {
      // A null key means the whole storage was cleared.
      if (e.key !== TOKEN_KEY && e.key !== null) return;
      setNotice(null);
      setToken(localStorage.getItem(TOKEN_KEY));
    }
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  return token ? (
    <Dashboard key={token} onSignOut={signOut} onSessionExpired={sessionExpired} />
  ) : (
    <AuthScreen onSignIn={signIn} notice={notice} />
  );
}

export default App;
