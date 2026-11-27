import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import DOMPurify from 'dompurify';
import { UnreadNotificationsContext } from '../context/UnreadNotificationsContext';
import { useReadCache, useReadQuery } from '../context/readQueries';
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
    const cache = useReadCache();
    const read = useCallback(() => notificationService.getMyNotifications(page, PAGE_SIZE, unreadOnly), [page, unreadOnly]);
    const { data, loading, error, retry } = useReadQuery('notifications', [page, PAGE_SIZE, unreadOnly], read);
    const result = data ?? EMPTY_PAGE;
    const loadError = error && (error.message || 'We couldn’t load notifications.');
    const [openNotice, setOpenNotice] = useState(null);
    const openItem = openNotice?.key === location.key ? openNotice.item : null;
    const openId = openItem?.id;
    const [readErrors, setReadErrors] = useState({});
    const readRequests = useRef(new Set());

    useEffect(() => {
        if (!data || openItem || !unreadOnly || page === 0 || page < data.totalPages) return;
        const next = new URLSearchParams({ view: 'unread' });
        if (data.totalPages > 1) next.set('page', data.totalPages - 1);
        setParams(next, { replace: true });
    }, [data, openItem, page, unreadOnly, setParams]);

    const changePage = (nextPage) => {
        setParams(unreadOnly
            ? { view: 'unread', ...(nextPage > 0 ? { page: nextPage } : {}) }
            : nextPage > 0 ? { page: nextPage } : {});
    };

    const markRead = async (item) => {
        if (item.read === true || readRequests.current.has(item.id)) return;
        setReadErrors((errors) => ({ ...errors, [item.id]: false }));
        readRequests.current.add(item.id);
        try {
            await notificationService.markRead(item.id);
            setOpenNotice((previous) => previous?.key === location.key && previous.item.id === item.id ? {
                ...previous,
                item: { ...item, read: true },
            } : previous);
            cache.invalidate('notifications');
            refreshUnreadCount?.();
        } catch {
            setReadErrors((errors) => ({ ...errors, [item.id]: true }));
        } finally {
            readRequests.current.delete(item.id);
        }
    };

    const items = result.content.map((item) => item.id === openId && openItem.read ? { ...item, read: true } : item);
    const unread = items.filter((item) => item.read !== true).length;
    const visibleNotifications = unreadOnly ? items.filter((item) => !item.read || item.id === openId) : items;
    if (openItem && !visibleNotifications.some((item) => item.id === openId)) visibleNotifications.unshift(openItem);

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
                        <button type="button" className="student-text-link" onClick={retry}>Try again</button>
                    </div>
                )}
                {loading && !visibleNotifications.length ? (
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
                                                    setOpenNotice(null);
                                                } else {
                                                    setOpenNotice({ item, key: location.key });
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
