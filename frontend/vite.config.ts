/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // The API's CORS rules allow exactly these two origins, so fail if the port is taken rather
  // than move to one the API would refuse.
  server: { port: 5173, strictPort: true },
  preview: { port: 4173, strictPort: true },
  test: {
    environment: 'jsdom',
    // Tests get empty CSS by default. styles.test.ts checks the real stylesheet.
    css: { include: [/styles\.css/] },
  },
})
