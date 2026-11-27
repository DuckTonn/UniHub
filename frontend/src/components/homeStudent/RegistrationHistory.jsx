import { Link } from 'react-router-dom';
import RegistrationCard from './RegistrationCard';

export default function RegistrationHistory({ pending, history, now, onPay, onCancel, cancellingId, onViewTicket }) {
    if (!pending.length && !history.length) {
        return (
            <div className="student-notice">
                <p>No registrations yet.</p>
                <Link className="student-text-link" to="/workshops">Find a workshop →</Link>
            </div>
        );
    }

    const groups = [
        { id: 'pending-registrations', title: 'Awaiting payment', items: pending, needsAttention: true },
        { id: 'booking-history', title: 'Booking history', items: history, needsAttention: false },
    ];

    return groups.filter(({ items }) => items.length > 0).map(({ id, title, items, needsAttention }) => (
        <section className="registration-group" key={id} aria-labelledby={id}>
            <div className="student-section-heading">
                <h2 id={id}>{title}</h2>
                <p>{items.length} {items.length === 1 ? 'booking' : 'bookings'}</p>
            </div>
            <div className="registration-grid">
                {items.map((registration) => (
                    <RegistrationCard key={registration.registrationId} registration={registration} now={now}
                        needsAttention={needsAttention} onPay={onPay} onCancel={onCancel} onViewTicket={onViewTicket}
                        cancelling={cancellingId === registration.workshopId} busy={cancellingId !== null} />
                ))}
            </div>
        </section>
    ));
}
