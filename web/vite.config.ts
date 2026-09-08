import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// The app is published as static files (GitHub Pages), so asset URLs must be
// relative. There is deliberately no dev server proxy: the browser talks to the
// transcription providers directly, exactly as the Android app does.
export default defineConfig({
  base: './',
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./test/setup.ts'],
    include: ['test/**/*.test.ts', 'test/**/*.test.tsx'],
  },
})
