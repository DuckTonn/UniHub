import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import workshopService from '../services/workshopService';
import workshopRegistrationService from '../services/workshopRegistrationService';
import PaymentModal from '../components/workshops/PaymentModal';
import WorkshopTicketDialog from '../components/workshops/WorkshopTicketDialog';
import RegistrationHistory from '../components/homeStudent/RegistrationHistory';
import PaginationControl from '../components/common/PaginationControl';
import { isHoldExpired, isWorkshopEnded } from '../utils/registrationDisplay';
import './studentExperience.css';
import './workshopTickets.css';

const PAGE_SIZE = 12;

export default function RegistrationHistoryPage() {
    const [result, setResult] = useState({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [paymentTarget, setPaymentTarget] = useState(null);
    const [ticket, setTicket] = useState(null);
    const [cancellingId, setCancellingId] = useState(null);
    const [now, setNow] = useState(Date.now);

    const fetchPage = useCallback((page) => workshopService.getUserWorkshops(page, PAGE_SIZE)
        .then((data) => setResult(data))
        .catch(() => setError('We couldn’t load your registrations. Please try again.'))
        .finally(() => setLoading(false)), []);

    useEffect(() => {
        fetchPage(0);
        const timer = window.setInterval(() => setNow(Date.now()), 5000);
        return () => window.clearInterval(timer);
    }, [fetchPage]);

    const sorted = useMemo(() => [...(result.content || [])].sort((left, right) =>
        new Date(right.createdAt || 0).getTime() - new Date(left.createdAt || 0).getTime()
    ), [result.content]);
    const { pending, history } = useMemo(() => {
        const pending = [];
        const history = [];
        for (const registration of sorted) {
            if (registration.status === 'PENDING' && !isWorkshopEnded(registration, now)) pending.push(registration);
            else history.push(registration);
        }
        return { pending, history };
    }, [sorted, now]);

    const refresh = (page) => {
        setLoading(true);
        setError('');
        return fetchPage(page);
    };

    const openPayment = (registration) => {
        if (isHoldExpired(registration, Date.now()) || isWorkshopEnded(registration, Date.now())) {
            setNow(Date.now());
            return;
        }
        if (!registration.paymentIdempotencyKey) return;
        setError('');
        setPaymentTarget(registration);
    };

    const retryPayment = async () => {
        if (!paymentTarget) return;
        const response = await workshopRegistrationService.processPayment(paymentTarget.workshopId, paymentTarget.paymentIdempotencyKey);
        if (!response?.success) throw new Error(response?.message || 'Payment failed');
        if (response.qrCode) setTicket({ title: paymentTarget.title, qrCode: response.qrCode });
        setPaymentTarget(null);
        await refresh(result.page);
    };

    const cancel = async (registration) => {
        if (cancellingId !== null) return;
        if (isHoldExpired(registration, Date.now()) || isWorkshopEnded(registration, Date.now())) {
            setNow(Date.now());
            return;
        }
        const { workshopId } = registration;
        setError('');
        setCancellingId(workshopId);
        try {
            await workshopRegistrationService.cancelRegistration(workshopId);
            await refresh(result.page);
        } catch (err) {
            setError(err.response?.data?.message || err.message || 'Failed to cancel registration.');
        } finally {
            setCancellingId(null);
        }
    };

    return (
        <div className="student-experience" aria-label="My registrations">
            <header className="workshop-catalog-header">
                <div>
                    <h1>My registrations</h1>
                    <p>Manage pending payments and open your check-in tickets.</p>
                </div>
                <Link className="student-button" to="/workshops">Browse workshops <span aria-hidden="true">→</span></Link>
            </header>

            <div className="student-section" aria-busy={loading}>
                {error && <p className="student-notice student-notice--error" role="alert">{error}</p>}
                {loading ? (
                    <p className="student-notice" role="status">Loading registrations…</p>
                ) : error && !result.content?.length ? (
                    <button type="button" className="student-button" onClick={() => refresh(result.page)}>Try again</button>
                ) : (
                    <>
                        <RegistrationHistory pending={pending} history={history} now={now} onPay={openPayment}
                            onCancel={cancel} cancellingId={cancellingId} onViewTicket={setTicket} />
                        <div className="workshop-pagination">
                            <PaginationControl currentPage={result.page} totalPages={result.totalPages}
                                totalElements={result.totalElements} pageSize={PAGE_SIZE} itemLabel="registrations"
                                onPageChange={(page) => {
                                    refresh(page);
                                    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
                                    window.scrollTo({ top: 0, behavior: reduce ? 'instant' : 'smooth' });
                                }} />
                        </div>
                    </>
                )}
            </div>

            {paymentTarget && (
                <PaymentModal isOpen workshop={{ title: paymentTarget.title, price: paymentTarget.price }}
                    idempotencyKey={paymentTarget.paymentIdempotencyKey} onClose={() => setPaymentTarget(null)}
                    onPaymentSuccess={retryPayment} onPaymentError={(err) => setError(err?.message || 'Payment failed')} />
            )}
            {ticket && <WorkshopTicketDialog workshopTitle={ticket.title} qrCode={ticket.qrCode} onClose={() => setTicket(null)} />}
        </div>
    );
}
