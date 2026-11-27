const workshops = Array.from({ length: 8 }, (_, index) => ({
    id: index + 1,
    title: ['Design Thinking Studio', 'Mobile App Prototyping', 'Research Methods'][index % 3],
    speaker: 'Casey Nguyen',
    description: 'Work through a campus design challenge.',
    startTime: '2030-06-16T08:00:00Z',
    endTime: '2030-06-16T10:00:00Z',
    registrationStartTime: '2026-06-14T08:00:00Z',
    registrationEndTime: '2030-06-15T07:00:00Z',
    roomName: 'Innovation Lab',
    status: 'PUBLISHED',
    price: 0,
    totalSlots: 20,
    remainingSlots: 8,
}));

const notifications = [{
    id: 7, title: 'Workshop room updated', contentHtml: '<p>Meet at the <strong>Innovation Lab</strong>.</p>',
    read: false, createdAt: '2026-06-15T08:00:00Z', channel: 'IN_APP', status: 'SUCCESS',
}];

export function performanceFixtures({ requests, failures }) {
    return {
        name: 'performance-fixtures',
        configurePreviewServer(server) {
            server.middlewares.use((request, response, next) => {
                const { pathname, searchParams } = new URL(request.url, 'http://127.0.0.1');
                if (pathname === '/__performance/session') {
                    response.setHeader('Content-Type', 'text/html');
                    return response.end('<!doctype html><title>Local performance fixture</title>');
                }
                if (!pathname.startsWith('/api/v1/')) return next();

                const path = pathname.slice('/api/v1'.length);
                requests.add(path);
                const role = request.headers.authorization === 'Bearer performance-ADMIN' ? 'ADMIN' : 'STUDENT';
                const page = (items) => {
                    const size = Number(searchParams.get('size') || 12);
                    return { content: items.slice(0, size), page: 0, size, totalElements: items.length,
                        totalPages: Math.ceil(items.length / size) };
                };
                const data = {
                    '/auth/me': { userId: role === 'ADMIN' ? 3 : 5, role, fullName: 'Alex Morgan',
                        email: 'performance@example.test', studentCode: 'PERF05', status: 'ACTIVE' },
                    '/workshops': page(workshops),
                    '/workshops/my-workshops': page([{ ...workshops[0], registrationId: 1, workshopId: 1,
                        status: 'SUCCESS', qrCode: 'PERFORMANCE-FIXTURE', createdAt: '2026-06-15T08:00:00Z' }]),
                    '/notifications': page(notifications),
                    '/notifications/unread-count': { unreadCount: 1 },
                    '/admin/workshops': page(workshops),
                    '/admin/stats': { totalWorkshops: 8, totalRegistrations: 12, totalRevenue: 0,
                        registrationsByHour: [{ hour: 8, count: 12 }], paymentSuccessRate: 100 },
                    '/admin/rooms': [{ id: 1, name: 'Innovation Lab', capacity: 20 }],
                };

                response.setHeader('Content-Type', 'application/json');
                if (request.method !== 'GET' || !Object.hasOwn(data, path)) {
                    failures.push(`${request.method} ${path}`);
                    response.statusCode = 404;
                    return response.end(JSON.stringify({ message: 'Missing performance fixture' }));
                }
                response.end(JSON.stringify(data[path]));
            });
        },
    };
}
