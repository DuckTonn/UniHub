import { useCallback, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import axiosClient from '../api/axiosClient';
import { useReadQuery } from '../context/readQueries';
import DeferredContent from '../components/common/DeferredContent';
import OperationError from '../components/common/OperationError';
import PaginationControl from '../components/common/PaginationControl';

const PAGE_SIZE = 12;
const statuses = { NEEDS_REVIEW: 'Needs review', ALL: 'All transactions', PROCESSING: 'Processing',
    FAILED: 'Failed', REFUND_REQUIRED: 'Refund required', COMPLETED: 'Completed', CANCELLED: 'Cancelled' };
const currency = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
const refundStatuses = { PENDING: 'Queued', PROCESSING: 'Processing', SUCCEEDED: 'Confirmed', FAILED: 'Failed' };

export default function AdminPaymentsPage() {
    const [params, setParams] = useSearchParams();
    const status = Object.hasOwn(statuses, params.get('status')) ? params.get('status') : 'NEEDS_REVIEW';
    const workshopId = params.get('workshopId') || '';
    const requestedPage = Number(params.get('page') || 0);
    const page = Number.isInteger(requestedPage) && requestedPage >= 0 && requestedPage <= 1000000 ? requestedPage : 0;
    const [entry, setEntry] = useState(null);
    const [confirmation, setConfirmation] = useState(null);
    const [submitting, setSubmitting] = useState(false);
    const [refundError, setRefundError] = useState('');
    const [submitted, setSubmitted] = useState(null);
    const search = entry?.filter === workshopId ? entry.value : workshopId;
    const read = useCallback(() => axiosClient.get('/admin/payments', {
        params: { workshopId: workshopId || undefined, status, page, size: PAGE_SIZE },
    }), [workshopId, status, page]);
    const { data, loading, error, retry } = useReadQuery('paymentReview', [workshopId, status, page], read);

    async function submitRefund() {
        setSubmitting(true);
        setRefundError('');
        try {
            const { data: refund } = await axiosClient.post(`/admin/payments/${confirmation.paymentId}/refunds`, null, {
                headers: { 'Idempotency-Key': confirmation.key },
            });
            setSubmitted({ ...refund, paymentId: confirmation.paymentId });
            setConfirmation(null);
            retry();
        } catch (error) {
            setRefundError(error.response?.status === 409
                ? 'This payment cannot be refunded with this request. Refresh to review its current status.'
                : 'Couldn’t verify the refund request. Retry to check the same request.');
        } finally {
            setSubmitting(false);
        }
    }

    const change = (name, value) => setParams((current) => {
        const next = new URLSearchParams(current);
        if (name !== 'page') next.delete('page');
        if (value) next.set(name, value);
        else next.delete(name);
        return next;
    });

    return (
        <section className="mx-auto max-w-5xl space-y-4 p-4 sm:p-6" aria-labelledby="payment-heading">
            <Link to="/admin" className="text-sm font-semibold text-indigo-700">Back to dashboard</Link>
            <h1 id="payment-heading" className="text-3xl font-bold">Payment reconciliation</h1>
            <p className="text-gray-600">Match transactions against provider records before resolving outstanding payments or refunds.</p>
            <form className="flex flex-wrap items-end gap-3 rounded-xl border border-gray-200 bg-white p-4" onSubmit={(event) => {
                event.preventDefault();
                change('workshopId', search.trim());
            }}>
                <label className="grid gap-1 text-sm font-semibold">
                    Transaction status
                    <select className="rounded-lg border border-gray-300 p-2" value={status}
                        onChange={(event) => change('status', event.target.value)}>
                        {Object.entries(statuses).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                    </select>
                </label>
                <label className="grid gap-1 text-sm font-semibold">
                    Workshop ID
                    <input className="max-w-40 rounded-lg border border-gray-300 p-2" type="text" inputMode="numeric"
                        pattern="[1-9][0-9]*" maxLength={18} value={search}
                        onChange={(event) => setEntry({ filter: workshopId, value: event.target.value })} />
                </label>
                <button type="submit" className="rounded-lg bg-indigo-700 px-4 py-2 text-white">Apply</button>
                <button type="button" onClick={retry} className="rounded-lg border border-indigo-300 px-4 py-2 text-indigo-700">Refresh</button>
            </form>
            {submitted && (
                <p role="status" className="rounded-lg bg-indigo-50 p-3 text-indigo-900">
                    Sandbox refund for payment #{submitted.paymentId}: {refundStatuses[submitted.status]}.
                    {' '}<button className="font-semibold underline" type="button" onClick={() => {
                        change('status', 'ALL');
                        retry();
                    }}>View all transactions</button>
                </p>
            )}
            {loading && <DeferredContent />}
            <OperationError error={error} message="Couldn’t load transactions." onRetry={retry} />
            {data?.content.length === 0 && <p>No transactions match these filters.</p>}
            <ul className="space-y-3">
                {data?.content.map((payment) => (
                    <li key={payment.paymentId} className="rounded-xl border border-gray-200 bg-white p-4">
                        <div className="flex flex-wrap items-baseline justify-between gap-2">
                            <h2 className="font-bold">{payment.workshopTitle}</h2>
                            <p className="rounded-full bg-indigo-50 px-3 py-1 text-sm font-semibold text-indigo-900">{statuses[payment.status] ?? payment.status}</p>
                        </div>
                        <p className="text-sm text-gray-600">Payment #{payment.paymentId} · Registration #{payment.registrationId} · {currency.format(payment.amount)}</p>
                        {payment.transactionReference && <p className="break-all text-sm">Provider reference <code>{payment.transactionReference}</code></p>}
                        {payment.issue && <p className="mt-2 text-sm font-medium text-amber-900">{payment.issue}</p>}
                        {payment.refundStatus && (
                            <p className="mt-2 text-sm font-medium">
                                Sandbox refund: {refundStatuses[payment.refundStatus]}
                                {payment.refundFailureCode && ' · Review required'}
                                {payment.refundReference && <> · <code className="break-all">{payment.refundReference}</code></>}
                            </p>
                        )}
                        {payment.sandboxRefundAvailable && confirmation?.paymentId !== payment.paymentId && (
                            <button type="button" disabled={Boolean(confirmation)}
                                className="mt-3 rounded-lg border border-indigo-300 px-4 py-2 font-semibold text-indigo-700 disabled:opacity-50"
                                onClick={() => {
                                    const storageKey = `unihub:sandbox-refund:${payment.paymentId}`;
                                    const key = sessionStorage.getItem(storageKey) || crypto.randomUUID();
                                    sessionStorage.setItem(storageKey, key);
                                    setRefundError('');
                                    setConfirmation({ paymentId: payment.paymentId, key });
                                }}>
                                Review sandbox refund
                            </button>
                        )}
                        {confirmation?.paymentId === payment.paymentId && (
                            <form className="mt-3 grid gap-3 rounded-lg border border-indigo-200 bg-indigo-50 p-3"
                                onSubmit={(event) => { event.preventDefault(); void submitRefund(); }}>
                                <p>Confirm a sandbox refund of <strong>{currency.format(payment.amount)}</strong> for {payment.workshopTitle}.</p>
                                {refundError && <p role="alert" className="text-sm font-semibold text-red-800">{refundError}</p>}
                                <div className="flex flex-wrap gap-2">
                                    <button type="submit" disabled={submitting}
                                        className="rounded-lg bg-indigo-700 px-4 py-2 font-semibold text-white disabled:opacity-50">
                                        {submitting ? 'Submitting…' : 'Confirm sandbox refund'}
                                    </button>
                                    <button type="button" disabled={submitting} className="px-4 py-2"
                                        onClick={() => setConfirmation(null)}>Cancel</button>
                                </div>
                            </form>
                        )}
                    </li>
                ))}
            </ul>
            {data && <PaginationControl currentPage={data.page} totalPages={data.totalPages} totalElements={data.totalElements}
                pageSize={PAGE_SIZE} onPageChange={(next) => change('page', next)} itemLabel="transactions" />}
        </section>
    );
}
