# ProgressArc frontend

React 19 + TypeScript + Vite. No UI library: components in `src/components`, one stylesheet
(`src/styles.css`) built on CSS custom properties, with light and dark themes that follow the
system setting.

```
npm install
npm run dev            # http://localhost:5173, calling the API at http://localhost:8080 (VITE_API_URL to change)
npm run lint
npm run build
npm run generate:api   # regenerate src/api-types.ts after the API's OpenAPI contract changes
```

`src/api.ts` is the only place that talks to the API, including the live-update stream
(`streamEvents`: server-sent events read with `fetch`, because `EventSource` cannot send the
`Authorization` header). Its types come from
`../backend/progresstracker/openapi.json`, so a field renamed on the server is a compile error
here, and CI fails if the generated file is out of date.

| File | What it is |
|---|---|
| `src/App.tsx` | Signed in or not: the sign-in screen or the dashboard |
| `src/components/Dashboard.tsx` | Loads habits, achievements, and the weekly summary; runs every action |
| `src/components/HabitRow.tsx` | One habit: check-off, goal progress, streaks, edit and delete |
| `src/components/HabitForm.tsx` | Add or edit a habit |
| `src/components/SidePanels.tsx` | Overview numbers, weekly summary, achievements |
| `src/format.ts` | Labels and date formatting. Whether a habit is done today comes from the API (`completedForPeriod`), so the UI never guesses the server's date |
