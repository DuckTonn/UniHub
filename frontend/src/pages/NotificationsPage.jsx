import { useContext, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import DOMPurify from 'dompurify';
import { UnreadNotificationsContext } from '../context/UnreadNotificationsContext';
import notificationService from '../services/notificationService';
import PaginationControl from '../components/common/PaginationControl';
import { formatDateTime } from '../utils/workshopDisplay';
import './studentExperience.css';
import './studentAccount.css';
import './discovery.css';

const EMPTY_PAGE = { content: [], page: 0, totalPages: 0, totalElements: 0 };

const PAGE_SIZE = 10;
const inboxMarkup = {
    ALLOWED_TAGS: ['p', 'div', 'span', 'br', 'hr', 'strong', 'em', 'b', 'i', 'a', 'ul', 'ol', 'li',
        'h2', 'h3', 'h4', 'table', 'thead', 'tbody', 'tr', 'td', 'th'],
    ALLOWED_ATTR: ['href', 'title', 'colspan', 'rowspan'],
    ALLOW_DATA_ATTR: false,
};

export default function NotificationsPage() {
    const refreshUnreadCount = useContext(UnreadNotificationsContext);
    const location = useLocation();
    const [params, setParams] = useSearchParams();
    const unreadOnly = params.get('view') === 'unread';
    const requestedPage = Number(params.get('page') || 0);
    const page = Number.isInteger(requestedPage) && requestedPage >= 0 && requestedPage <= Math.floor(2_147_483_647 / PAGE_SIZE)
        ? requestedPage : 0;
    const [loadAttempt, setLoadAttempt] = useState(0);
    const requestKey = `${location.key}:${loadAttempt}`;
    const [loaded, setLoaded] = useState(null);
    const loading = loaded?.key !== requestKey;
    const result = loading ? EMPTY_PAGE : loaded;
    const loadError = result.error;
    const [openNotice, setOpenNotice] = useState(null);
    const openId = openNotice?.key === location.key ? openNotice.id : null;
    const [readErrors, setReadErrors] = useState({});
    const readRequests = useRef(new Set());
    const closedInbox = useRef(null);

    useEffect(() => {
        let active = true;
        notificationService.getMyNotifications(page, PAGE_SIZE, unreadOnly)
            .then((data) => {
                if (!active) return;
                const content = data.content || [];
                const totalPages = data.totalPages ?? 1;
                if (unreadOnly && page > 0 && page >= totalPages) {
                    const next = new URLSearchParams({ view: 'unread' });
                    if (totalPages > 1) next.set('page', totalPages - 1);
                    setParams(next, { replace: true });
                    return;
                }
                setLoaded({ content, page: data.page ?? page, totalPages,
                    totalElements: data.totalElements ?? content.length, key: requestKey });
            })
            .catch((err) => {
                if (active) setLoaded({ ...EMPTY_PAGE, key: requestKey, error: err.message || 'We couldn’t load notifications.' });
            });

        return () => { active = false; };
    }, [page, unreadOnly, requestKey, setParams]);

    const changePage = (nextPage) => {
        setParams(unreadOnly
            ? { view: 'unread', ...(nextPage > 0 ? { page: nextPage } : {}) }
            : nextPage > 0 ? { page: nextPage } : {});
    };

    const reload = () => setLoadAttempt((attempt) => attempt + 1);

    const markRead = async (item) => {
        if (item.read === true || readRequests.current.has(item.id)) return;
        setReadErrors((errors) => ({ ...errors, [item.id]: false }));
        readRequests.current.add(item.id);
        try {
            await notificationService.markRead(item.id);
            setLoaded((previous) => previous?.key === requestKey ? {
                ...previous,
                content: previous.content.map((entry) => entry.id === item.id ? { ...entry, read: true } : entry),
            } : previous);
            refreshUnreadCount?.();
            if (unreadOnly && closedInbox.current === location.key) reload();
        } catch {
            setReadErrors((errors) => ({ ...errors, [item.id]: true }));
        } finally {
            readRequests.current.delete(item.id);
        }
    };

    const unread = result.content.filter((item) => item.read !== true).length;
    const visibleNotifications = unreadOnly ? result.content.filter((item) => !item.read || item.id === openId) : result.content;

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
                <div className="inbox-filters" aria-label="Filter notifications" role="group">
                    <button type="button" className="student-button" aria-pressed={!unreadOnly}
                        onClick={() => { if (unreadOnly) setParams({}); }}>All notifications</button>
                    <button type="button" className="student-button" aria-pressed={unreadOnly}
                        onClick={() => { if (!unreadOnly) setParams({ view: 'unread' }); }}>Unread only</button>
                </div>
                {loadError && (
                    <div className="student-notice student-notice--error" role="alert">
                        <p>{loadError}</p>
                        <button type="button" className="student-text-link" onClick={reload}>Try again</button>
                    </div>
                )}
                {loading ? (
                    <p className="student-notice" role="status">Loading notifications…</p>
                ) : visibleNotifications.length ? (
                    <ul className="inbox-list">
                        {visibleNotifications.map((item) => {
                            const expanded = item.id === openId;
                            const unreadItem = item.read !== true;
                            return (
                                <li key={item.id} className="inbox-item" data-unread={unreadItem}>
                                    <h3>
                                        <button type="button" className="inbox-trigger" id={`notice-button-${item.id}`}
                                            aria-expanded={expanded} aria-controls={`notice-content-${item.id}`}
                                            onClick={() => {
                                                if (expanded) {
                                                    closedInbox.current = location.key;
                                                    setOpenNotice(null);
                                                    if (unreadOnly && result.content.some((entry) => entry.read)) reload();
                                                } else {
                                                    closedInbox.current = null;
                                                    setOpenNotice({ id: item.id, key: location.key });
                                                    markRead(item);
                                                }
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
                ) : !loadError && <p className="student-notice">{unreadOnly ? 'No unread notifications.' : 'No notifications yet.'}</p>}
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
