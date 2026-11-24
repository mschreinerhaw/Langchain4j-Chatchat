import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',
  use: { baseURL: process.env.TEST_ADMIN ?? 'http://127.0.0.1:18081',
    launchOptions: { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH },
    screenshot: 'only-on-failure' },
});
