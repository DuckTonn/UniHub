import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import PasswordField from '../components/common/PasswordField';
import authService from '../services/authService';

const LoginPage = () => {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);

    const { login } = useAuth();
    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setIsSubmitting(true);

        try {
            const session = await authService.login({ email, password });
            await login(session);
        } catch (err) {
            setError(err.message || 'We couldn’t sign you in. Please try again.');
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <section className="auth-content" aria-labelledby="login-title">
            <h1 className="auth-title" id="login-title">Welcome back</h1>
            <p className="auth-intro">Sign in to manage your workshops and registrations.</p>

            {error && <p className="auth-feedback" role="alert">{error}</p>}

            <form className="auth-form" onSubmit={handleSubmit} aria-busy={isSubmitting}>
                <div>
                    <label htmlFor="email">Email address</label>
                    <input
                        id="email"
                        name="email"
                        type="email"
                        inputMode="email"
                        autoComplete="username"
                        autoCapitalize="none"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        placeholder="you@example.com"
                        required
                    />
                </div>
                <PasswordField
                    id="password"
                    label="Password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter your password"
                    autoComplete="current-password"
                    required
                />
                <button type="submit" className="auth-submit" disabled={isSubmitting}>
                    {isSubmitting && <span className="auth-submit-spinner" aria-hidden="true" />}
                    {isSubmitting ? 'Signing in…' : 'Sign in'}
                    {!isSubmitting && <span aria-hidden="true">→</span>}
                </button>
            </form>

            <p className="auth-switch">
                New to UniHub? <Link to="/register">Create a student account</Link>
            </p>
        </section>
    );
};

export default LoginPage;
