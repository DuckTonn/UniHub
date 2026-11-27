import { env } from 'node:process';
import { defineConfig } from '@playwright/test';

const origin = 'http://127.0.0.1:4178';

export default defineConfig({
    testDir: './e2e',
    fullyParallel: true,
    forbidOnly: Boolean(env.CI),
    reporter: env.CI ? [['line'], ['html', { open: 'never' }]] : 'list',
    use: {
        browserName: 'chromium',
        baseURL: origin,
        serviceWorkers: 'block',
        timezoneId: 'Asia/Ho_Chi_Minh',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
    },
    projects: [
        { name: 'compact-320', use: { viewport: { width: 320, height: 720 }, hasTouch: true } },
        { name: 'mobile-390', use: { viewport: { width: 390, height: 844 }, hasTouch: true } },
        { name: 'desktop-1280', use: { viewport: { width: 1280, height: 900 } } },
    ],
    webServer: {
        command: 'npm run build && npm run preview -- --host 127.0.0.1 --port 4178 --strictPort',
        url: origin,
        env: { VITE_API_BASE_URL: '/api/v1' },
        timeout: 120_000,
        reuseExistingServer: false,
    },
});
