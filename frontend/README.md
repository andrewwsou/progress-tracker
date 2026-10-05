# ProgressArc frontend

React 19 + TypeScript + Vite. No UI library: components in `src/components`, one stylesheet
(`src/styles.css`) built on CSS custom properties, with light and dark themes that follow the
system setting.

Needs Node 22.13 or later in the 22 line, 24, or 26 and later: the `engines` range in
`package.json`, which is where the ranges Vitest, jsdom and ESLint declare overlap. Node 20, 23 and 25
are not supported. CI uses Node 24.

```
npm install
npm run dev            # http://localhost:5173, calling the API at http://localhost:8080 (VITE_API_URL to change)
npm run lint
npm test               # Vitest + jsdom; every API call is mocked, so no server is needed
npm run build
npm run preview        # the production build on http://localhost:4173
npm run generate:api   # regenerate src/api-types.ts after the API's OpenAPI contract changes
```

The API's CORS rules allow only those two origins, so both ports are strict: if 5173 or 4173 is
taken, Vite stops with an error instead of moving to a port the API would refuse.

`src/api.ts` is the only place that talks to the API, including the live-update stream
(`streamEvents`: server-sent events read with `fetch`, because `EventSource` cannot send the
`Authorization` header; `followEvents` reconnects it with backoff, and stays disconnected until the
tab is next shown if the server closed it because the user has too many open). A request that
cannot reach the server fails with a readable message rather than the browser's "Failed to fetch".
Its types come from `../backend/progresstracker/openapi.json`, so a field renamed on the server is
a compile error here, and CI fails if the generated file is out of date.

| File | What it is |
|---|---|
| `src/App.tsx` | Signed in or not: the sign-in screen or the dashboard. Follows sign-ins and sign-outs in other tabs, revokes the token on sign-out, and says so when a session expired |
| `src/components/Dashboard.tsx` | Loads habits, achievements, and the weekly summary (with Try again if that fails); runs every action |
| `src/components/HabitRow.tsx` | One habit: check-off, goal progress, streaks, edit and delete |
| `src/components/HabitForm.tsx` | Add or edit a habit |
| `src/components/SidePanels.tsx` | Overview numbers, weekly summary, achievements |
| `src/format.ts` | Labels and date formatting. Whether a habit is done today comes from the API (`completedForPeriod`), so the UI never guesses the server's date |
| `src/**/*.test.ts(x)` | The SSE parser, the reconnect loop and its backoff (1 s doubling to 30 s, back to 1 s after a stream that got going), error messages, sign-out and other tabs, the dashboard's load and refresh rules, closing the stream when the tab is hidden or the user signs out, and where keyboard focus goes |
