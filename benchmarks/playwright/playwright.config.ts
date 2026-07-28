import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  timeout: 30_000,
  workers: 1,
  fullyParallel: false,
  retries: 0,
  use: {
    ...devices['Desktop Chrome'],
    browserName: 'chromium',
    channel: undefined,
    headless: false,
    viewport: { width: 1280, height: 720 },
    actionTimeout: 500,
    navigationTimeout: 500,
  },
});
