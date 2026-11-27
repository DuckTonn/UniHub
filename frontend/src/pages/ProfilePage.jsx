import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import telegramService from '../services/telegramService';
import './studentExperience.css';
import './studentAccount.css';

const roles = { STUDENT: 'Student', ADMIN: 'Administrator', STAFF: 'Staff' };

export default function ProfilePage() {
    const { user, refreshProfile } = useAuth();
    const [feedback, setFeedback] = useState(null);
    const [refreshing, setRefreshing] = useState(false);
    const initials = user?.fullName?.trim().split(/\s+/).filter(Boolean).slice(0, 2)
        .map((word) => Array.from(word)[0]).join('').toUpperCase() || 'U';
    const linked = Boolean(user?.chatId);

    const connectTelegram = () => {
        try {
            const link = telegramService.openTelegramDeepLink(user?.id);
            setFeedback({ text: 'Follow the bot instructions, then refresh your profile.', link });
        } catch (error) {
            setFeedback({ error: true, text: error.message || 'Unable to open Telegram.' });
        }
    };

    const refresh = async () => {
        setFeedback(null);
        setRefreshing(true);
        try {
            await refreshProfile();
            setFeedback({ text: 'Profile updated.' });
        } catch (error) {
            setFeedback({ error: true, text: error.message || 'We couldn’t refresh your profile.' });
        } finally {
            setRefreshing(false);
        }
    };

    return (
        <div className="student-experience" aria-label="Student profile">
            <header className="profile-cover">
                <span className="profile-avatar" aria-hidden="true">{initials}</span>
                <div className="profile-intro">
                    <h1>My profile</h1>
                    <p>{user?.fullName || 'UniHub account'}</p>
                    {user?.role && <span className="workshop-status">{roles[user.role] || user.role}</span>}
                </div>
            </header>

            <div className="student-section profile-panels">
                <section className="account-panel" aria-labelledby="account-details">
                    <h2 id="account-details">Account details</h2>
                    <dl className="profile-fields">
                        <div><dt>Email address</dt><dd>{user?.email || 'Not available'}</dd></div>
                        <div><dt>Student code</dt><dd>{user?.studentCode || 'Not available'}</dd></div>
                        <div><dt>Phone number</dt><dd>{user?.phoneNumber || 'Not provided'}</dd></div>
                        <div>
                            <dt>Account status</dt>
                            <dd>
                                <span className={`workshop-status${user?.status === 'ACTIVE' ? ' workshop-status--registered' : ''}`}>
                                    {user?.status === 'ACTIVE' ? 'Active' : user?.status === 'INACTIVE' ? 'Inactive' : user?.status || 'Not available'}
                                </span>
                            </dd>
                        </div>
                    </dl>
                </section>

                <section className="account-panel" aria-labelledby="telegram-title">
                    <div className="account-panel-heading">
                        <h2 id="telegram-title">Telegram</h2>
                        <span className={`workshop-status${linked ? ' workshop-status--registered' : ''}`}>
                            {linked ? 'Connected' : 'Not connected'}
                        </span>
                    </div>
                    <p className="account-panel-copy">Link Telegram to receive workshop reminders.</p>
                    <div className="profile-actions">
                        <button type="button" className="student-button student-button--primary" onClick={connectTelegram}>
                            {linked ? 'Reconnect Telegram' : 'Connect Telegram'}
                        </button>
                        <button type="button" className="student-button" onClick={refresh} disabled={refreshing} aria-busy={refreshing}>
                            {refreshing ? 'Refreshing…' : 'Refresh status'}
                        </button>
                    </div>
                    {feedback && (
                        <div className={`profile-feedback${feedback.error ? ' profile-feedback--error' : ''}`} role={feedback.error ? 'alert' : 'status'}>
                            <p>{feedback.text}</p>
                            {feedback.link && (
                                <a className="student-text-link" href={feedback.link} target="_blank" rel="noreferrer">Open Telegram again →</a>
                            )}
                        </div>
                    )}
                    <Link className="student-text-link" to="/notifications">View notifications →</Link>
                </section>
            </div>
        </div>
    );
}
