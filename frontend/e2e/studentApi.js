import { test as base } from '@playwright/test';

export const student = { email: 'student@example.test', password: 'fixture-password' };
const userProfile = { userId: 5, role: 'STUDENT', fullName: 'Alex Morgan', email: student.email,
    studentCode: 'E2E05', status: 'ACTIVE' };
const fixedTime = new Date('2026-06-15T08:00:00Z');
const times = {
    startTime: '2026-06-16T08:00:00Z',
    endTime: '2026-06-16T10:00:00Z',
    registrationStartTime: '2026-06-14T08:00:00Z',
    registrationEndTime: '2026-06-16T07:00:00Z',
};

export const test = base.extend({
    studentApi: async ({ page }, provide) => {
        const calls = { logins: [], registrations: [], payments: [], read: [] };
        const workshops = [
            { id: 1, title: 'Design Thinking Studio', price: 0, remainingSlots: 8 },
            { id: 2, title: 'Mobile App Prototyping', price: 99000, remainingSlots: 4, userRegistrationStatus: 'PENDING' },
            { id: 3, title: 'Research Methods', price: 0, remainingSlots: 0 },
            ...Array.from({ length: 10 }, (_, index) => ({
                id: index + 4, title: `Campus workshop ${index + 4}`, price: 0, remainingSlots: 6,
            })),
        ].map((workshop) => ({ ...times, ...workshop, speaker: 'Casey Nguyen', roomName: 'Innovation Lab',
            description: 'Work through a campus design challenge.' }));
        const booking = (workshop, extra) => ({
            ...times,
            registrationId: workshop.id,
            workshopId: workshop.id,
            title: workshop.title,
            price: workshop.price,
            createdAt: fixedTime.toISOString(),
            ...extra,
        });
        const registrations = [
            booking(workshops[1], { status: 'PENDING', paymentIdempotencyKey: 'e2e-hold-2' }),
        ];
        const notices = [
            { id: 7, title: 'Workshop room updated', contentHtml: '<p>Meet at the <strong>Innovation Lab</strong>.</p>',
                read: false, createdAt: fixedTime.toISOString(), channel: 'IN_APP', status: 'SUCCESS' },
            { id: 8, title: 'Welcome to UniHub', contentHtml: '<p>Browse upcoming workshops.</p>',
                read: true, createdAt: '2026-06-14T08:00:00Z', channel: 'IN_APP', status: 'SUCCESS' },
        ];
        let interruptFirstRead = true;

        await page.clock.setFixedTime(fixedTime);
        await page.route('**/api/v1/**', async (route) => {
            const request = route.request();
            const url = new URL(request.url());
            const method = request.method();
            const path = url.pathname.replace('/api/v1', '');
            const reply = (json, status = 200) => route.fulfill({ status, json });
            const paginate = (items) => {
                const pageNumber = Number(url.searchParams.get('page') || '0');
                const size = Number(url.searchParams.get('size') || '10');
                return reply({ content: items.slice(pageNumber * size, (pageNumber + 1) * size),
                    page: pageNumber, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) });
            };

            if (method === 'POST' && path === '/auth/login/browser') {
                const body = request.postDataJSON();
                calls.logins.push(body.email);
                if (body.email !== student.email || body.password !== student.password) {
                    return reply({ message: 'Invalid email or password.' }, 401);
                }
                return reply({ accessToken: 'e2e-access-token', refreshToken: 'e2e-refresh-token', userId: 5, role: 'STUDENT' });
            }
            if (method === 'GET' && path === '/auth/me') return reply(userProfile);
            if (method === 'GET' && path === '/workshops') {
                const keyword = url.searchParams.get('q')?.toLowerCase() ?? '';
                const from = url.searchParams.get('from');
                const to = url.searchParams.get('to');
                const fee = url.searchParams.get('fee');
                const seatsAvailable = url.searchParams.get('seatsAvailable') === 'true';
                return paginate(workshops.filter((item) => {
                    const date = item.startTime.slice(0, 10);
                    return (item.title.toLowerCase().includes(keyword) || item.speaker.toLowerCase().includes(keyword))
                        && (!from || date >= from) && (!to || date <= to)
                        && (fee === 'FREE' ? item.price === 0 : fee === 'PAID' ? item.price > 0 : true)
                        && (!seatsAvailable || item.remainingSlots > 0);
                }));
            }
            if (method === 'GET' && path === '/workshops/my-workshops') return paginate(registrations);
            if (method === 'GET' && path === '/notifications') {
                return paginate(url.searchParams.get('unreadOnly') === 'true' ? notices.filter((item) => !item.read) : notices);
            }
            if (method === 'GET' && path === '/notifications/unread-count') {
                return reply({ unreadCount: notices.filter((item) => !item.read).length });
            }
            if (method === 'POST' && path === '/workshops/1/register') {
                calls.registrations.push(1);
                if (workshops[0].userRegistrationStatus) return reply({ message: 'Already registered' }, 409);
                const qrCode = 'QR-E2E-FREE-DEMO';
                workshops[0].userRegistrationStatus = 'SUCCESS';
                workshops[0].remainingSlots -= 1;
                registrations.push(booking(workshops[0], { status: 'SUCCESS', qrCode }));
                return reply({ paidFlow: false, qrCode });
            }
            if (method === 'POST' && path === '/workshops/2/pay') {
                const key = request.headers()['idempotency-key'];
                calls.payments.push(key);
                if (key !== 'e2e-hold-2') return reply({ message: 'Expected the pending booking key.' }, 400);
                const qrCode = 'QR-E2E-PAID-DEMO';
                workshops[1].userRegistrationStatus = 'SUCCESS';
                Object.assign(registrations[0], { status: 'SUCCESS', qrCode });
                return reply({ success: true, qrCode });
            }
            if (method === 'PUT' && path === '/notifications/7/read') {
                calls.read.push(7);
                if (interruptFirstRead) {
                    interruptFirstRead = false;
                    return reply({ message: 'Temporary failure' }, 503);
                }
                notices[0].read = true;
                return route.fulfill({ status: 204 });
            }
            return reply({ message: `Unexpected fixture API request ${method} ${path}` }, 404);
        });

        await provide(calls);
    },
});
