import { useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatDateTime, formatDescription, formatPrice } from '../../utils/workshopDisplay';
import { registrationStatus } from '../../utils/registrationDisplay';

export default function WorkshopDetailsDialog({ workshop, registerLabel, canRegister, busy, error, onRegister, onClose }) {
    const dialogRef = useRef(null);
    const titleId = useId();
    const mapId = useId();
    const [showMap, setShowMap] = useState(false);
    const [mapStatus, setMapStatus] = useState('loading');

    useEffect(() => {
        const dialog = dialogRef.current;
        dialog.showModal();
        return () => dialog.close();
    }, []);

    const close = () => {
        if (busy) return;
        dialogRef.current.close();
        onClose();
    };

    const status = workshop.userRegistrationStatus ? registrationStatus(workshop.userRegistrationStatus) : null;
    const room = workshop.roomName || 'Room TBA';
    const canOpenTicket = workshop.userRegistrationStatus === 'SUCCESS' || workshop.userRegistrationStatus === 'PENDING';

    return (
        <dialog
            ref={dialogRef}
            className="workshop-dialog workshop-detail"
            aria-labelledby={titleId}
            onCancel={(event) => { event.preventDefault(); close(); }}
            onClick={(event) => { if (event.target === event.currentTarget) close(); }}
        >
            <div className="workshop-detail-layout">
                <header className="workshop-detail-cover">
                    <div className="workshop-detail-badges">
                        <span className="workshop-status">{formatPrice(workshop.price)}</span>
                        {status && <span className={`workshop-status workshop-status--${status.tone}`}>{status.label}</span>}
                    </div>
                    <h2 id={titleId} tabIndex={-1} autoFocus>{workshop.title}</h2>
                    <p>{formatDateTime(workshop.startTime)}</p>
                </header>

                <div className="workshop-detail-body">
                    <dl className="registration-facts workshop-detail-facts">
                        <div><dt>Room</dt><dd>{room}{workshop.roomCapacity ? ` · ${workshop.roomCapacity} seats` : ''}</dd></div>
                        <div><dt>Speaker</dt><dd>{workshop.speaker || 'To be announced'}</dd></div>
                        <div><dt>Workshop ends</dt><dd>{formatDateTime(workshop.endTime)}</dd></div>
                        <div><dt>Seats available</dt><dd>{workshop.remainingSlots ?? '—'}</dd></div>
                        <div><dt>Registration opens</dt><dd>{formatDateTime(workshop.registrationStartTime)}</dd></div>
                        <div><dt>Registration closes</dt><dd>{formatDateTime(workshop.registrationEndTime)}</dd></div>
                    </dl>
                    <section className="workshop-detail-section">
                        <h3>About this workshop</h3>
                        <p>{formatDescription(workshop.description)}</p>
                    </section>
                    <section className="workshop-detail-section">
                        <h3>Seat map</h3>
                        {workshop.layoutMapUrl ? (
                            <>
                                <button type="button" className="student-button" aria-expanded={showMap} aria-controls={mapId}
                                    onClick={() => { if (!showMap) setMapStatus('loading'); setShowMap(!showMap); }}>
                                    {showMap ? 'Hide seat map' : 'Show seat map'}
                                </button>
                                <div id={mapId} hidden={!showMap}>
                                    {showMap && (
                                        <div className="workshop-detail-map">
                                            {mapStatus === 'loading' && <p role="status">Loading seat map…</p>}
                                            {mapStatus === 'error' && <p role="alert">The seat map could not be loaded.</p>}
                                            <img src={workshop.layoutMapUrl} alt={`Seat map for ${room}`}
                                                hidden={mapStatus === 'error'} onLoad={() => setMapStatus('loaded')}
                                                onError={() => setMapStatus('error')} />
                                        </div>
                                    )}
                                </div>
                            </>
                        ) : <p>No seat map is available for this room.</p>}
                    </section>
                    {error && <p role="alert" className="student-notice student-notice--error">{error}</p>}
                </div>
                <div className="workshop-detail-actions">
                    <button type="button" className="student-button" onClick={close} disabled={busy}>Close</button>
                    {canOpenTicket ? (
                        <Link className="student-button student-button--primary" to="/registration-history">
                            {workshop.userRegistrationStatus === 'SUCCESS' ? 'Open my tickets' : 'Manage my booking'}
                        </Link>
                    ) : (
                        <button type="button" className="student-button student-button--primary" onClick={onRegister}
                            disabled={!canRegister} aria-busy={busy}>{registerLabel}</button>
                    )}
                </div>
            </div>
        </dialog>
    );
}
