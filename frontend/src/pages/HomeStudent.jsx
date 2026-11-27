import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import workshopService from '../services/workshopService';
import HomeStudentHeader from '../components/homeStudent/HomeStudentHeader';
import UpcomingWorkshops from '../components/homeStudent/UpcomingWorkshops';
import UserInfoCard from '../components/homeStudent/UserInfoCard';
import './studentExperience.css';

export default function HomeStudent() {
    const { user } = useAuth();
    const [upcomingWorkshops, setUpcomingWorkshops] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    const fetchData = useCallback(() => workshopService.getAll(0, 3)
        .then((result) => setUpcomingWorkshops(result.content || []))
        .catch(() => setError('We couldn’t load workshops. Please try again.'))
        .finally(() => setLoading(false)), []);

    useEffect(() => {
        fetchData();
    }, [fetchData]);

    const retry = () => {
        setLoading(true);
        setError('');
        fetchData();
    };

    return (
        <div className="student-experience" aria-label="Student home">
            <HomeStudentHeader userName={user?.fullName?.trim()} />
            <UpcomingWorkshops upcomingWorkshops={upcomingWorkshops} loading={loading} error={error} onRetry={retry} />
            <UserInfoCard user={user} />
        </div>
    );
}
