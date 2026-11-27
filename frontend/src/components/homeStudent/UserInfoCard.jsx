import { Link } from 'react-router-dom';

export default function UserInfoCard({ user }) {
    return (
        <section className="student-account" aria-labelledby="student-account-title">
            <span className="student-account-avatar" aria-hidden="true">{user?.fullName?.trim().charAt(0).toUpperCase() || 'U'}</span>
            <div>
                <h2 id="student-account-title">{user?.fullName || 'Your profile'}</h2>
                {(user?.email || user?.studentCode) && (
                    <p>{[user?.email, user?.studentCode].filter(Boolean).join(' · ')}</p>
                )}
            </div>
            <Link className="student-button" to="/profile">View profile <span aria-hidden="true">→</span></Link>
        </section>
    );
}
