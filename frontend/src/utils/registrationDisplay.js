const HOLD_EXPIRY_MS = 10 * 60 * 1000;

export const isWorkshopEnded = ({ endTime }, now) =>
    Boolean(endTime && new Date(endTime).getTime() <= now);

export const isHoldExpired = ({ status, createdAt }, now) =>
    status === 'PENDING' && Boolean(createdAt && now - new Date(createdAt).getTime() >= HOLD_EXPIRY_MS);

export function registrationStatus(status) {
    switch (status) {
        case 'SUCCESS': return { label: 'Confirmed', tone: 'registered' };
        case 'PENDING': return { label: 'Payment pending', tone: 'pending' };
        case 'FAILED': return { label: 'Payment failed', tone: 'failed' };
        case 'CANCELLED': return { label: 'Cancelled', tone: 'neutral' };
        default: return { label: 'Status unavailable', tone: 'neutral' };
    }
}
