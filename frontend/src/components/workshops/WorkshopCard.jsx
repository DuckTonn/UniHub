import { Link } from 'react-router-dom';
import { formatDateTime, formatDescription, formatPrice, formatWorkshopDate, formatWorkshopMonth, isRegistrationClosed, isRegistrationStarted } from '../../utils/workshopDisplay';

const palettes = ['violet', 'rose', 'blue'];

export default function WorkshopCard({ workshop, index, onOpen }) {
    const date = formatWorkshopDate(workshop.startTime);
    const registration = workshop.userRegistrationStatus;
    const status = registration === 'PENDING' ? 'Payment pending' : registration ? 'Registered' : null;
    const availability = isRegistrationClosed(workshop) ? 'Registration closed'
        : workshop.remainingSlots === 0 ? 'Full'
            : !isRegistrationStarted(workshop) ? 'Registration not started'
                : workshop.remainingSlots == null ? null : `${workshop.remainingSlots} seats left`;
    const action = <>View details <span aria-hidden="true">↗</span></>;
    const actionLabel = `View details for ${workshop.title}`;

    return (
        <article className="workshop-card">
            <div className={`workshop-card-art workshop-card-art--${palettes[index % palettes.length]}`}>
                {date && (
                    <span className="workshop-date-stamp" aria-hidden="true">
                        <span>{formatWorkshopMonth(date)}</span>
                        <strong>{date.getDate()}</strong>
                    </span>
                )}
                <span className="workshop-price">{formatPrice(workshop.price)}</span>
            </div>
            <div className="workshop-card-body">
                <p className="workshop-meta workshop-card-time">{formatDateTime(workshop.startTime)}</p>
                <h3>{workshop.title}</h3>
                <p className="workshop-meta">{[workshop.speaker, workshop.roomName || 'Room TBA'].filter(Boolean).join(' · ')}</p>
                <p className="workshop-card-description">{formatDescription(workshop.description)}</p>
                <div className="workshop-card-footer">
                    {(status || availability) && (
                        <span className={`workshop-status${status ? (registration === 'PENDING' ? ' workshop-status--pending' : ' workshop-status--registered') : ''}`}>
                            {status || availability}
                        </span>
                    )}
                    {onOpen ? (
                        <button type="button" className="workshop-card-action" aria-label={actionLabel} onClick={() => onOpen(workshop)}>{action}</button>
                    ) : (
                        <Link to="/workshops" state={{ workshopId: workshop.id }} className="workshop-card-action" aria-label={actionLabel}>{action}</Link>
                    )}
                </div>
            </div>
        </article>
    );
}
