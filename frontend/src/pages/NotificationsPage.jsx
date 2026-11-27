import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import DOMPurify from 'dompurify';
import notificationService from '../services/notificationService';
import PaginationControl from '../components/common/PaginationControl';
import { formatDateTime } from '../utils/workshopDisplay';
import './studentExperience.css';
import './studentAccount.css';

const PAGE_SIZE = 10;
const inboxMarkup = {
    ALLOWED_TAGS: ['p', 'div', 'span', 'br', 'hr', 'strong', 'em', 'b', 'i', 'a', 'ul', 'ol', 'li',
        'h2', 'h3', 'h4', 'table', 'thead', 'tbody', 'tr', 'td', 'th'],
    ALLOWED_ATTR: ['href', 'title', 'colspan', 'rowspan'],
    ALLOW_DATA_ATTR: false,
};

export default function NotificationsPage() {
    const [result, setResult] = useState({ content: [], page: 0, totalPages: 0, totalElements: 0 });
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState(null);
    const [openId, setOpenId] = useState(null);
    const [readErrors, setReadErrors] = useState({});
    const readRequests = useRef(new Set());

    const fetchPage = useCallback((page) => notificationService.getMyNotifications(page, PAGE_SIZE)
        .then((data) => {
            const content = data.content || [];
            setResult({ content, page: data.page ?? page, totalPages: data.totalPages ?? 1,
                totalElements: data.totalElements ?? content.length });
        })
        .catch((err) => setLoadError({ page, message: err.message || 'We couldn’t load notifications.' }))
        .finally(() => setLoading(false)), []);

    useEffect(() => {
        fetchPage(0);
    }, [fetchPage]);

    const changePage = (page) => {
        setOpenId(null);
        setLoadError(null);
        setLoading(true);
        fetchPage(page);
    };

    const markRead = async (item) => {
        if (item.read === true || readRequests.current.has(item.id)) return;
        setReadErrors((errors) => ({ ...errors, [item.id]: false }));
        readRequests.current.add(item.id);
        try {
            await notificationService.markRead(item.id);
            setResult((previous) => ({
                ...previous,
                content: previous.content.map((entry) => entry.id === item.id ? { ...entry, read: true } : entry),
            }));
        } catch {
            setReadErrors((errors) => ({ ...errors, [item.id]: true }));
        } finally {
            readRequests.current.delete(item.id);
        }
    };

    const unread = result.content.filter((item) => item.read !== true).length;

    return (
        <div className="student-experience" aria-label="Notifications">
            <header className="workshop-catalog-header">
                <div>
                    <h1>Notifications</h1>
                    <p>Keep up with workshop updates and reminders.</p>
                </div>
                <Link className="student-button" to="/profile">My profile <span aria-hidden="true">→</span></Link>
            </header>

            <section className="student-section inbox" aria-labelledby="inbox-title" aria-busy={loading}>
                <div className="student-section-heading">
                    <h2 id="inbox-title">Inbox</h2>
                    {!loading && !loadError && result.content.length > 0 && (
                        <p className="inbox-count" aria-live="polite">{unread} unread on this page</p>
                    )}
                </div>
                {loadError && (
                    <div className="student-notice student-notice--error" role="alert">
                        <p>{loadError.message}</p>
                        <button type="button" className="student-text-link" onClick={() => changePage(loadError.page)}>Try again</button>
                    </div>
                )}
                {loading ? (
                    <p className="student-notice" role="status">Loading notifications…</p>
                ) : result.content.length ? (
                    <ul className="inbox-list">
                        {result.content.map((item) => {
                            const expanded = item.id === openId;
                            const unreadItem = item.read !== true;
                            return (
                                <li key={item.id} className="inbox-item" data-unread={unreadItem}>
                                    <h3>
                                        <button type="button" className="inbox-trigger" id={`notice-button-${item.id}`}
                                            aria-expanded={expanded} aria-controls={`notice-content-${item.id}`}
                                            onClick={() => {
                                                setOpenId(expanded ? null : item.id);
                                                if (!expanded) markRead(item);
                                            }}>
                                            <span className="inbox-dot" aria-hidden="true" />
                                            <span className="inbox-heading">
                                                <span className="inbox-item-title">{item.title}</span>
                                                <span className="inbox-time">{formatDateTime(item.createdAt)}</span>
                                            </span>
                                            <span className={`inbox-state${unreadItem ? ' inbox-state--new' : ''}`}>
                                                {unreadItem ? 'Unread' : 'Read'}
                                            </span>
                                            <span className="inbox-chevron" aria-hidden="true" />
                                        </button>
                                    </h3>
                                    <div id={`notice-content-${item.id}`} className="inbox-detail" hidden={!expanded}>
                                        {expanded && (
                                            <>
                                                <div className="inbox-message"
                                                    dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(item.contentHtml || '', inboxMarkup) }} />
                                                {readErrors[item.id] && (
                                                    <div className="inbox-read-error" role="alert">
                                                        <p>Couldn’t save the read status.</p>
                                                        <button type="button" className="student-text-link" onClick={() => markRead(item)}>Try again</button>
                                                    </div>
                                                )}
                                            </>
                                        )}
                                    </div>
                                </li>
                            );
                        })}
                    </ul>
                ) : !loadError && <p className="student-notice">No notifications yet.</p>}
                {!loading && (
                    <div className="workshop-pagination">
                        <PaginationControl currentPage={result.page} totalPages={result.totalPages}
                            totalElements={result.totalElements} pageSize={PAGE_SIZE} itemLabel="notifications"
                            onPageChange={(page) => {
                                changePage(page);
                                window.scrollTo({ top: 0, behavior: 'instant' });
                            }} />
                    </div>
                )}
            </section>
        </div>
    );
}
