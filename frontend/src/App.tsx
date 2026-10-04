import { useCallback, useState } from "react";
import { AuthScreen } from "./components/AuthScreen";
import { Dashboard } from "./components/Dashboard";

// api.ts reads the token from here for every request.
const TOKEN_KEY = "token";

function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_KEY));

  const signIn = useCallback((newToken: string) => {
    localStorage.setItem(TOKEN_KEY, newToken);
    setToken(newToken);
  }, []);

  const signOut = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setToken(null);
  }, []);

  return token ? <Dashboard key={token} onSignOut={signOut} /> : <AuthScreen onSignIn={signIn} />;
}

export default App;
