import axiosClient from '../api/axiosClient';

const authService = {
    studentRegister(data) {
        return axiosClient.post('/auth/register/student', data);
    },

    adminStaffRegister(data) {
        return axiosClient.post('/auth/register/admin-staff', data);
    },

    login(data) {
        return axiosClient.post('/auth/login/browser', data);
    },

    refreshToken(refreshToken) {
        return axiosClient.post('/auth/refresh', { refreshToken });
    },

    getCurrentUserProfile() {
        return axiosClient.get('/auth/me');
    },
};

export default authService;
