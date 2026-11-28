import { lazy, Suspense, useCallback, useState, useMemo } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import workshopService from '../services/workshopService';
import workshopRegistrationService from '../services/workshopRegistrationService';
import { useReadCache, useReadQuery } from '../context/readQueries';
import DeferredContent from '../components/common/DeferredContent';
import PaginationControl from '../components/common/PaginationControl';

import WorkshopFilters from '../components/workshops/WorkshopFilters';
import WorkshopCard from '../components/workshops/WorkshopCard';
import { isRegistrationClosed } from '../utils/workshopDisplay';
import './studentExperience.css';
import './workshopTickets.css';
import './discovery.css';

const PaymentModal = lazy(() => import('../components/workshops/PaymentModal'));
const WorkshopTicketDialog = lazy(() => import('../components/workshops/WorkshopTicketDialog'));
const WorkshopDetailsDialog = lazy(() => import('../components/workshops/WorkshopDetailsDialog'));

const isRegistrationOpen = (w) => {
    try {
        const now = new Date();
        // Prefer explicit registration window if provided, otherwise fall back to start/end
        const start = w.registrationStartTime ? new Date(w.registrationStartTime) : new Date(w.startTime);
        const end = w.registrationEndTime ? new Date(w.registrationEndTime) : new Date(w.endTime);
        return now >= start && now <= end;
    } catch {
        return true; // if parsing fails, default to allow registration and let backend enforce
    }
};

const getRegisterButtonLabel = (workshop, isRegistering) => {
    if (isRegistering) return 'Processing...';
    if (workshop?.userRegistrationStatus === 'SUCCESS') return 'Registered';
    if (workshop?.userRegistrationStatus === 'PENDING') return 'Payment pending';
    if (isRegistrationClosed(workshop)) return 'Registration closed';
    if (!isRegistrationOpen(workshop)) return 'Registration not open';
    if (workshop?.remainingSlots === 0) return 'Full';
    return 'Register';
};

const PAGE_SIZE = 12;
const FILTER_NAMES = ['q', 'from', 'to', 'fee', 'seatsAvailable'];
const EMPTY_PAGE = { content: [], totalPages: 0, totalElements: 0 };

