import { useState } from 'react';

export default function WorkshopFilters({ filters, onApply, onReset }) {
    const [error, setError] = useState('');

    return (
        <form className="discovery-filters" onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            const from = form.get('from');
            const to = form.get('to');
            if (from && to && from > to) {
                setError('The start date must be on or before the end date.');
                return;
            }
            setError('');
            onApply({
                q: form.get('q').trim(),
                from,
                to,
                fee: form.get('fee'),
                seatsAvailable: form.has('seatsAvailable') ? 'true' : '',
            });
        }}>
            <label className="discovery-search">
                Search by name or speaker
                <input name="q" type="search" defaultValue={filters.q} maxLength={120} placeholder="e.g. Design Thinking" />
            </label>
            <label>
                From date
                <input name="from" type="date" defaultValue={filters.from} max="9999-12-31" />
            </label>
            <label>
                To date
                <input name="to" type="date" defaultValue={filters.to} max="9999-12-31" />
            </label>
            <label>
                Fee
                <select name="fee" defaultValue={filters.fee}>
                    <option value="">Any price</option>
                    <option value="FREE">Free</option>
                    <option value="PAID">Paid</option>
                </select>
            </label>
            <label className="discovery-checkbox">
                <input name="seatsAvailable" type="checkbox" defaultChecked={filters.seatsAvailable === 'true'} />
                Only with seats available
            </label>
            <div className="discovery-actions">
                <button type="submit" className="student-button student-button--primary">Apply filters</button>
                <button type="reset" className="student-button" onClick={() => { setError(''); onReset(); }}>Clear filters</button>
            </div>
            {error && <p className="student-notice student-notice--error" role="alert">{error}</p>}
        </form>
    );
}
