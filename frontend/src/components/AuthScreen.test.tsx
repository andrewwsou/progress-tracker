import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthScreen } from "./AuthScreen";

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

function signIn() {
  fireEvent.change(screen.getByRole("textbox", { name: "Email" }), { target: { value: "a@example.com" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password1" } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
}

describe("AuthScreen", () => {
  it("explains a server it cannot reach", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    render(<AuthScreen onSignIn={vi.fn()} />);
    signIn();

    expect((await screen.findByRole("alert")).textContent).toBe(
      "Can't reach the server. Check that the API is running, then try again.",
    );
  });

  it("shows the server's reason for refusing a sign-in", async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ status: 429, detail: "Too many sign-in attempts. Try again in 60 seconds." }), {
        status: 429,
        headers: { "Content-Type": "application/problem+json", "Retry-After": "60" },
      }),
    );
    render(<AuthScreen onSignIn={vi.fn()} />);
    signIn();

    expect((await screen.findByRole("alert")).textContent).toBe("Too many sign-in attempts. Try again in 60 seconds.");
  });

  it("shows why the user was signed out, and moves focus there so it is read out", () => {
    render(<AuthScreen onSignIn={vi.fn()} notice="Your session expired. Sign in again." />);

    const notice = screen.getByRole("status");
    expect(notice.textContent).toBe("Your session expired. Sign in again.");
    expect(document.activeElement).toBe(notice);
  });

  it("does not take focus back when the notice shows again after an error", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    render(<AuthScreen onSignIn={vi.fn()} notice="Your session expired. Sign in again." />);
    signIn();
    await screen.findByRole("alert");
    expect(screen.queryByRole("status")).toBeNull();

    // Switching to Create account clears the error, which brings the notice back.
    const switchMode = screen.getByRole("button", { name: "Create an account" });
    switchMode.focus();
    fireEvent.click(switchMode);

    expect(screen.getByRole("status")).toBeTruthy();
    expect(document.activeElement).toBe(switchMode);
  });

  it("shows no notice by default, and leaves focus alone", () => {
    render(<AuthScreen onSignIn={vi.fn()} />);

    expect(screen.queryByRole("status")).toBeNull();
    expect(document.activeElement).toBe(document.body);
  });
});