const WorkshopListPage = () => {
    const location = useLocation();
    const navigate = useNavigate();
    const requestedWorkshopId = location.state?.workshopId;
    const [searchParams, setSearchParams] = useSearchParams();
    const filters = useMemo(() => Object.fromEntries(FILTER_NAMES.map((name) => [name, searchParams.get(name) || ''])), [searchParams]);
    const requestedPage = Number(searchParams.get('page') || 0);
    const page = Number.isInteger(requestedPage) && requestedPage >= 0 && requestedPage <= Math.floor(2_147_483_647 / PAGE_SIZE)
        ? requestedPage : 0;
    const cache = useReadCache();
    const query = useMemo(() => Object.fromEntries(Object.entries(filters).filter(([, value]) => value !== '')), [filters]);
    const read = useCallback(() => workshopService.getAll(page, PAGE_SIZE, query), [page, query]);
    const { data, loading, error: loadError, retry } = useReadQuery('workshops', [page, PAGE_SIZE, query], read);
    const { content: workshops, totalPages, totalElements } = data ?? EMPTY_PAGE;
    const error = loadError && (loadError.message || 'We couldn’t load workshops.');
    const [selection, setSelection] = useState(null);
    const selectedWorkshop = selection?.locationKey === location.key
        ? selection.workshop
        : data?.content?.find((item) => item.id === requestedWorkshopId);
    const handleSelectWorkshop = (workshop) => {
        setRegistrationError('');
        setSelection({ locationKey: location.key, workshop });
    };

    const handleCloseWorkshop = () => setSelection({ locationKey: location.key, workshop: null });
    const [isRegistering, setIsRegistering] = useState(false);
    const [showPaymentModal, setShowPaymentModal] = useState(false);
    const [showSuccessModal, setShowSuccessModal] = useState(false);
    const [registrationData, setRegistrationData] = useState(null);
    const [registrationError, setRegistrationError] = useState('');

    const reload = () => cache.invalidate('workshops', 'registrations', 'notifications');

    const handleRegister = async () => {
        if (!selectedWorkshop) return;
        setIsRegistering(true);
        setRegistrationError('');

        try {
            const response = await workshopRegistrationService.register(selectedWorkshop.id);
            const dataWithTitle = {
                ...response,
                title: selectedWorkshop.title,
                workshopId: selectedWorkshop.id,
            };
            setRegistrationData(dataWithTitle);
            reload();

            if (response.paidFlow) {
                // Show payment modal for paid workshops
                setShowPaymentModal(true);
            } else {
                // Show success modal for free workshops
                setShowSuccessModal(true);
            }
            handleCloseWorkshop();
        } catch (error) {
            const status = error.response?.status;
            if (status === 409) {
                setRegistrationError('You have already registered for this workshop!');
            } else if (status === 429) {
                setRegistrationError('Too many requests. Please try again later.');
            } else if (status === 402) {
                setRegistrationError('No seats left. Please choose another workshop.');
            } else {
                setRegistrationError(error.response?.data?.message || error.message || 'Registration failed');
            }
        } finally {
            setIsRegistering(false);
        }
    };

    const handlePaymentSuccess = async () => {
        if (!registrationData) return;
        setIsRegistering(true);
        setRegistrationError('');

        try {
            const paymentResponse = await workshopRegistrationService.processPayment(
                registrationData.workshopId,
                registrationData.idempotencyKey
            );

            if (paymentResponse.checkoutStatus === 'OPEN') return;
            if (paymentResponse.checkoutStatus) {
                reload();
                navigate('/registration-history?checkout=return');
                return;
            }
            if (paymentResponse.success) {
                if (paymentResponse.qrCode) {
                    setRegistrationData((current) => ({
                        ...current,
                        qrCode: paymentResponse.qrCode,
                    }));
                }
                reload();
                setShowPaymentModal(false);
                setShowSuccessModal(true);
            } else {
                throw new Error(paymentResponse.message || 'Payment failed');
            }
        } catch (error) {
            reload();
            throw error;
        } finally {
            setIsRegistering(false);
        }
    };

    const applyFilters = (values) => {
        setSearchParams(Object.fromEntries(Object.entries(values).filter(([, value]) => value !== '')));
    };

    const handlePageChange = (nextPage) => {
        setSearchParams((previous) => {
            const next = new URLSearchParams(previous);
            if (nextPage === 0) next.delete('page');
            else next.set('page', nextPage);
            return next;
        });
        const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        window.scrollTo({ top: 0, behavior: reducedMotion ? 'instant' : 'smooth' });
    };

    return (
        <div className="student-experience" aria-label="Workshop catalog">
            <header className="workshop-catalog-header">
                <div>
                    <h1>Explore workshops</h1>
                    <p>Find a session that fits your schedule and reserve your seat.</p>
                </div>
                <Link to="/registration-history" className="student-button">
                    My registrations <span aria-hidden="true">→</span>
                </Link>
            </header>

            <section className="student-section" aria-labelledby="catalog-results" aria-busy={loading}>
                <div className="student-section-heading">
                    <h2 id="catalog-results">Workshop catalog</h2>
                    {!loading && !error && (
                        <p>{totalElements} {totalElements === 1 ? 'workshop' : 'workshops'}</p>
                    )}
                </div>
                <WorkshopFilters key={JSON.stringify(filters)} filters={filters} onApply={applyFilters} onReset={() => setSearchParams({})} />
                {error && (
                    <div className="student-notice student-notice--error" role="alert">
                        <p>{error}</p>
                        {!workshops.length && <button className="student-text-link" type="button" onClick={retry}>Try again</button>}
                    </div>
                )}
                {loading ? (
                    <p className="student-notice" role="status">Loading workshops…</p>
                ) : workshops.length ? (
                    <div className="workshop-grid">
                        {workshops.map((workshop, index) => (
                            <WorkshopCard
                                key={workshop.id}
                                workshop={workshop}
                                index={index}
                                onOpen={handleSelectWorkshop}
                            />
                        ))}
                    </div>
                ) : !error && (
                    <p className="student-notice">No workshops match these filters.</p>
                )}

                <div className="workshop-pagination">
                    <PaginationControl
                        currentPage={page}
                        totalPages={totalPages}
                        totalElements={totalElements}
                        pageSize={PAGE_SIZE}
                        onPageChange={handlePageChange}
                        itemLabel="workshops"
                    />
                </div>
            </section>

            <Suspense fallback={<DeferredContent />}>
                {selectedWorkshop && (
                    <WorkshopDetailsDialog
                        workshop={selectedWorkshop}
                        registerLabel={getRegisterButtonLabel(selectedWorkshop, isRegistering)}
                        canRegister={selectedWorkshop.remainingSlots !== 0 && !isRegistering
                            && isRegistrationOpen(selectedWorkshop) && !selectedWorkshop.userRegistrationStatus}
                        busy={isRegistering}
                        error={registrationError}
                        onRegister={handleRegister}
                        onClose={handleCloseWorkshop}
                    />
                )}

                {/* Payment Modal */}
                {registrationData && showPaymentModal && (
                    <PaymentModal
                        isOpen={showPaymentModal}
                        workshop={{
                            title: registrationData.title || 'Workshop',
                            price: registrationData.amount,
                        }}
                        onClose={async () => {
                            if (registrationData?.workshopId) {
                                try {
                                    await workshopRegistrationService.cancelRegistration(registrationData.workshopId);
                                    reload();
                                } catch (e) {
                                    console.error('Failed to cancel registration:', e);
                                }
                            }
                            setShowPaymentModal(false);
                            setRegistrationData(null);
                            setRegistrationError('');
                        }}
                        onPaymentSuccess={handlePaymentSuccess}
                        onPaymentError={(error) => {
                            setRegistrationError(error.message || 'Payment error');
                        }}
                    />
                )}

                {registrationData && showSuccessModal && (
                    <WorkshopTicketDialog
                        justRegistered
                        qrCode={registrationData.qrCode}
                        workshopTitle={registrationData.title || 'Workshop'}
                        onClose={() => {
                            setShowSuccessModal(false);
                            setRegistrationData(null);
                            setRegistrationError('');
                        }}
                    />
                )}
            </Suspense>
        </div>
    );
};

export default WorkshopListPage;
