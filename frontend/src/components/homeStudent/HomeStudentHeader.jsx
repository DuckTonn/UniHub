import { Link } from 'react-router-dom';

export default function HomeStudentHeader({ userName }) {
    return (
        <header className="student-hero">
            <div className="student-hero-copy">
                <h1>{userName ? `Welcome, ${userName.split(' ')[0]}` : 'Welcome to UniHub'}</h1>
                <p>Find your next campus workshop and keep track of the sessions you’ve booked.</p>
                <div className="student-actions">
                    <Link className="student-button student-button--primary" to="/workshops">
                        Explore workshops <span aria-hidden="true">→</span>
                    </Link>
                    <Link className="student-button student-button--on-cover" to="/registration-history">
                        My registrations
                    </Link>
                </div>
            </div>
        </header>
    );
}
