import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, fetchHabits, followEvents, loginUser, logout, streamEvents, type LiveEvent } from "./api";
import { errorText } from "./format";

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

/** A text/event-stream response that sends these chunks, one read each, then ends (unless told not to). */
function sseResponse(chunks: string[], { end = true } = {}): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      if (end) controller.close();
    },
  });
  return new Response(body, { headers: { "Content-Type": "text/event-stream" } });
}

function problem(status: number, detail: string): Response {
  return new Response(JSON.stringify({ status, detail }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

async function eventsFrom(chunks: string[]): Promise<LiveEvent[]> {
  fetchMock.mockResolvedValueOnce(sseResponse(chunks));
  const events: LiveEvent[] = [];
  await streamEvents((event) => events.push(event), new AbortController().signal);
  return events;
}

describe("streamEvents", () => {
  it("ignores keep-alive comments", async () => {
    expect(await eventsFrom([": keep-alive\n\n", "event: reward\ndata: {}\n\n"])).toEqual([{ type: "reward" }]);
  });

  it("puts together an event split across chunks", async () => {
    expect(await eventsFrom(["event: rew", "ard\ndata: {", "}\n", "\n"])).toEqual([{ type: "reward" }]);
  });

  it("reads CRLF line endings", async () => {
    expect(await eventsFrom(["event: summary\r\ndata: {}\r\n\r\n"])).toEqual([{ type: "summary" }]);
  });

  it("ignores a block without data", async () => {
    expect(await eventsFrom(["event: reward\n\n", "event: ready\ndata: {}\n\n"])).toEqual([{ type: "ready" }]);
  });

  it("turns a refused token into an ApiError with status 401", async () => {
    fetchMock.mockResolvedValueOnce(problem(401, "Full authentication is required"));
    const result = streamEvents(() => undefined, new AbortController().signal);
    await expect(result).rejects.toBeInstanceOf(ApiError);
    await expect(result).rejects.toMatchObject({ status: 401 });
  });

  it("gives up on a stream that has sent nothing for 60 seconds", async () => {
    vi.useFakeTimers();
    fetchMock.mockResolvedValueOnce(sseResponse([], { end: false }));
    let failure: unknown;
    const done = streamEvents(() => undefined, new AbortController().signal).catch((err) => {
      failure = err;
    });

    await vi.advanceTimersByTimeAsync(59_999);
    expect(failure).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1);
    await done;
    expect(failure).toBeInstanceOf(Error);
    expect((failure as Error).message).toBe("The live-update stream went silent");
  });

  it("lets an abort through unchanged", async () => {
    const controller = new AbortController();
    controller.abort();
    const abort = new DOMException("The operation was aborted.", "AbortError");
    fetchMock.mockRejectedValueOnce(abort);
    await expect(streamEvents(() => undefined, controller.signal)).rejects.toBe(abort);
  });
});

describe("network failures", () => {
  it("become a message a person can read", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const err = await fetchHabits().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(0);
    expect(errorText(err, "Could not load your habits.")).toBe(
      "Can't reach the server. Check that the API is running, then try again.",
    );
  });

  it("leave the server's own messages alone", async () => {
    fetchMock.mockResolvedValueOnce(problem(429, "Too many sign-in attempts. Try again in 60 seconds."));
    await expect(loginUser("a@example.com", "password1")).rejects.toMatchObject({
      status: 429,
      message: "Too many sign-in attempts. Try again in 60 seconds.",
    });
  });
});

describe("logout", () => {
  it("sends the token being signed out", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await logout("token-1");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/auth\/logout$/);
    expect(init?.method).toBe("POST");
    expect(init?.headers).toEqual({ Authorization: "Bearer token-1" });
  });
});

describe("followEvents", () => {
  it("reconnects after a stream ends", async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(async () => sseResponse(["event: ready\ndata: {}\n\n"]));
    const controller = new AbortController();
    const result = followEvents(() => undefined, () => undefined, controller.signal);

    await vi.advanceTimersByTimeAsync(999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    controller.abort();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(await result).toBe("aborted");
  });

  it("waits twice as long after each drop, up to 30 seconds", async () => {
    vi.useFakeTimers();
    const start = Date.now();
    const connectedAt: number[] = [];
    fetchMock.mockImplementation(async () => {
      connectedAt.push(Date.now() - start);
      return sseResponse([]); // ends before "ready"
    });
    const controller = new AbortController();
    const result = followEvents(() => undefined, () => undefined, controller.signal);

    await vi.advanceTimersByTimeAsync(100_000);
    // Waits of 1, 2, 4, 8 and 16 s, then 30 s instead of 32 s.
    expect(connectedAt).toEqual([0, 1_000, 3_000, 7_000, 15_000, 31_000, 61_000, 91_000]);
    controller.abort();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(await result).toBe("aborted");
  });

  it("starts again from 1 second after a stream that got as far as ready", async () => {
    vi.useFakeTimers();
    const start = Date.now();
    const connectedAt: number[] = [];
    const streams = [[], [], ["event: ready\ndata: {}\n\n"]];
    fetchMock.mockImplementation(async () => {
      connectedAt.push(Date.now() - start);
      return sseResponse(streams[connectedAt.length - 1] ?? []);
    });
    const controller = new AbortController();
    const result = followEvents(() => undefined, () => undefined, controller.signal);

    await vi.advanceTimersByTimeAsync(6_000);
    // Waits of 1 and 2 s; then, after the stream that sent ready, 1 s again (not 4) and 2 s.
    expect(connectedAt).toEqual([0, 1_000, 3_000, 4_000, 6_000]);
    controller.abort();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(await result).toBe("aborted");
  });

  it("stays disconnected after the server evicts the stream", async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(async () => sseResponse(["event: ready\ndata: {}\n\n", "event: evicted\ndata: {}\n\n"]));
    const events: string[] = [];
    const onDrop = vi.fn();
    const result = followEvents((event) => events.push(event.type), onDrop, new AbortController().signal);

    expect(await result).toBe("evicted");
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(events).toEqual(["ready", "evicted"]);
    expect(onDrop).toHaveBeenCalledTimes(1);
  });

  it("stops when the token is refused", async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(async () => problem(401, "Full authentication is required"));
    const result = followEvents(() => undefined, () => undefined, new AbortController().signal);

    expect(await result).toBe("unauthorized");
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
