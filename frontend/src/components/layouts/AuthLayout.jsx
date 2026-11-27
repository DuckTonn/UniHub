import { Suspense } from 'react';
import { Link, Outlet } from 'react-router-dom';
import DeferredContent from '../common/DeferredContent';
import './auth.css';

export default function AuthLayout() {
    return (
        <div className="auth-layout">
            <aside className="auth-cover">
                <div className="auth-cover-caption">
                    <p className="auth-cover-title">Make time to<br />learn together.</p>
                    <p>Discover campus workshops, book your spot, and keep track of what’s coming up.</p>
                </div>
            </aside>
            <div className="auth-panel">
                <header className="auth-header">
                    <Link to="/login" className="auth-brand" aria-label="UniHub sign in">
                        <span className="auth-brand-mark" aria-hidden="true">U</span>
                        UniHub
                    </Link>
                </header>
                <main className="auth-main">
                    <Suspense fallback={<DeferredContent />}>
                        <Outlet />
                    </Suspense>
                </main>
            </div>
        </div>
    );
}
