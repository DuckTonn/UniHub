import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { gzipSync } from 'node:zlib';
import { chromium } from '@playwright/test';
import { launch } from 'chrome-launcher';
import lighthouse from 'lighthouse';
import { build, preview } from 'vite';
import { performanceFixtures } from './fixtures.mjs';

const { env, argv } = process;
const routes = [
    { name: 'login', path: '/login', group: 'auth' },
    { name: 'home', path: '/', role: 'STUDENT', api: '/workshops', group: 'student' },
    { name: 'workshops', path: '/workshops', role: 'STUDENT', api: '/workshops', group: 'student' },
    { name: 'registrations', path: '/registration-history', role: 'STUDENT', api: '/workshops/my-workshops', group: 'student' },
    { name: 'notifications', path: '/notifications', role: 'STUDENT', api: '/notifications', group: 'student' },
    { name: 'dashboard', path: '/admin', role: 'ADMIN', api: '/admin/workshops', group: 'admin' },
    { name: 'statistics', path: '/admin/statistics', role: 'ADMIN', api: '/admin/stats', group: 'analytics' },
];

const baselineDist = argv[2];
const dist = resolve(baselineDist ?? 'dist');
const output = resolve('performance-results', baselineDist ? 'baseline' : 'current');
const numberOfRuns = 3;
const requests = new Set();
const failures = [];
const measurements = [];
const violations = [];
const budget = baselineDist ? null : JSON.parse(await readFile(new URL('./budgets.json', import.meta.url), 'utf8'));
const median = (values) => values.toSorted((a, b) => a - b)[Math.floor(values.length / 2)];

if (!baselineDist) {
    env.VITE_API_BASE_URL = '/api/v1';
    await build({ envDir: false });
}
await mkdir(output, { recursive: true });
const server = await preview({
    configFile: false,
    envDir: false,
    build: { outDir: dist },
    preview: { host: '127.0.0.1', port: 0, strictPort: true },
    plugins: [performanceFixtures({ requests, failures })],
});
const origin = `http://127.0.0.1:${server.httpServer.address().port}`;

async function measure(route, run) {
    const chrome = await launch({
        chromePath: chromium.executablePath(),
        chromeFlags: ['--headless', '--no-sandbox', '--disable-dev-shm-usage'],
    });
    try {
        requests.clear();
        failures.length = 0;
        if (route.role) {
            const browser = await chromium.connectOverCDP(`http://127.0.0.1:${chrome.port}`);
            const page = await browser.contexts()[0].newPage();
            await page.goto(`${origin}/__performance/session`);
            await page.evaluate((role) => {
                localStorage.setItem('access_token', `performance-${role}`);
                localStorage.setItem('role', role);
                localStorage.setItem('user_id', role === 'ADMIN' ? '3' : '5');
            }, route.role);
            await page.goto('about:blank');
        }

        const url = `${origin}${route.path}`;
        const { lhr, report } = await lighthouse(url, {
            port: chrome.port,
            logLevel: 'error',
            onlyCategories: ['performance'],
            output: 'html',
            formFactor: 'mobile',
            throttlingMethod: 'simulate',
            disableStorageReset: true,
        });
        await writeFile(join(output, `${route.name}-${run}.html`), report);
        if (lhr.runtimeError || lhr.finalDisplayedUrl !== url || failures.length
            || (route.api && !requests.has(route.api))) {
            throw new Error(`${route.path} was not measured: ${lhr.runtimeError?.message ?? lhr.finalDisplayedUrl}; requests: ${[...requests]}; missing fixtures: ${failures}`);
        }
        const scripts = new Set(lhr.audits['network-requests'].details.items.map(({ url }) => new URL(url))
            .filter((asset) => asset.origin === origin && asset.pathname.startsWith('/assets/') && asset.pathname.endsWith('.js'))
            .map((asset) => asset.pathname.slice(1)));
        if (!scripts.size) throw new Error(`No JavaScript recorded for ${route.path}`);
        const jsBytes = (await Promise.all([...scripts].map(async (asset) =>
            gzipSync(await readFile(join(dist, asset))).byteLength))).reduce((sum, size) => sum + size, 0);
        const lcp = lhr.audits['largest-contentful-paint'].numericValue;
        const cls = lhr.audits['cumulative-layout-shift'].numericValue;
        if (!Number.isFinite(lcp) || !Number.isFinite(cls)) throw new Error(`Missing LCP or CLS for ${route.path}`);
        return { jsBytes, lcp, cls };
    } finally {
        await chrome.kill();
    }
}

try {
    for (const route of routes) {
        const samples = [];
        for (let run = 1; run <= numberOfRuns; run++) samples.push(await measure(route, run));
        const jsBytes = Math.max(...samples.map((sample) => sample.jsBytes));
        const lcp = Math.round(median(samples.map((sample) => sample.lcp)));
        const cls = Math.round(median(samples.map((sample) => sample.cls)) * 1000) / 1000;
        measurements.push({ route: route.path, group: route.group, jsBytes, lcp, cls, samples });
        const limit = budget?.[route.group];
        if (budget && !limit) throw new Error(`Missing budget for ${route.group}`);
        if (limit) {
            for (const [metric, value] of Object.entries({ jsBytes, lcp, cls })) {
                if (value > limit[metric]) violations.push(`${route.path} ${metric} ${value} > ${limit[metric]}`);
            }
        }
        console.log(`${route.path} — ${(jsBytes / 1024).toFixed(1)} KiB gzip, LCP ${lcp} ms, CLS ${cls}`);
    }
} finally {
    await new Promise((done) => server.httpServer.close(done));
    await writeFile(join(output, 'summary.json'), JSON.stringify({ measurements, violations }, null, 2));
    if (env.GITHUB_STEP_SUMMARY) {
        const summary = [
            '## Frontend mobile Lighthouse',
            '',
            '| Route | JS gzip | Median LCP | Median CLS |',
            '| --- | ---: | ---: | ---: |',
            ...measurements.map(({ route, jsBytes, lcp, cls }) =>
                `| \`${route}\` | ${(jsBytes / 1024).toFixed(1)} KiB | ${lcp} ms | ${cls} |`),
            ...violations.map((violation) => `\n- ${violation}`),
            '',
        ].join('\n');
        await mkdir(dirname(env.GITHUB_STEP_SUMMARY), { recursive: true });
        await writeFile(env.GITHUB_STEP_SUMMARY, summary, { flag: 'a' });
    }
}
if (violations.length) {
    console.error(violations.join('\n'));
    process.exitCode = 1;
}
