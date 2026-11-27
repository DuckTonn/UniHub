import { lazy, Suspense, useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import adminWorkshopService from '../services/adminWorkshopService';
import { useAuth } from '../context/AuthContext';
import { useReadCache, useReadQuery } from '../context/readQueries';
import DeferredContent from '../components/common/DeferredContent';
import PaginationControl from '../components/common/PaginationControl';
import toast from 'react-hot-toast';

const WorkshopFormModal = lazy(() => import('../components/workshops/WorkshopFormModal'));
const AttendanceModal = lazy(() => import('../components/workshops/AttendanceModal'));
const CreateAccountModal = lazy(() => import('../components/common/CreateAccountModal'));
const SeatDistributionChart = lazy(() => import('../components/workshops/SeatDistributionChart'));
const PAGE_SIZE = 12;

const formatDateTime = (dt) => {
    if (!dt) return '—';
    const d = new Date(dt);
    return d.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' })
        + ' ' + d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
};

const formatPrice = (price) => {
    if (!price || Number(price) === 0) return 'Free';
    return Number(price).toLocaleString('vi-VN') + 'đ';
};

const statusBadge = (status) => {
    const map = {
        DRAFT: 'bg-gray-100 text-gray-700',
        PUBLISHED: 'bg-green-100 text-green-700',
        CANCELLED: 'bg-red-100 text-red-700',
        COMPLETED: 'bg-blue-100 text-blue-700',
    };
    return (
        <span className={`inline-block rounded-full px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider ${map[status] || 'bg-gray-100 text-gray-600'}`}>
            {status || '—'}
        </span>
    );
};


const Dashboard = () => {
    const { user } = useAuth();
    const cache = useReadCache();
    const navigate = useNavigate();

    const [page, setPage] = useState(0);
    const read = useCallback(() => adminWorkshopService.getAll(page, PAGE_SIZE), [page]);
    const { data, loading, error: readError, retry } = useReadQuery('adminWorkshops', [page, PAGE_SIZE], read);
    const workshops = data?.content ?? data ?? [];
    const currentPage = data?.page ?? page;
    const totalPages = data?.totalPages ?? (workshops.length ? 1 : 0);
    const totalElements = data?.totalElements ?? workshops.length;
    const [error, setError] = useState('');
    const [modalOpen, setModalOpen] = useState(false);
    const [editingWorkshop, setEditingWorkshop] = useState(null);
    const [submitting, setSubmitting] = useState(false);
    const [deleteConfirm, setDeleteConfirm] = useState(null);
    const [cancelConfirm, setCancelConfirm] = useState(null);
    const [statsData, setStatsData] = useState(null);

    // AI Summary State
    const [aiUploadOpen, setAiUploadOpen] = useState(false);
    const [aiWorkshop, setAiWorkshop] = useState(null);
    const [aiFile, setAiFile] = useState(null);
    const [aiUploading, setAiUploading] = useState(false);
    const [aiStatus, setAiStatus] = useState(null);
    const [aiError, setAiError] = useState('');
    const [openActionId, setOpenActionId] = useState(null);
    const [attendanceWorkshop, setAttendanceWorkshop] = useState(null);
    const [createAccountOpen, setCreateAccountOpen] = useState(false);

    // Close dropdown when clicking outside
    useEffect(() => {
        const handleClickOutside = () => setOpenActionId(null);
        window.addEventListener('click', handleClickOutside);
        return () => window.removeEventListener('click', handleClickOutside);
    }, []);

    const handleCreate = () => {
        setEditingWorkshop(null);
        setModalOpen(true);
    };

    const handleEdit = (workshop) => {
        setEditingWorkshop(workshop);
        setModalOpen(true);
    };

    const handleSubmit = async (payload) => {
        try {
            setSubmitting(true);
            if (editingWorkshop) {
                await adminWorkshopService.update(editingWorkshop.id, payload);
                toast.success('Workshop updated successfully!');
            } else {
                await adminWorkshopService.create(payload);
                toast.success('Workshop created successfully!');
            }
            cache.invalidate('adminWorkshops', 'statistics', 'workshops');
            setModalOpen(false);
            setError('');
        } catch (err) {
            toast.error(err.message || 'Failed to save workshop. Please check your input.');
        } finally {
            setSubmitting(false);
        }
    };

    const handleDelete = async (id) => {
        try {
            await adminWorkshopService.delete(id);
            cache.invalidate('adminWorkshops', 'statistics', 'workshops');
            setDeleteConfirm(null);
            // If we deleted the last item on this page, go back one page
            const nextPage = workshops.length === 1 && currentPage > 0 ? currentPage - 1 : currentPage;
            setPage(nextPage);
            setError('');
        } catch (err) {
            setError(err.message);
            setDeleteConfirm(null);
        }
    };

    const handleCancel = async (id) => {
        try {
            await adminWorkshopService.cancel(id);
            cache.invalidate('adminWorkshops', 'statistics', 'workshops', 'registrations', 'notifications');
            setCancelConfirm(null);
            setError('');
        } catch (err) {
            setError(err.message);
            setCancelConfirm(null);
        }
    };

    const handlePublish = async (id) => {
        try {
            await adminWorkshopService.publish(id);
            cache.invalidate('adminWorkshops', 'statistics', 'workshops');
            setError('');
        } catch (err) {
            setError(err.message);
        }
    };

    const handleViewStats = async (id) => {
        try {
            const data = await adminWorkshopService.getStats(id);
            setStatsData(data);
        } catch (err) {
            setError(err.message);
        }
    };

    const handleViewAttendances = (workshop) => {
        setAttendanceWorkshop(workshop);
    };

    useEffect(() => {
        if (!aiUploadOpen || !aiWorkshop || aiUploading) return undefined;

        let cancelled = false;
        let timer;
        const poll = async () => {
            try {
                const latest = await adminWorkshopService.getAiSummaryStatus(aiWorkshop.id);
                if (cancelled) return;
                setAiStatus(latest);
                if (latest.status === 'COMPLETED') {
                    cache.invalidate('adminWorkshops', 'workshops');
                } else if (latest.status === 'QUEUED' || latest.status === 'PROCESSING') {
                    timer = setTimeout(poll, 3000);
                }
            } catch {
                if (!cancelled) timer = setTimeout(poll, 5000);
            }
        };
        poll();
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [aiUploadOpen, aiWorkshop, aiUploading, cache]);

    const handleOpenAiUpload = (workshop) => {
        setAiWorkshop(workshop);
        setAiFile(null);
        setAiStatus(null);
        setAiError('');
        setAiUploadOpen(true);
    };

    const handleAiUploadSubmit = async () => {
        if (!aiFile) return;
        try {
            setAiUploading(true);
            setAiError('');
            await adminWorkshopService.uploadAiSummary(aiWorkshop.id, aiFile);
            setAiStatus({ status: 'QUEUED' });
        } catch (err) {
            setAiError(err.message || 'Upload failed');
        } finally {
            setAiUploading(false);
        }
    };

    return (
        <>
            <div className="min-h-screen bg-gray-50 p-6 mx-auto max-w-7xl">
                {/* Dashboard Header */}
                <div className="mb-6 flex items-center justify-between">
                    <div>
                        <h1 className="text-3xl font-bold text-gray-900">Admin Dashboard</h1>
                        <p className="mt-1 text-gray-500">
                            Logged in as <span className="font-medium text-indigo-600">{user?.fullName ?? 'Admin'}</span>
                        </p>
                    </div>
                    <div className="flex items-center gap-3">
                        {user?.role === 'ADMIN' && (
                            <button
                                onClick={() => setCreateAccountOpen(true)}
                                className="flex items-center gap-2 rounded-xl border-2 border-violet-200 bg-white px-4 py-2.5 text-sm font-semibold text-violet-700 shadow-sm hover:border-violet-400 hover:bg-violet-50 transition-all"
                            >
                                <span className="text-base leading-none">👤</span>
                                Create Account
                            </button>
                        )}
                        <button
                            onClick={() => navigate('/admin/rooms')}
                            className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-md hover:from-violet-700 hover:to-indigo-700 transition-all"
                        >
                            <span>🏛️</span>
                            Manage Rooms
                        </button>
                        <button
                            onClick={() => navigate('/admin/statistics')}
                            className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-md hover:from-violet-700 hover:to-indigo-700 transition-all"
                        >
                            <span>📊</span>
                            View Analytics
                        </button>
                    </div>
                </div>

                {/* Workshop Management Section Header */}
                <div className="mb-6 flex items-center justify-between">
                    <div>
                        <h2 className="text-2xl font-bold text-gray-900">Workshop Management</h2>
                        <p className="mt-1 text-sm text-gray-500">
                            {totalElements} workshop{totalElements !== 1 ? 's' : ''} total
                            {totalPages > 1 && ` · Page ${currentPage + 1} of ${totalPages}`}
                        </p>
                    </div>
                    <button
                        onClick={handleCreate}
                        className="flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 transition"
                    >
                        <span className="text-lg leading-none">+</span>
                        Create Workshop
                    </button>
                </div>

                {/* Error Banner */}
                {error && (
                    <div className="mb-4 flex items-center justify-between rounded-lg bg-red-50 p-3 text-sm text-red-700" role="alert">
                        <span>{error}</span>
                        <button onClick={() => setError('')} className="font-medium hover:text-red-900">✕</button>
                    </div>
                )}

                {/* Workshop Table */}
                {loading ? <DeferredContent /> : readError ? (
                    <div className="mb-4 rounded-lg bg-red-50 p-3 text-sm text-red-700" role="alert">
                        <p>We couldn’t load workshops.</p>
                        <button type="button" className="mt-2 font-semibold underline" onClick={retry}>Try again</button>
                    </div>
                ) : workshops.length === 0 ? (
                    <div className="rounded-2xl bg-white p-12 text-center shadow-sm">
                        <p className="text-gray-400">No workshops yet. Click "Create Workshop" to get started.</p>
                    </div>
                ) : (
                    <div className="overflow-hidden rounded-2xl bg-white shadow-sm border border-gray-100">
                        <table className="w-full text-sm text-left">
                            <thead>
                                <tr className="border-b bg-gray-50/50 text-[11px] uppercase tracking-wider text-gray-400">
                                    <th className="px-6 py-4 font-bold text-left">Workshop</th>
                                    <th className="px-6 py-4 font-bold text-center">Status</th>
                                    <th className="px-6 py-4 font-bold text-left">Event Time</th>
                                    <th className="px-6 py-4 font-bold text-left">Registration</th>
                                    <th className="px-6 py-4 font-bold text-center">Slots</th>
                                    <th className="px-6 py-4 font-bold text-right">Price</th>
                                    <th className="px-6 py-4 font-bold text-center">Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {workshops.map((w) => (
                                    <tr key={w.id} className="border-b last:border-0 hover:bg-gray-50/30 transition">
                                        <td className="px-6 py-3 text-left">
                                            <div className="font-bold text-gray-900 text-[13px]">{w.title}</div>
                                            <div className="mt-1 text-[11px] text-gray-500">
                                                {w.speaker || '—'} · {w.roomName || '—'}
                                            </div>
                                            {w.description && (
                                                <div className="mt-1 text-[11px] text-gray-400 line-clamp-1 max-w-[500px]" title={w.description}>
                                                    {w.description}
                                                </div>
                                            )}
                                        </td>
                                        <td className="px-6 py-3 text-center align-middle">{statusBadge(w.status)}</td>
                                        <td className="px-6 py-3 text-left align-middle text-[11px] text-gray-600">
                                            <div>{formatDateTime(w.startTime)}</div>
                                            <div className="text-gray-400">→ {formatDateTime(w.endTime)}</div>
                                        </td>
                                        <td className="px-6 py-3 text-left align-middle text-[11px] text-gray-600">
                                            <div>{formatDateTime(w.registrationStartTime)}</div>
                                            <div className="text-gray-400">→ {formatDateTime(w.registrationEndTime)}</div>
                                        </td>
                                        <td className="px-6 py-3 text-center align-middle">
                                            <span className={`inline-block rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${w.remainingSlots === 0
                                                ? 'bg-red-100 text-red-700'
                                                : w.remainingSlots <= w.totalSlots * 0.2
                                                    ? 'bg-amber-100 text-amber-700'
                                                    : 'bg-green-100 text-green-700'
                                                }`}>
                                                {w.remainingSlots}/{w.totalSlots}
                                            </span>
                                        </td>
                                        <td className="px-6 py-3 text-right align-middle text-[13px] font-medium text-gray-700">{formatPrice(w.price)}</td>
                                        <td className="px-6 py-3 text-center align-middle">
                                            <div className="relative inline-block text-left" onClick={(e) => e.stopPropagation()}>
                                                <button
                                                    onClick={() => setOpenActionId(openActionId === w.id ? null : w.id)}
                                                    className={`flex items-center justify-center w-8 h-8 rounded-full transition-all duration-200 ${openActionId === w.id ? 'bg-gray-100 text-gray-900 shadow-inner' : 'text-gray-400 hover:bg-gray-50 hover:text-gray-600'}`}
                                                >
                                                    <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 5v.01M12 12v.01M12 19v.01M12 6a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2z" />
                                                    </svg>
                                                </button>

                                                {openActionId === w.id && (
                                                    <div className={`absolute right-0 z-50 w-30 rounded-md border border-gray-200 bg-white py-1 shadow-lg ${workshops.indexOf(w) >= workshops.length - 2 && workshops.length > 0 ? 'bottom-full mb-1' : 'mt-1'}`}>
                                                        <button onClick={() => { handleViewAttendances(w); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] text-gray-700 hover:bg-gray-50 transition">
                                                            View Attendances
                                                        </button>
                                                        <button onClick={() => { handleViewStats(w.id); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] text-gray-700 hover:bg-gray-50 transition">
                                                            View Statistics
                                                        </button>
                                                        <button onClick={() => { handleOpenAiUpload(w); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] text-gray-700 hover:bg-gray-50 transition">
                                                            AI Summarize
                                                        </button>
                                                        {w.status !== 'CANCELLED' && w.status !== 'COMPLETED' && (
                                                            <button onClick={() => { handleEdit(w); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] text-gray-700 hover:bg-gray-50 transition">
                                                                Edit Details
                                                            </button>
                                                        )}
                                                        {w.status === 'DRAFT' && (
                                                            <button onClick={() => { handlePublish(w.id); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] font-semibold text-gray-900 hover:bg-gray-50 transition">
                                                                Publish Now
                                                            </button>
                                                        )}
                                                        {w.status === 'PUBLISHED' && (
                                                            <button onClick={() => { setCancelConfirm(w.id); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] text-red-500 hover:bg-red-50 transition">
                                                                Cancel Workshop
                                                            </button>
                                                        )}
                                                        {w.status === 'DRAFT' && (
                                                            <button onClick={() => { setDeleteConfirm(w.id); setOpenActionId(null); }} className="w-full px-3 py-1.5 text-left text-[11px] text-red-600 hover:bg-red-50 transition">
                                                                Delete
                                                            </button>
                                                        )}
                                                    </div>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}

                {/* Pagination Controls */}
                <PaginationControl
                    currentPage={currentPage}
                    totalPages={totalPages}
                    totalElements={totalElements}
                    pageSize={PAGE_SIZE}
                    onPageChange={(nextPage) => {
                        setError('');
                        setPage(nextPage);
                    }}
                    itemLabel="workshops"
                />

                {/* Create/Edit Modal */}
                <Suspense fallback={<DeferredContent />}>
                    {modalOpen && (
                        <WorkshopFormModal isOpen onClose={() => setModalOpen(false)} onSubmit={handleSubmit}
                            initialData={editingWorkshop} isLoading={submitting} />
                    )}
                </Suspense>

                {/* Delete Confirmation */}
                {deleteConfirm && (
                    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
                        <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-xl">
                            <h3 className="text-lg font-bold text-gray-900">Delete Workshop?</h3>
                            <p className="mt-2 text-sm text-gray-500">
                                This action cannot be undone. Only DRAFT workshops can be deleted.
                            </p>
                            <div className="mt-5 flex justify-end gap-3">
                                <button
                                    onClick={() => setDeleteConfirm(null)}
                                    className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition"
                                >
                                    Cancel
                                </button>
                                <button
                                    onClick={() => handleDelete(deleteConfirm)}
                                    className="rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 transition"
                                >
                                    Delete
                                </button>
                            </div>
                        </div>
                    </div>
                )}

                {/* Stats Modal */}
                {statsData && (() => {
                    const fillRate = statsData.fillRate ?? 0;
                    const chartData = [
                        { name: 'Registered', value: Number(statsData.registeredCount) || 0, fill: '#6366f1' },
                        { name: 'Available', value: Math.max(0, statsData.totalSlots - (Number(statsData.registeredCount) || 0)), fill: '#e5e7eb' },
                    ];
                    return (
                        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setStatsData(null)}>
                            <div className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
                                <h3 className="text-lg font-bold text-gray-900 mb-1">Workshop Statistics</h3>
                                <p className="text-sm text-gray-500 mb-5 line-clamp-1">{statsData.title}</p>

                                {/* Metric grid */}
                                <div className="grid grid-cols-2 gap-3 mb-5">
                                    <div className="rounded-xl bg-indigo-50 p-4 text-center">
                                        <p className="text-xs font-medium text-indigo-500 uppercase">Total Slots</p>
                                        <p className="mt-1 text-2xl font-bold text-indigo-700">{statsData.totalSlots}</p>
                                    </div>
                                    <div className="rounded-xl bg-emerald-50 p-4 text-center">
                                        <p className="text-xs font-medium text-emerald-500 uppercase">Remaining</p>
                                        <p className="mt-1 text-2xl font-bold text-emerald-700">{statsData.remainingSlots}</p>
                                    </div>
                                    <div className="rounded-xl bg-violet-50 p-4 text-center">
                                        <p className="text-xs font-medium text-violet-500 uppercase">Registered</p>
                                        <p className="mt-1 text-2xl font-bold text-violet-700">{statsData.registeredCount}</p>
                                    </div>
                                    <div className="rounded-xl bg-amber-50 p-4 text-center">
                                        <p className="text-xs font-medium text-amber-500 uppercase">Fill Rate</p>
                                        <p className="mt-1 text-2xl font-bold text-amber-700">{fillRate}%</p>
                                    </div>
                                </div>

                                {/* Fill rate progress bar */}
                                <div className="mb-4">
                                    <div className="flex justify-between text-xs text-gray-500 mb-1">
                                        <span>Fill Rate</span><span>{fillRate}%</span>
                                    </div>
                                    <div className="h-3 w-full rounded-full bg-gray-200 overflow-hidden">
                                        <div
                                            className={`h-full rounded-full transition-all duration-500 ${fillRate >= 90 ? 'bg-gradient-to-r from-red-400 to-red-600' :
                                                fillRate >= 60 ? 'bg-gradient-to-r from-amber-400 to-orange-500' :
                                                    'bg-gradient-to-r from-emerald-400 to-emerald-600'
                                                }`}
                                            style={{ width: `${Math.min(fillRate, 100)}%` }}
                                        />
                                    </div>
                                </div>

                                {/* Bar chart: registered vs remaining */}
                                <div className="mb-4">
                                    <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2">Seat Distribution</p>
                                    <div className="h-20">
                                        <Suspense fallback={<DeferredContent />}>
                                            <SeatDistributionChart data={chartData} />
                                        </Suspense>
                                    </div>
                                </div>

                                <div className="flex justify-end">
                                    <button
                                        onClick={() => setStatsData(null)}
                                        className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition"
                                    >
                                        Close
                                    </button>
                                </div>
                            </div>
                        </div>
                    );
                })()}

                {/* AI Summary Upload Modal */}
                {aiUploadOpen && aiWorkshop && (
                    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
                        <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl">
                            <h3 className="text-lg font-bold text-gray-900 mb-2">Upload PDF for AI Summary</h3>
                            <p className="text-sm text-gray-500 mb-4">{aiWorkshop.title}</p>

                            <div aria-live="polite" className="mb-3 text-sm text-gray-700">
                                {aiStatus?.status === 'QUEUED' && 'Document queued'}
                                {aiStatus?.status === 'PROCESSING' && 'Generating summary…'}
                                {aiStatus?.status === 'COMPLETED' && 'Document processed'}
                                {aiStatus?.status === 'FAILED' && <span className="text-red-700">{aiStatus.error}</span>}
                            </div>
                            {aiError && <p role="alert" className="mb-3 text-sm text-red-700">{aiError}</p>}
                            <label className="block text-sm font-medium text-gray-700 mb-2" htmlFor="ai-document">
                                Select PDF file (maximum 10 MiB)
                            </label>
                            <input id="ai-document" type="file" accept="application/pdf" disabled={aiUploading}
                                onChange={event => setAiFile(event.target.files[0] ?? null)}
                                className="block w-full text-sm text-gray-500 file:mr-4 file:rounded-full file:border-0
                                    file:bg-fuchsia-50 file:px-4 file:py-2 file:font-semibold file:text-fuchsia-700"
                            />

                            <div className="mt-5 flex justify-end gap-3">
                                <button
                                    onClick={() => {
                                        setAiUploadOpen(false);
                                        setAiFile(null);
                                    }}
                                    disabled={aiUploading}
                                    className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition disabled:opacity-50"
                                >
                                    Close
                                </button>
                                <button
                                    onClick={handleAiUploadSubmit}
                                    disabled={aiUploading || !aiFile}
                                    className="flex items-center gap-2 rounded-lg bg-fuchsia-600 px-4 py-2 text-sm font-medium text-white hover:bg-fuchsia-700 transition disabled:opacity-50"
                                >
                                    {aiUploading ? 'Uploading…' : 'Upload & Summarize'}
                                </button>
                            </div>
                        </div>
                    </div>
                )}

                {/* Cancel Confirmation Modal */}
                {cancelConfirm && (
                    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
                        <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-xl">
                            <h3 className="text-lg font-bold text-gray-900">Cancel Workshop?</h3>
                            <p className="mt-2 text-sm text-gray-500">
                                This will <strong>permanently cancel</strong> the workshop. All <strong>SUCCESS</strong> and <strong>PENDING</strong> registrations will be cancelled automatically. Completed payments will be preserved for refund reconciliation.
                            </p>
                            <div className="mt-5 flex justify-end gap-3">
                                <button
                                    onClick={() => setCancelConfirm(null)}
                                    className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition"
                                >
                                    No, Keep
                                </button>
                                <button
                                    onClick={() => handleCancel(cancelConfirm)}
                                    className="rounded-lg bg-orange-600 px-4 py-2 text-sm font-medium text-white hover:bg-orange-700 transition"
                                >
                                    Yes, Cancel Workshop
                                </button>
                            </div>
                        </div>
                    </div>
                )}
            </div>

            <Suspense fallback={<DeferredContent />}>
                {attendanceWorkshop && <AttendanceModal workshop={attendanceWorkshop} onClose={() => setAttendanceWorkshop(null)} />}
                {createAccountOpen && <CreateAccountModal isOpen onClose={() => setCreateAccountOpen(false)} />}
            </Suspense>
        </>
    );
};

export default Dashboard;
