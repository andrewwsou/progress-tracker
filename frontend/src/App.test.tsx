import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";

const server = vi.hoisted(() => ({ loggedOut: [] as string[], reachable: true }));

// A plain function rather than vi.fn(): a mock function handles the promises it returns, which
// would hide a failed logout that the sign-out leaves unhandled.
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  logout: (token: string) => {
    server.loggedOut.push(token);
    return server.reachable ? Promise.resolve() : Promise.reject(new Error("Can't reach the server"));
  },
}));

// A stand-in dashboard that shows which token it was mounted with and offers both ways out.
vi.mock("./components/Dashboard", () => ({
  Dashboard: function FakeDashboard({ onSignOut, onSessionExpired }: { onSignOut: () => void; onSessionExpired: () => void }) {
    const [token] = useState(() => localStorage.getItem("token"));
    return (
      <div>
        <p>Dashboard for {token}</p>
        <button onClick={onSignOut}>Sign out</button>
        <button onClick={onSessionExpired}>Token refused</button>
      </div>
    );
  },
}));

beforeEach(() => {
  localStorage.setItem("token", "alice-token");
  server.loggedOut = [];
  server.reachable = true;
});

afterEach(() => {
  cleanup();
  localStorage.clear();
});

/** What the browser does in this tab when another tab changes the token. */
function otherTabSets(token: string | null) {
  if (token === null) localStorage.removeItem("token");
  else localStorage.setItem("token", token);
  act(() => {
    window.dispatchEvent(new StorageEvent("storage", { key: "token", newValue: token }));
  });
}

describe("signing out", () => {
  it("revokes the token on the server and shows no notice", () => {
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(server.loggedOut).toEqual(["alice-token"]);
    expect(localStorage.getItem("token")).toBeNull();
    expect(screen.getByRole("heading", { name: "Sign in" })).toBeTruthy();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("does not wait for the server, or fail with it", async () => {
    server.reachable = false;
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeTruthy();
    // Vitest fails the run on an unhandled rejection; give one the chance to surface.
    await new Promise((resolve) => setTimeout(resolve, 0));
  });

  it("says the session expired when the API refuses the token", () => {
    render(<App />);
    fireEvent.click(screen.getByRole("button", { name: "Token refused" }));

    expect(screen.getByRole("status").textContent).toBe("Your session expired. Sign in again.");
    expect(document.activeElement).toBe(screen.getByRole("status"));
    expect(localStorage.getItem("token")).toBeNull();
    expect(server.loggedOut).toEqual([]);
  });
});

describe("other tabs", () => {
  it("follows a sign-out in another tab", () => {
    render(<App />);
    otherTabSets(null);

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeTruthy();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("follows a switch to another account", () => {
    render(<App />);
    otherTabSets("bob-token");

    expect(screen.getByText("Dashboard for bob-token")).toBeTruthy();
  });

  it("ignores a late refusal of a token another tab has already replaced", () => {
    render(<App />);
    localStorage.setItem("token", "bob-token"); // the storage event has not arrived yet
    fireEvent.click(screen.getByRole("button", { name: "Token refused" }));

    expect(localStorage.getItem("token")).toBe("bob-token");
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("follows another tab clearing the storage", () => {
    render(<App />);
    localStorage.clear();
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: null }));
    });

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeTruthy();
  });
});
