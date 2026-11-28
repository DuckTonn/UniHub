import { expect, test } from '@playwright/test';

const workshop = { id: 31, title: 'Campus Lab', status: 'DRAFT', price: 90000, roomName: 'Lab A',
    totalSlots: 10, remainingSlots: 8, startTime: '2030-06-16T08:00:00', endTime: '2030-06-16T10:00:00' };
const requestId = '0123456789abcdef0123456789abcdef';
const envelope = (content) => ({ content, page: 0, totalElements: content.length, totalPages: content.length ? 1 : 0 });

test('admin reviews drafts, filters attendance and opens the payment queue for the workshop', async ({ page }) => {
    let status;
    let attendanceFilter;
    let paymentFilter;
    let interrupted = false;
    await page.addInitScript(() => {
        localStorage.setItem('access_token', 'fixture-admin');
        localStorage.setItem('role', 'ADMIN');
        localStorage.setItem('user_id', '9');
    });
    await page.route('**/api/v1/**', (route) => {
        const { pathname, searchParams } = new URL(route.request().url());
        if (pathname.endsWith('/auth/me')) {
            return route.fulfill({ json: { userId: 9, role: 'ADMIN', fullName: 'Test Admin' } });
        }
        if (pathname.endsWith('/notifications/unread-count')) return route.fulfill({ json: { unreadCount: 0 } });
        if (pathname.endsWith('/admin/workshops')) {
            status = searchParams.get('status');
            return route.fulfill({ json: envelope([workshop]) });
        }
        if (pathname.endsWith('/attendances')) {
            attendanceFilter = searchParams.get('checkedIn');
            return route.fulfill({ json: envelope(attendanceFilter === 'false' ? [{ registrationId: 5, fullName: 'Test Student', checkedIn: false }] : []) });
        }
        if (pathname.endsWith('/admin/payments')) {
            paymentFilter = searchParams.get('workshopId');
            if (!interrupted) {
                interrupted = true;
                return route.fulfill({ status: 503, headers: { 'X-Request-ID': requestId }, json: { message: 'Temporary failure' } });
            }
            return route.fulfill({ json: envelope([{ paymentId: 20, registrationId: 5, workshopTitle: workshop.title,
                amount: 90000, status: 'REFUND_REQUIRED', transactionReference: 'TXN-fixture', issue: 'Confirm refund with the provider' }]) });
        }
        return route.fulfill({ status: 404, json: { message: 'Missing fixture' } });
    });

    await page.goto('/admin');
    await page.getByLabel('Workshop status').selectOption('DRAFT');
    const actions = page.getByRole('button', { name: 'Actions for Campus Lab' });
    await actions.click();
    expect(status).toBe('DRAFT');
    await page.getByRole('button', { name: 'View Attendances' }).click();
    const dialog = page.getByRole('dialog', { name: 'Attendance List' });
    await dialog.getByLabel('Check-in status').selectOption('false');
    await expect(dialog.getByText('Test Student')).toBeVisible();
    expect(attendanceFilter).toBe('false');
    await dialog.getByRole('button', { name: 'Close', exact: true }).first().click();
    await actions.click();
    await page.getByRole('row').getByRole('button', { name: 'Review payments' }).click();
    await expect(page.getByRole('alert')).toContainText(requestId);
    expect(paymentFilter).toBe('31');
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByText('Confirm refund with the provider')).toBeVisible();
});

test('staff signs in and sees accepted, duplicate and rejected check-ins without access to payments', async ({ page }) => {
    let scans = 0;
    await page.route('**/api/v1/**', async (route) => {
        const { pathname } = new URL(route.request().url());
        if (pathname.endsWith('/auth/login/browser')) {
            expect(route.request().postDataJSON()).toEqual({ email: 'staff@example.test', password: 'fixture-password' });
            return route.fulfill({ json: { accessToken: 'fixture-staff', role: 'STAFF', userId: 7 } });
        }
        if (pathname.endsWith('/auth/me')) return route.fulfill({ json: { userId: 7, role: 'STAFF' } });
        if (pathname.endsWith('/workshops')) return route.fulfill({ json: envelope([{ ...workshop, status: 'PUBLISHED' }]) });
        if (pathname.endsWith('/checkins/sync')) {
            const body = route.request().postDataJSON();
            expect(body.workshopId).toBe(31);
            expect(body.records[0].scannedAt).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d$/);
            scans++;
            return route.fulfill({ json: { successCount: Number(scans === 1), duplicateCount: Number(scans === 2), failedCount: Number(scans === 3) } });
        }
        return route.fulfill({ status: 404, json: { message: 'Missing fixture' } });
    });

    await page.goto('/staff/login');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Staff sign in', exact: true })).toHaveCount(0);
    await page.goto('/check-in');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('Email address').fill('staff@example.test');
    await page.getByLabel('Password', { exact: true }).fill('fixture-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(/\/check-in$/);
    await page.getByRole('button', { name: 'Campus Lab #31' }).click();
    const code = page.getByLabel('Ticket QR code');
    for (const message of ['Check-in recorded.', 'This ticket was already checked in.', 'Ticket rejected.']) {
        await code.fill('QR-fixture');
        await code.press('Enter');
        await expect(page.getByRole('status')).toContainText(message);
        await expect(code).toHaveValue('');
    }
    await page.goto('/admin/payments');
    await expect(page.getByRole('heading', { name: 'Access Denied' })).toBeVisible();
});
