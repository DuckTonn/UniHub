import { lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { Toaster } from 'react-hot-toast';
import Layout from './components/layouts/Layout';
import AuthLayout from './components/layouts/AuthLayout';
import ProtectedRoute from './routes/ProtectedRoute';
import GuestRoute from './routes/GuestRoute';

// Pages
import LoginPage from './pages/LoginPage';
import UnauthorizedPage from './pages/UnauthorizedPage';
import NotFoundPage from './pages/NotFoundPage';
import HomeStudent from './pages/HomeStudent';
import WorkshopListPage from './pages/WorkshopListPage';
import RegistrationHistoryPage from './pages/RegistrationHistoryPage';
import NotificationsPage from './pages/NotificationsPage';

const RegisterPage = lazy(() => import('./pages/RegisterPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const StatisticsPage = lazy(() => import('./pages/StatisticsPage'));
const RoomsPage = lazy(() => import('./pages/RoomsPage'));
const AdminPaymentsPage = lazy(() => import('./pages/AdminPaymentsPage'));
const StaffCheckinPage = lazy(() => import('./pages/StaffCheckinPage'));

// Auto-redirect ADMIN to /admin when visiting /
const HomeRedirect = () => {
    const { user } = useAuth();
    if (user?.role === 'ADMIN') return <Navigate to="/admin" replace />;
    if (user?.role === 'STAFF') return <Navigate to="/check-in" replace />;
    return <HomeStudent />;
};

const App = () => {
    return (
        <AuthProvider>
            <Toaster
                position="top-right"
                reverseOrder={false}
                toastOptions={{
                    className: 'text-sm font-medium',
                    style: {
                        borderRadius: '12px',
                        boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)',
                        padding: '12px 16px',
                        maxWidth: '400px',
                    },
                    success: {
                        style: {
                            background: '#f0fdf4',
                            color: '#15803d',
                            border: '1px solid #dcfce7',
                        },
                        iconTheme: {
                            primary: '#16a34a',
                            secondary: '#f0fdf4',
                        },
                    },
                    error: {
                        style: {
                            background: '#fef2f2',
                            color: '#b91c1c',
                            border: '1px solid #fee2e2',
                        },
                        iconTheme: {
                            primary: '#dc2626',
                            secondary: '#fef2f2',
                        },
                    },
                }}
            />
            <BrowserRouter>
                <Routes>
                    <Route element={<GuestRoute />}>
                        <Route element={<AuthLayout />}>
                            <Route path="/login" element={<LoginPage />} />
                            <Route path="/staff/login" element={<Navigate to="/login" replace />} />
                            <Route path="/register" element={<RegisterPage />} />
                        </Route>
                    </Route>
                    <Route element={<Layout />}>
                        {/* Always accessible public routes */}
                        <Route path="/unauthorized" element={<UnauthorizedPage />} />

                        {/* Authenticated routes */}
                        <Route element={<ProtectedRoute />}>

                            <Route path="/" element={<HomeRedirect />} />

                            {/* Student-only routes */}
                            <Route element={<ProtectedRoute allowedRoles={['STUDENT', 'ADMIN']} />}>
                                <Route path="/profile" element={<ProfilePage />} />
                                <Route path="/workshops" element={<WorkshopListPage />} />
                                <Route path="/registration-history" element={<RegistrationHistoryPage />} />
                                <Route path="/notifications" element={<NotificationsPage />} />
                            </Route>

                            {/* Admin-only routes */}
                            <Route element={<ProtectedRoute allowedRoles={['ADMIN']} />}>
                                <Route path="/admin" element={<Dashboard />} />
                                <Route path="/admin/statistics" element={<StatisticsPage />} />
                                <Route path="/admin/rooms" element={<RoomsPage />} />
                                <Route path="/admin/payments" element={<AdminPaymentsPage />} />
                            </Route>

                            <Route element={<ProtectedRoute allowedRoles={['STAFF']} />}>
                                <Route path="/check-in" element={<StaffCheckinPage />} />
                            </Route>

                            {/* Catch-all route for undefined URLs (404 Not Found) */}
                            <Route path="*" element={<NotFoundPage />} />
                        </Route>
                    </Route>
                </Routes>
            </BrowserRouter>
        </AuthProvider>
    );
};

export default App;
