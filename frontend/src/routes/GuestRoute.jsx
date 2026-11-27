import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

const GuestRoute = () => {
    const { isAuthenticated, user } = useAuth();
    const { state } = useLocation();

    if (isAuthenticated) {
        const from = state?.from;
        const destination = user?.role === 'ADMIN' ? '/admin' : from || '/';
        return <Navigate to={destination} replace />;
    }

    return <Outlet />;
};

export default GuestRoute;
