import { expect } from '@playwright/test';
import { student, test } from './studentApi.js';

async function fillLogin(page, password = student.password) {
    await page.getByLabel('Email address', { exact: true }).fill(student.email);
    await page.getByLabel('Password', { exact: true }).fill(password);
}

async function signInTo(page, path) {
    await page.goto(path);
    await fillLogin(page);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
}

test('rejects the wrong password and returns to the requested workshop page', async ({ page, studentApi }) => {
    await page.goto('/workshops?page=1#catalog');
    await fillLogin(page, 'wrong-password');
    const password = page.getByLabel('Password', { exact: true });
    await page.getByRole('button', { name: 'Show password', exact: true }).click();
    await expect(password).toHaveAttribute('type', 'text');
    await page.getByRole('button', { name: 'Hide password', exact: true }).click();
    await expect(password).toHaveAttribute('type', 'password');

    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText('Invalid email or password.');
    await expect(page.getByLabel('Email address')).toHaveValue(student.email);
    await password.fill(student.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(/\/workshops\?page=1#catalog$/);
    await expect(page.getByRole('heading', { name: 'Workshop catalog' })).toBeVisible();
    expect(studentApi.logins).toEqual([student.email, student.email]);
});

test('registers for a free workshop and reopens its check-in QR', async ({ page, studentApi }) => {
    await signInTo(page, '/workshops');
    await page.getByRole('button', { name: 'View details for Design Thinking Studio' }).click();
    const detail = page.getByRole('dialog', { name: 'Design Thinking Studio' });
    await expect(detail.getByText('Innovation Lab')).toBeVisible();
    await detail.getByRole('button', { name: 'Register', exact: true }).click();
    const confirmation = page.getByRole('dialog', { name: 'Registration confirmed' });
    await expect(confirmation.getByRole('img', { name: 'Workshop check-in QR code' })).toBeVisible();
    await confirmation.getByRole('button', { name: 'Done' }).click();
    expect(studentApi.registrations).toEqual([1]);

    await page.getByRole('main').getByRole('link', { name: 'My registrations' }).click();
    const opener = page.getByRole('button', { name: 'Show QR code for Design Thinking Studio' });
    await opener.click();
    const ticket = page.getByRole('dialog', { name: 'Check-in ticket' });
    const qr = ticket.getByRole('img', { name: 'Workshop check-in QR code' });
    await expect(qr).toBeVisible();
    expect(await qr.evaluate((element) => element.tagName.toLowerCase())).toBe('svg');
    await ticket.getByRole('button', { name: 'Done' }).press('Escape');
    await expect(ticket).toBeHidden();
    await expect(opener).toBeFocused();
});

test('completes a pending demo payment with its original booking key', async ({ page, studentApi }) => {
    await signInTo(page, '/registration-history');
    const pending = page.getByRole('region', { name: 'Awaiting payment' });
    await expect(pending.getByText('Payment pending', { exact: true })).toBeVisible();
    await pending.getByRole('button', { name: 'Pay now', exact: true }).click();
    await page.locator('form').getByRole('button', { name: 'Pay Now', exact: true }).click();
    const ticket = page.getByRole('dialog', { name: 'Check-in ticket' });
    await expect(ticket.getByRole('img', { name: 'Workshop check-in QR code' })).toBeVisible();
    expect(studentApi.payments).toEqual(['e2e-hold-2']);
    await ticket.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByRole('region', { name: 'Booking history' }).getByText('Confirmed', { exact: true })).toBeVisible();
    await expect(pending).toBeHidden();
});

test('updates unread status only after a successful save and keeps it after reload', async ({ page, studentApi }) => {
    await signInTo(page, '/notifications');
    await expect(page.getByText('1 unread on this page', { exact: true })).toBeVisible();
    expect(studentApi.read).toEqual([]);
    const notice = page.getByRole('button', { name: /Workshop room updated/ });
    await notice.press('Enter');
    await expect(notice).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByText('Innovation Lab', { exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('Couldn’t save the read status.');
    await expect(page.getByText('1 unread on this page', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await expect(page.getByText('0 unread on this page', { exact: true })).toBeVisible();
    expect(studentApi.read).toEqual([7, 7]);
    await page.reload();
    await expect(page.getByText('0 unread on this page', { exact: true })).toBeVisible();
    await expect(notice).toHaveAttribute('aria-expanded', 'false');
});

test('keeps student screens within the viewport with keyboard focus and reduced motion', async ({ page, studentApi }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    expect(await page.locator('.auth-content').evaluate((element) => getComputedStyle(element).animationName)).toBe('none');
    await page.getByRole('link', { name: 'UniHub sign in' }).press('Tab');
    const email = page.getByLabel('Email address', { exact: true });
    await expect(email).toBeFocused();
    await expect(email).toHaveCSS('outline-style', 'solid');

    await fillLogin(page);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Welcome, Alex' })).toBeVisible();
    expect(studentApi.logins).toEqual([student.email]);
    for (const [path, heading, readyText] of [
        ['/', 'Welcome, Alex', 'Design Thinking Studio'],
        ['/workshops', 'Explore workshops', 'Design Thinking Studio'],
        ['/registration-history', 'My registrations', 'Payment pending'],
        ['/profile', 'My profile', student.email],
        ['/notifications', 'Notifications', 'Workshop room updated'],
    ]) {
        await page.goto(path);
        await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
        await expect(page.getByRole('main').getByText(readyText, { exact: true })).toBeVisible();
        await expect.poll(() => page.evaluate(() =>
            document.documentElement.scrollWidth <= document.documentElement.clientWidth
        ), { message: `${path} must not overflow horizontally` }).toBe(true);

        if (path === '/workshops') {
            const details = page.getByRole('button', { name: 'View details for Design Thinking Studio' });
            await details.press('Enter');
            const dialog = page.getByRole('dialog', { name: 'Design Thinking Studio' });
            await expect(dialog).toHaveCSS('animation-name', 'none');
            await dialog.getByRole('button', { name: 'Close' }).press('Escape');
            await expect(details).toBeFocused();
        }
    }
    await page.getByRole('button', { name: /Workshop room updated/ }).press('Enter');
    const inboxDetail = page.locator('.inbox-detail:not([hidden])');
    await expect(inboxDetail.getByText('Innovation Lab', { exact: true })).toBeVisible();
    await expect(inboxDetail).toHaveCSS('animation-name', 'none');
});

test('applies catalog filters through the URL and resets pagination', async ({ page, studentApi }) => {
    await signInTo(page, '/workshops');
    expect(studentApi.logins).toEqual([student.email]);
    await page.getByTitle('Next page').click();
    await expect(page).toHaveURL(/page=1$/);
    await expect(page.getByRole('heading', { name: 'Campus workshop 13' })).toBeVisible();

    await page.getByRole('searchbox', { name: 'Search by name or speaker' }).fill('Casey Nguyen');
    await page.getByLabel('From date').fill('2026-06-16');
    await page.getByLabel('To date').fill('2026-06-16');
    await page.getByRole('combobox', { name: 'Fee', exact: true }).selectOption('PAID');
    await page.getByRole('checkbox', { name: 'Only with seats available' }).check();
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await expect(page.getByRole('heading', { name: 'Mobile App Prototyping' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Design Thinking Studio' })).toHaveCount(0);
    const params = new URL(page.url()).searchParams;
    expect(Object.fromEntries(params)).toEqual({
        q: 'Casey Nguyen', from: '2026-06-16', to: '2026-06-16', fee: 'PAID', seatsAvailable: 'true',
    });
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Mobile App Prototyping' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Fee', exact: true })).toHaveValue('PAID');
    await page.goBack();
    await expect(page).toHaveURL(/page=1$/);
    await expect(page.getByRole('heading', { name: 'Campus workshop 13' })).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await expect(page).toHaveURL(/\/workshops$/);
});

test('filters unread notices on the server and refreshes the navigation badge', async ({ page, studentApi }) => {
    await signInTo(page, '/notifications');
    await expect(page.getByRole('link', { name: 'Notifications, 1 unread' })).toBeVisible();
    await page.getByRole('button', { name: 'Unread only' }).click();
    await expect(page).toHaveURL(/\?view=unread$/);
    const notice = page.getByRole('button', { name: /Workshop room updated/ });
    await notice.click();
    await expect(page.getByRole('button', { name: /Welcome to UniHub/ })).toHaveCount(0);
    await page.getByRole('button', { name: 'Try again', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Notifications, 0 unread' })).toBeVisible();
    await expect(page.getByText('Innovation Lab', { exact: true })).toBeVisible();
    await notice.click();
    await expect(page.getByText('No unread notifications.', { exact: true })).toBeVisible();
    expect(studentApi.read).toEqual([7, 7]);
    await page.getByRole('button', { name: 'All notifications' }).click();
    await expect(notice.getByText('Read', { exact: true })).toBeVisible();
});
