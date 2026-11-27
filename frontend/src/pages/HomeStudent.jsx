import { useAuth } from '../context/AuthContext';
import { useReadQuery } from '../context/readQueries';
import workshopService from '../services/workshopService';
import HomeStudentHeader from '../components/homeStudent/HomeStudentHeader';
import UpcomingWorkshops from '../components/homeStudent/UpcomingWorkshops';
import UserInfoCard from '../components/homeStudent/UserInfoCard';
import './studentExperience.css';

const readUpcomingWorkshops = () => workshopService.getAll(0, 3);

export default function HomeStudent() {
    const { user } = useAuth();
    const { data, loading, error, retry } = useReadQuery('workshops', [0, 3, {}], readUpcomingWorkshops);

    return (
        <div className="student-experience" aria-label="Student home">
            <HomeStudentHeader userName={user?.fullName?.trim()} />
            <UpcomingWorkshops upcomingWorkshops={data?.content ?? []} loading={loading}
                error={error && 'We couldn’t load workshops. Please try again.'} onRetry={retry} />
            <UserInfoCard user={user} />
        </div>
    );
}
