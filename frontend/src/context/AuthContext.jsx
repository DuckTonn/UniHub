import { createContext, useContext, useState, useEffect, useMemo } from 'react';
import authService from '../services/authService';
import { createReadCache, ReadQueriesContext } from './readQueries';

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
    const [user, setUser] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    const account = user ? `${user.role}:${user.id}` : null;
    const readCache = useMemo(() => account ? createReadCache() : null, [account]);

    const loadCurrentUser = async () => {
        const response = await authService.getCurrentUserProfile();
        const profile = response;

        setUser({
            id: profile.userId,
            role: profile.role,
            fullName: profile.fullName,
            email: profile.email,
            studentCode: profile.studentCode,
            phoneNumber: profile.phoneNumber,
            chatId: profile.chatId,
            status: profile.status,
        });

        return profile;
    };

    useEffect(() => {
        const initializeAuth = async () => {
            const storedToken = localStorage.getItem('access_token');
            const storedRole = localStorage.getItem('role');
            const storedUserId = localStorage.getItem('user_id');

            if (storedToken && storedRole) {
                try {
                    await loadCurrentUser();
                } catch {
                    setUser({ id: storedUserId, role: storedRole });
                }
            }

            setIsLoading(false);
        };

        initializeAuth();
    }, []);

    const login = async (authData) => {
        const { accessToken, refreshToken, userId, role } = authData;
        
        localStorage.setItem('access_token', accessToken);
        localStorage.setItem('refresh_token', refreshToken);
        localStorage.setItem('role', role);
        localStorage.setItem('user_id', userId);

        try {
            await loadCurrentUser();
        } catch {
            setUser({ id: userId, role: role });
        }
    };

    const refreshProfile = async () => {
        return loadCurrentUser();
    };

    const logout = () => {
        localStorage.removeItem('access_token');
        localStorage.removeItem('refresh_token');
        localStorage.removeItem('role');
        localStorage.removeItem('user_id');
        
        setUser(null);
    };

    const value = {
        user,
        isAuthenticated: !!user,
        isLoading,
        login,
        logout,
        refreshProfile
    };

    return (
        <AuthContext.Provider value={value}>
            <ReadQueriesContext.Provider value={readCache}>
                {!isLoading && children}
            </ReadQueriesContext.Provider>
        </AuthContext.Provider>
    );
};

// eslint-disable-next-line react-refresh/only-export-components -- Keep the auth provider and its hook on the existing import path.
export const useAuth = () => {
    const context = useContext(AuthContext);
    if (!context) {
        throw new Error("useAuth must be used within an AuthProvider");
    }
    return context;
};
