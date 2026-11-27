import { expect } from '@playwright/test';
import { student, test } from './studentApi.js';

test('retries a failed catalog read and reuses fresh results when returning to the route', async ({ page, studentApi }) => {
    let reads = 0;
    await page.route(/\/api\/v1\/workshops\?/, async (route) => {
        reads++;
        if (reads === 1) return route.fulfill({ status: 503, json: { message: 'Catalog temporarily unavailable' } });
        return route.fallback();
    });

    await page.goto('/workshops');
    await page.getByLabel('Email address', { exact: true }).fill(student.email);
    await page.getByLabel('Password', { exact: true }).fill(student.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Catalog temporarily unavailable');
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    const workshop = page.getByRole('heading', { name: 'Design Thinking Studio' });
    await expect(workshop).toBeVisible();
    expect(reads).toBe(2);

    await page.getByRole('main').getByRole('link', { name: 'My registrations' }).click();
    await expect(page.getByRole('heading', { name: 'My registrations' })).toBeVisible();
    await page.getByRole('main').getByRole('link', { name: 'Browse workshops' }).click();
    await expect(workshop).toBeVisible();
    expect(reads).toBe(2);
    expect(studentApi.logins).toEqual([student.email]);
});
