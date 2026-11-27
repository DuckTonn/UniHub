export default function OperationError({ error, message = error?.message, onRetry }) {
    if (!error) return null;
    return (
        <div role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
            <p>{message}</p>
            {error.requestId && <p className="mt-1 break-all">Request ID <code>{error.requestId}</code></p>}
            {onRetry && <button type="button" className="mt-2 font-semibold underline" onClick={onRetry}>Try again</button>}
        </div>
    );
}
