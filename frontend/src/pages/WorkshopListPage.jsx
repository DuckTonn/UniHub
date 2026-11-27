import { useState, useEffect, useCallback, useRef } from 'react';
import { Link, useLocation } from 'react-router-dom';
import workshopService from '../services/workshopService';
import workshopRegistrationService from '../services/workshopRegistrationService';
import PaymentModal from '../components/workshops/PaymentModal';
import RegistrationSuccessModal from '../components/workshops/RegistrationSuccessModal';
import PaginationControl from '../components/common/PaginationControl';

import WorkshopCard from '../components/workshops/WorkshopCard';
import { formatDateTime, formatDescription, formatPrice, isRegistrationClosed, isRegistrationStarted } from '../utils/workshopDisplay';
import './studentExperience.css';

const getSeatMapAlt = (workshop) => {
    const roomName = workshop?.roomName ? ` for ${workshop.roomName}` : '';
    return `Seat map${roomName}`;
};

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
    if (!isRegistrationStarted(workshop)) return 'Registration Not Started';
    if (isRegistrationClosed(workshop)) return 'Registration closed';
    if (workshop?.remainingSlots === 0) return 'Full';
    return 'Register';
};

const PAGE_SIZE = 12;

const WorkshopListPage = () => {
    const requestedWorkshopId = useLocation().state?.workshopId;
    const detailDialog = useRef(null);
    const [workshops, setWorkshops] = useState([]);
    const [loading, setLoading] = useState(true);
    const [loadAttempt, setLoadAttempt] = useState(0);
    const [error, setError] = useState('');
    const [selectedWorkshop, setSelectedWorkshop] = useState(null);
    const [showSeatMap, setShowSeatMap] = useState(false);
    const [seatMapLoading, setSeatMapLoading] = useState(false);
    const [seatMapError, setSeatMapError] = useState('');

    const resetSeatMapState = () => {
        setShowSeatMap(false);
        setSeatMapLoading(false);
        setSeatMapError('');
    };

    const handleSelectWorkshop = (workshop) => {
        resetSeatMapState();
        setRegistrationError('');
        setSelectedWorkshop(workshop);
    };

    const handleCloseWorkshop = () => {
        resetSeatMapState();
        setSelectedWorkshop(null);
    };
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

    useEffect(() => {
        const dialog = detailDialog.current;
        if (!selectedWorkshop || !dialog) return;
        dialog.showModal();
        return () => dialog.close();
    }, [selectedWorkshop]);

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

            {/* Workshop Detail Modal */}
            {selectedWorkshop && (
                <dialog
                    ref={detailDialog}
                    className="workshop-dialog"
                    aria-labelledby="workshop-dialog-title"
                    onCancel={(event) => {
                        event.preventDefault();
                        if (!isRegistering) handleCloseWorkshop();
                    }}
                    onClick={(event) => {
                        if (event.target === event.currentTarget && !isRegistering) handleCloseWorkshop();
                    }}
                >
                    <div className="workshop-dialog-content">
                        <h2 className="text-2xl font-bold" id="workshop-dialog-title" tabIndex={-1} autoFocus>{selectedWorkshop.title}</h2>

                        <div className="mt-4 space-y-3">
                            <div className="flex items-start gap-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Time:</span>
                                <span className="text-sm text-gray-700 font-semibold">{formatDateTime(selectedWorkshop.startTime)}</span>
                            </div>
                            <div className="flex items-start gap-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Speaker:</span>
                                <span className="text-sm text-gray-700 font-semibold">{selectedWorkshop.speaker}</span>
                            </div>
                            <div className="flex items-start gap-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Room:</span>
                                <span className="text-sm text-gray-700">
                                    {selectedWorkshop.roomName || 'Room TBA'}
                                    {selectedWorkshop.roomCapacity ? ` (${selectedWorkshop.roomCapacity} seats)` : ''}
                                </span>
                            </div>
                            <div className="flex items-start gap-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Ends:</span>
                                <span className="text-sm text-gray-700">{formatDateTime(selectedWorkshop.endTime)}</span>
                            </div>
                            
                            {/* Bổ sung Registration Starts */}
                            <div className="flex items-start gap-2 border-t border-gray-50 pt-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Registration starts:</span>
                                <span className="text-sm text-gray-700">{formatDateTime(selectedWorkshop.registrationStartTime)}</span>
                            </div>
                            
                            <div className="flex items-start gap-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Registration ends:</span>
                                <span className="text-sm text-gray-700">{formatDateTime(selectedWorkshop.registrationEndTime)}</span>
                            </div>
                            
                            <div className="flex items-start gap-2 border-t border-gray-50 pt-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Price:</span>
                                <span className={`text-sm font-bold ${
                                    Number(selectedWorkshop.price) === 0 ? 'text-emerald-600' : 'text-amber-600'
                                }`}>
                                    {formatPrice(selectedWorkshop.price)}
                                </span>
                            </div>
                            <div className="flex items-start gap-2">
                                <span className="text-sm font-medium text-gray-500 w-26 sm:w-36 shrink-0">Seats:</span>
                                <span className="text-sm text-gray-700">
                                    {selectedWorkshop.remainingSlots} seats left
                                </span>
                            </div>
                        </div>

                        <div className="mt-4">
                            <h4 className="text-sm font-medium text-gray-500 mb-1">Description:</h4>
                            <p className="text-sm text-gray-700 whitespace-pre-wrap">{formatDescription(selectedWorkshop.description)}</p>
                        </div>

                        {/* Seat map */}
                        <div className="mt-4 border-t border-gray-50 pt-3">
                            <h4 className="text-sm font-medium text-gray-500 mb-2">
                                Seat map: {!selectedWorkshop.layoutMapUrl && <span className="font-normal italic">Unavailable</span>}
                            </h4>

                            {selectedWorkshop.layoutMapUrl && (
                                <>
                                    <div className="flex items-center gap-2 mb-2">
                                        <button
                                            onClick={() => {
                                                setShowSeatMap((s) => !s);
                                                if (!showSeatMap) {
                                                    setSeatMapLoading(true);
                                                    setSeatMapError('');
                                                }
                                            }}
                                            className="rounded-md border px-3 py-1 text-sm bg-white hover:bg-gray-50"
                                        >
                                            {showSeatMap ? 'Hide seat map' : 'View seat map'}
                                        </button>
                                    </div>

                                    {showSeatMap && (
                                        <div className="w-full overflow-hidden rounded-lg border border-gray-100 bg-gray-50 p-3">
                                            {seatMapLoading && (
                                                <div className="mb-2 text-sm text-gray-500">Loading seat map...</div>
                                            )}
                                            {seatMapError && (
                                                <div className="mb-2 text-sm text-red-600">Failed to load seat map</div>
                                            )}
                                            <img
                                                src={selectedWorkshop.layoutMapUrl}
                                                alt={getSeatMapAlt(selectedWorkshop)}
                                                loading="lazy"
                                                className="max-h-[55vh] w-full rounded object-contain"
                                                onLoad={() => setSeatMapLoading(false)}
                                                onError={() => { setSeatMapLoading(false); setSeatMapError('error'); }}
                                            />
                                        </div>
                                    )}
                                </>
                            )}
                        </div>

                        {selectedWorkshop.userRegistrationStatus && (
                            <div className="mt-4">
                                <h4 className="text-sm font-medium text-gray-500 mb-1">Registration status:</h4>
                                <span className={`inline-flex rounded-full px-3 py-1 text-xs font-semibold ${
                                    selectedWorkshop.userRegistrationStatus === 'SUCCESS'
                                        ? 'bg-emerald-100 text-emerald-700'
                                        : 'bg-amber-100 text-amber-700'
                                }`}>
                                    {selectedWorkshop.userRegistrationStatus === 'SUCCESS' ? 'Registered' : 'Payment pending'}
                                </span>
                            </div>
                        )}

                        {registrationError && (
                            <div className="mt-4 p-3 bg-red-50 border border-red-200 text-red-700 rounded text-sm">
                                {registrationError}
                            </div>
                        )}

                        <div className="workshop-dialog-actions">
                            <button
                                onClick={handleCloseWorkshop}
                                disabled={isRegistering}
                                className="student-button"
                            >
                                Close
                            </button>
                            <button
                                onClick={handleRegister}
                                disabled={
                                    selectedWorkshop.remainingSlots === 0
                                    || isRegistering
                                    || !isRegistrationOpen(selectedWorkshop)
                                    || Boolean(selectedWorkshop.userRegistrationStatus)
                                }
                                className="student-button student-button--primary"
                            >
                                {getRegisterButtonLabel(selectedWorkshop, isRegistering)}
                            </button>
                        </div>
                    </div>
                </dialog>
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

            {/* Registration Success Modal */}
            {registrationData && (
                <RegistrationSuccessModal
                    isOpen={showSuccessModal}
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
