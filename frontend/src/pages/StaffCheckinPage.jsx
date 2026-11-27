import { useCallback, useRef, useState } from 'react';
import axiosClient from '../api/axiosClient';
import workshopService from '../services/workshopService';
import { useReadQuery } from '../context/readQueries';
import OperationError from '../components/common/OperationError';
import PaginationControl from '../components/common/PaginationControl';

const PAGE_SIZE = 12;
const campusTime = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});

function scannedAt() {
    const time = Object.fromEntries(campusTime.formatToParts(new Date()).map(({ type, value }) => [type, value]));
    return `${time.year}-${time.month}-${time.day}T${time.hour}:${time.minute}:${time.second}`;
}

export default function StaffCheckinPage() {
    const [search, setSearch] = useState('');
    const [query, setQuery] = useState({ keyword: '', page: 0 });
    const [workshop, setWorkshop] = useState(null);
    const [code, setCode] = useState('');
    const [result, setResult] = useState(null);
    const [error, setError] = useState(null);
    const [busy, setBusy] = useState(false);
    const submitting = useRef(false);
    const input = useRef(null);
    const read = useCallback(() => workshopService.getAll(query.page, PAGE_SIZE, { q: query.keyword }), [query]);
    const catalog = useReadQuery('checkinWorkshops', [query.page, query.keyword], read);

    const submit = async (event) => {
        event.preventDefault();
        const qrCode = code.trim();
        if (!workshop || !qrCode || submitting.current) return;
        submitting.current = true;
        setBusy(true);
        setError(null);
        setResult(null);
        try {
            const saved = await axiosClient.post('/checkins/sync', { workshopId: workshop.id, records: [{ qrCode, scannedAt: scannedAt() }] });
            setResult(saved.successCount === 1 ? 'Check-in recorded.' : saved.duplicateCount === 1
                ? 'This ticket was already checked in.' : 'Ticket rejected. Check the workshop and scan the ticket again.');
            setCode('');
        } catch (failure) {
            setError(failure);
        } finally {
            submitting.current = false;
            setBusy(false);
            input.current?.focus();
        }
    };

    return (
        <section className="mx-auto max-w-3xl space-y-4 p-4 sm:p-6" aria-labelledby="checkin-heading">
            <h1 id="checkin-heading" className="text-3xl font-bold">Workshop check-in</h1>
            <form className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
                event.preventDefault();
                setQuery({ keyword: search.trim(), page: 0 });
            }}>
                <label className="grid gap-1 text-sm font-semibold">Find a published workshop
                    <input type="search" value={search} maxLength={120} onChange={(event) => setSearch(event.target.value)}
                        className="rounded-lg border border-gray-300 bg-white p-2" />
                </label>
                <button className="rounded-lg bg-indigo-700 px-4 py-2 text-white">Search</button>
            </form>
            {catalog.loading && <p role="status">Loading workshops…</p>}
            <OperationError error={catalog.error} onRetry={catalog.retry} />
            {catalog.data?.content.length === 0 && <p>No published workshops match this search.</p>}
            <ul className="grid gap-2 sm:grid-cols-2">
                {catalog.data?.content.map((item) => (
                    <li key={item.id}>
                        <button type="button" aria-pressed={workshop?.id === item.id} disabled={busy}
                            className="w-full rounded-lg border border-gray-300 bg-white p-3 text-left aria-pressed:border-indigo-500 aria-pressed:bg-indigo-50"
                            onClick={() => {
                                setWorkshop(item);
                                setCode('');
                                setError(null);
                                setResult(null);
                                window.requestAnimationFrame(() => input.current?.focus());
                            }}>
                            {item.title} <span className="text-sm text-gray-600">#{item.id}</span>
                        </button>
                    </li>
                ))}
            </ul>
            {catalog.data && <PaginationControl currentPage={catalog.data.page} totalPages={catalog.data.totalPages}
                totalElements={catalog.data.totalElements} pageSize={PAGE_SIZE}
                onPageChange={(page) => setQuery((current) => ({ ...current, page }))} itemLabel="workshops" />}
            <form onSubmit={submit} className="space-y-3 rounded-xl border border-gray-200 bg-white p-4">
                <h2 className="font-bold">{workshop ? `Check in to ${workshop.title}` : 'Select a workshop to check in'}</h2>
                <label className="grid gap-1 text-sm font-semibold">Ticket QR code
                    <input ref={input} type="text" required autoComplete="off" spellCheck={false} maxLength={255}
                        disabled={!workshop} readOnly={busy} value={code} onChange={(event) => setCode(event.target.value)}
                        className="rounded-lg border border-gray-300 p-2" />
                </label>
                <button type="submit" disabled={!workshop || busy} className="rounded-lg bg-indigo-700 px-4 py-2 text-white disabled:opacity-50">
                    {busy ? 'Submitting…' : 'Check in'}
                </button>
                <OperationError error={error} message="Couldn’t submit the check-in. Try again." />
                {result && <p role="status">{result}</p>}
            </form>
        </section>
    );
}
