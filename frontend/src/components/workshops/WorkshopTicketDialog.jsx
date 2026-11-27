import { useEffect, useId, useRef } from 'react';
import { QRCodeSVG } from 'qrcode.react';

export default function WorkshopTicketDialog({ workshopTitle, qrCode, onClose, justRegistered = false }) {
    const dialogRef = useRef(null);
    const titleId = useId();

    useEffect(() => {
        const dialog = dialogRef.current;
        dialog.showModal();
        return () => dialog.close();
    }, []);

    const close = () => {
        dialogRef.current.close();
        onClose();
    };

    return (
        <dialog
            ref={dialogRef}
            className="workshop-dialog ticket-dialog"
            aria-labelledby={titleId}
            onCancel={(event) => { event.preventDefault(); close(); }}
            onClick={(event) => { if (event.target === event.currentTarget) close(); }}
        >
            <div className="ticket-dialog-body">
                <span className="workshop-status workshop-status--registered">Confirmed</span>
                <h2 id={titleId} tabIndex={-1} autoFocus>
                    {justRegistered ? 'Registration confirmed' : 'Check-in ticket'}
                </h2>
                <p className="ticket-dialog-workshop">{workshopTitle}</p>
                {qrCode ? (
                    <>
                        <div className="ticket-qr">
                            <QRCodeSVG value={qrCode} size={280} level="M" marginSize={4} title="Workshop check-in QR code" />
                        </div>
                        <p className="ticket-qr-note">Show this code at the workshop entrance to check in.</p>
                    </>
                ) : (
                    <p className="student-notice">Your QR code is being prepared. Check My registrations later.</p>
                )}
                <button type="button" className="student-button student-button--primary" onClick={close}>Done</button>
            </div>
        </dialog>
    );
}
