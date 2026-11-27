import { useState, useEffect, useCallback } from 'react';
import { Link, useLocation } from 'react-router-dom';
import workshopService from '../services/workshopService';
import workshopRegistrationService from '../services/workshopRegistrationService';
import PaymentModal from '../components/workshops/PaymentModal';
import WorkshopTicketDialog from '../components/workshops/WorkshopTicketDialog';
import WorkshopDetailsDialog from '../components/workshops/WorkshopDetailsDialog';
import PaginationControl from '../components/common/PaginationControl';

import WorkshopCard from '../components/workshops/WorkshopCard';
import { isRegistrationClosed } from '../utils/workshopDisplay';
import './studentExperience.css';
import './workshopTickets.css';

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

const WorkshopListPage = () => {
    const requestedWorkshopId = useLocation().state?.workshopId;
    const [workshops, setWorkshops] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadAttempt, setLoadAttempt] = useState(0);
    const [error, setError] = useState('');
    const [selectedWorkshop, setSelectedWorkshop] = useState(null);
    const handleSelectWorkshop = (workshop) => {
        setRegistrationError('');
        setSelectedWorkshop(workshop);
    };

    const handleCloseWorkshop = () => setSelectedWorkshop(null);
    const [isRegistering, setIsRegistering] = useState(false);
    const [showPaymentModal, setShowPaymentModal] = useState(false);
    const [showSuccessModal, setShowSuccessModal] = useState(false);
    const [registrationData, setRegistrationData] = useState(null);
    const [registrationError, setRegistrationError] = useState('');

    // Pagination state
    const [currentPage, setCurrentPage] = useState(0);
    const [totalPages, setTotalPages] = useState(0);
    const [totalElements, setTotalElements] = useState(0);

    const receivePage = useCallback((data) => {
        setWorkshops(data.content || []);
        setCurrentPage(data.page);
        setTotalPages(data.totalPages);
        setTotalElements(data.totalElements);
    }, []);

    const fetchWorkshops = async (page) => {
        receivePage(await workshopService.getAll(page, PAGE_SIZE));
    };

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
            await fetchWorkshops(currentPage);

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
            // Generate unique Idempotency-Key
            const idempotencyKey = registrationData.idempotencyKey || crypto.randomUUID();
            const paymentResponse = await workshopRegistrationService.processPayment(
                registrationData.workshopId,
                idempotencyKey
            );

            if (paymentResponse.success) {
                if (paymentResponse.qrCode) {
                    setRegistrationData((current) => ({
                        ...current,
                        qrCode: paymentResponse.qrCode,
                    }));
                }
                await fetchWorkshops(currentPage);
                setShowPaymentModal(false);
                setShowSuccessModal(true);
            } else {
                setRegistrationError(`Payment failed: ${paymentResponse.message}`);
                await fetchWorkshops(currentPage);
            }
        } catch (error) {
            const status = error.response?.status;
            if (status === 409) {
                setRegistrationError('Payment is already being processed. Please wait or try again.');
            } else if (status === 429) {
                setRegistrationError('Too many payment requests. Please try again later.');
            } else {
                setRegistrationError(error.response?.data?.message || error.message || 'Payment failed');
            }
            await fetchWorkshops(currentPage);
        } finally {
            setIsRegistering(false);
        }
    };

    useEffect(() => {
        let active = true;
        workshopService.getAll(0, PAGE_SIZE)
            .then((data) => {
                if (!active) return;
                receivePage(data);
                if (requestedWorkshopId != null) {
                    setSelectedWorkshop(data.content?.find((item) => item.id === requestedWorkshopId) || null);
                }
            })
            .catch((err) => {
                if (active) setError(err.message || 'We couldn’t load workshops. Please try again.');
            })
            .finally(() => {
                if (active) setLoading(false);
            });

        return () => { active = false; };
    }, [loadAttempt, receivePage, requestedWorkshopId]);

    const retry = () => {
        setLoading(true);
        setError('');
        setLoadAttempt((attempt) => attempt + 1);
    };

    const handlePageChange = async (page) => {
        setError('');
        try {
            await fetchWorkshops(page);
            const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
            window.scrollTo({ top: 0, behavior: reducedMotion ? 'instant' : 'smooth' });
        } catch (err) {
            setError(err.message || 'We couldn’t load that page. Please try again.');
        }
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
                    <p className="student-notice">No workshops are available yet.</p>
                )}

                <div className="workshop-pagination">
                    <PaginationControl
                        currentPage={currentPage}
                        totalPages={totalPages}
                        totalElements={totalElements}
                        pageSize={PAGE_SIZE}
                        onPageChange={handlePageChange}
                        itemLabel="workshops"
                    />
                </div>
            </section>

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
            {registrationData && (
                <PaymentModal
                    isOpen={showPaymentModal}
                    workshop={{
                        title: registrationData.title || 'Workshop',
                        price: registrationData.amount,
                    }}
                    idempotencyKey={registrationData.idempotencyKey}
                    onClose={async () => {
                        if (registrationData?.workshopId) {
                            try {
                                await workshopRegistrationService.cancelRegistration(registrationData.workshopId);
                                await fetchWorkshops(currentPage);
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
        </div>
    );
};

export default WorkshopListPage;
