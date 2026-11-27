import axiosClient from '../api/axiosClient';

const NOTIFICATION_API = '/notifications';
const DEFAULT_PAGE_SIZE = 10;

const notificationService = {
    getMyNotifications: async (page = 0, size = DEFAULT_PAGE_SIZE, unreadOnly = false) => {
        const response = await axiosClient.get(NOTIFICATION_API, {
            params: { page, size, ...(unreadOnly ? { unreadOnly: true } : {}) },
        });
        return response;
    },

    getUnreadCount: async () => {
        const { unreadCount } = await axiosClient.get(`${NOTIFICATION_API}/unread-count`);
        return unreadCount;
    },

    markRead: async (id) => {
        await axiosClient.put(`${NOTIFICATION_API}/${id}/read`);
    },
};

export default notificationService;
