import { formatDateTime, formatPrice } from '../../utils/workshopDisplay';
import { isHoldExpired, isWorkshopEnded, registrationStatus } from '../../utils/registrationDisplay';

export default function RegistrationCard({
    registration, now, needsAttention = false, onViewTicket, onPay, onCancel, cancelling, busy,
}) {
    const ended = isWorkshopEnded(registration, now);
    const expired = isHoldExpired(registration, now);
    const pending = registration.status === 'PENDING';
    const confirmed = registration.status === 'SUCCESS';
    const status = expired && !ended ? { label: 'Seat hold expired', tone: 'neutral' } : registrationStatus(registration.status);
    const canManage = needsAttention && pending && !expired;

    return (
        <article className="registration-card" data-tone={status.tone}>
            <div className="registration-card-heading">
                <h3>{registration.title}</h3>
                <span className={`workshop-status workshop-status--${status.tone}`}>{status.label}</span>
            </div>
            <dl className="registration-facts">
                <div><dt>Workshop starts</dt><dd>{formatDateTime(registration.startTime)}</dd></div>
                <div><dt>Workshop ends</dt><dd>{formatDateTime(registration.endTime)}</dd></div>
                <div><dt>Price</dt><dd>{formatPrice(registration.price)}</dd></div>
                <div><dt>Registered on</dt><dd>{formatDateTime(registration.createdAt)}</dd></div>
            </dl>
            {confirmed && registration.qrCode && (
                <div className="registration-ticket-row">
                    <div>
                        <p>Check-in ticket</p>
                        <span>{ended ? 'This workshop has ended.' : 'Show your QR code at the entrance.'}</span>
                    </div>
                    <button type="button" className="student-button student-button--primary" onClick={() => onViewTicket(registration)}
                        aria-label={`Show QR code for ${registration.title}`}>Show QR code</button>
                </div>
            )}
            {confirmed && !registration.qrCode && <p className="registration-note">QR code is being prepared. Check back later.</p>}
            {pending && ended && <p className="registration-note">This workshop has ended. Verify the payment status before trying again.</p>}
            {pending && !ended && expired && <p className="registration-note">The payment window has passed. Unpaid seats are released automatically.</p>}
            {canManage && (
                <>
                    {!registration.paymentIdempotencyKey && <p className="registration-note">Payment is temporarily unavailable for this booking.</p>}
                    <div className="registration-actions">
                        <button type="button" className="student-button student-button--primary"
                            disabled={!registration.paymentIdempotencyKey || busy} onClick={() => onPay(registration)}>Pay now</button>
                        <button type="button" className="student-button" disabled={busy} onClick={() => onCancel(registration)}>
                            {cancelling ? 'Cancelling…' : 'Cancel registration'}
                        </button>
                    </div>
                </>
            )}
        </article>
    );
}
