import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import workshopService from '../services/workshopService';
import workshopRegistrationService from '../services/workshopRegistrationService';
import { useReadCache, useReadQuery } from '../context/readQueries';
import DeferredContent from '../components/common/DeferredContent';
import RegistrationHistory from '../components/homeStudent/RegistrationHistory';
import PaginationControl from '../components/common/PaginationControl';
import { isHoldExpired, isWorkshopEnded } from '../utils/registrationDisplay';
import './studentExperience.css';
import './workshopTickets.css';

const PAGE_SIZE = 12;
const EMPTY_PAGE = { content: [], page: 0, totalPages: 0, totalElements: 0 };
const PaymentModal = lazy(() => import('../components/workshops/PaymentModal'));
const WorkshopTicketDialog = lazy(() => import('../components/workshops/WorkshopTicketDialog'));

export default function RegistrationHistoryPage() {
    const [page, setPage] = useState(0);
    const cache = useReadCache();
    const read = useCallback(() => workshopService.getUserWorkshops(page, PAGE_SIZE), [page]);
    const { data, loading, error: readError, retry } = useReadQuery('registrations', [page, PAGE_SIZE], read);
    const result = data ?? EMPTY_PAGE;
    const [actionError, setActionError] = useState('');
    const [paymentTarget, setPaymentTarget] = useState(null);
    const [ticket, setTicket] = useState(null);
    const [cancellingId, setCancellingId] = useState(null);
    const [now, setNow] = useState(Date.now);

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 5000);
        return () => window.clearInterval(timer);
    }, []);

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

    const openPayment = (registration) => {
        if (isHoldExpired(registration, Date.now()) || isWorkshopEnded(registration, Date.now())) {
            setNow(Date.now());
            return;
        }
        if (!registration.paymentIdempotencyKey) return;
        setActionError('');
        setPaymentTarget(registration);
    };

    const retryPayment = async () => {
        if (!paymentTarget) return;
        const response = await workshopRegistrationService.processPayment(paymentTarget.workshopId, paymentTarget.paymentIdempotencyKey);
        if (!response?.success) throw new Error(response?.message || 'Payment failed');
        if (response.qrCode) setTicket({ title: paymentTarget.title, qrCode: response.qrCode });
        setPaymentTarget(null);
        cache.invalidate('registrations', 'workshops', 'notifications');
    };

    const cancel = async (registration) => {
        if (cancellingId !== null) return;
        if (isHoldExpired(registration, Date.now()) || isWorkshopEnded(registration, Date.now())) {
            setNow(Date.now());
            return;
        }
        const { workshopId } = registration;
        setActionError('');
        setCancellingId(workshopId);
        try {
            await workshopRegistrationService.cancelRegistration(workshopId);
            cache.invalidate('registrations', 'workshops', 'notifications');
        } catch (err) {
            setActionError(err.response?.data?.message || err.message || 'Failed to cancel registration.');
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
                {actionError && <p className="student-notice student-notice--error" role="alert">{actionError}</p>}
                {loading ? (
                    <p className="student-notice" role="status">Loading registrations…</p>
                ) : readError ? (
                    <div className="student-notice student-notice--error" role="alert">
                        <p>We couldn’t load your registrations. Please try again.</p>
                        <button type="button" className="student-text-link" onClick={retry}>Try again</button>
                    </div>
                ) : (
                    <>
                        <RegistrationHistory pending={pending} history={history} now={now} onPay={openPayment}
                            onCancel={cancel} cancellingId={cancellingId} onViewTicket={setTicket} />
                        <div className="workshop-pagination">
                            <PaginationControl currentPage={result.page} totalPages={result.totalPages}
                                totalElements={result.totalElements} pageSize={PAGE_SIZE} itemLabel="registrations"
                                onPageChange={(page) => {
                                    setActionError('');
                                    setPage(page);
                                    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
                                    window.scrollTo({ top: 0, behavior: reduce ? 'instant' : 'smooth' });
                                }} />
                        </div>
                    </>
                )}
            </div>

            <Suspense fallback={<DeferredContent />}>
                {paymentTarget && (
                    <PaymentModal isOpen workshop={{ title: paymentTarget.title, price: paymentTarget.price }}
                        idempotencyKey={paymentTarget.paymentIdempotencyKey} onClose={() => setPaymentTarget(null)}
                        onPaymentSuccess={retryPayment} onPaymentError={(err) => setActionError(err?.message || 'Payment failed')} />
                )}
                {ticket && <WorkshopTicketDialog workshopTitle={ticket.title} qrCode={ticket.qrCode} onClose={() => setTicket(null)} />}
            </Suspense>
        </div>
    );
}
