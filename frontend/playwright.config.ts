import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  use: { baseURL: 'http://127.0.0.1:5173', browserName: 'chromium' },
  webServer: [
    { command: 'java -jar ../backend/target/incident-replay-0.1.0-SNAPSHOT.jar --server.port=8080', url: 'http://127.0.0.1:8080/api/health', reuseExistingServer: false, timeout: 60000, env: { IR_AI_ENABLED: 'false', IR_AI_API_KEY: '', IR_AI_MODEL: '' } },
    { command: 'npm run dev', url: 'http://127.0.0.1:5173', reuseExistingServer: false, timeout: 60000 },
  ],
});
