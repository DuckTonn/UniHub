import { Link } from 'react-router-dom';
import WorkshopCard from '../workshops/WorkshopCard';

export default function UpcomingWorkshops({ upcomingWorkshops, loading, error, onRetry }) {
    return (
        <section className="student-section" aria-labelledby="upcoming-title" aria-busy={loading}>
            <div className="student-section-heading">
                <h2 id="upcoming-title">Upcoming workshops</h2>
                <Link className="student-text-link" to="/workshops">See all <span aria-hidden="true">↗</span></Link>
            </div>
            {loading ? (
                <p className="student-notice" role="status">Loading workshops…</p>
            ) : error ? (
                <div className="student-notice student-notice--error" role="alert">
                    <p>{error}</p>
                    <button className="student-text-link" type="button" onClick={onRetry}>Try again</button>
                </div>
            ) : upcomingWorkshops.length ? (
                <div className="workshop-grid">
                    {upcomingWorkshops.map((workshop, index) => (
                        <WorkshopCard key={workshop.id} workshop={workshop} index={index} />
                    ))}
                </div>
            ) : (
                <p className="student-notice">No upcoming workshops yet.</p>
            )}
        </section>
    );
}
