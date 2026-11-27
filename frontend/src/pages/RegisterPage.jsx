import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import PasswordField from '../components/common/PasswordField';
import authService from '../services/authService';

const RegisterPage = () => {
    const [formData, setFormData] = useState({
        fullName: '',
        studentCode: '',
        email: '',
        phoneNumber: '',
        password: '',
        confirmPassword: '',
    });
    const [error, setError] = useState('');
    const [success, setSuccess] = useState(false);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const navigate = useNavigate();

    useEffect(() => {
        if (!success) return;
        const redirect = setTimeout(() => navigate('/login'), 2000);
        return () => clearTimeout(redirect);
    }, [navigate, success]);

    const handleChange = (e) => {
        const { id, value } = e.target;
        setFormData((previous) => ({ ...previous, [id]: value }));
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');

        if (formData.password !== formData.confirmPassword) {
            setError('Passwords do not match. Please enter the same password in both fields.');
            return;
        }

        setIsSubmitting(true);

        try {
            const { fullName, studentCode, email, phoneNumber, password } = formData;
            await authService.studentRegister({
                fullName,
                studentCode,
                email,
                phoneNumber: phoneNumber || undefined,
                password,
            });
            setSuccess(true);
        } catch (err) {
            setError(err.message || 'We couldn’t create your account. Please try again.');
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <section className="auth-content auth-content--register" aria-labelledby="register-title">
            <h1 className="auth-title" id="register-title">Create your account</h1>
            <p className="auth-intro">Register as a student to book campus workshops.</p>

            {error && <p className="auth-feedback" role="alert">{error}</p>}
            {success && (
                <p className="auth-feedback auth-feedback--success" role="status">
                    Account activated. Taking you to sign in…
                </p>
            )}

            <form className="auth-form" onSubmit={handleSubmit} aria-busy={isSubmitting}>
                <div className="auth-pair">
                    <div>
                        <label htmlFor="fullName">Full name</label>
                        <input id="fullName" name="fullName" autoComplete="name"
                            value={formData.fullName} onChange={handleChange} required />
                    </div>
                    <div>
                        <label htmlFor="studentCode">Student code</label>
                        <input id="studentCode" name="studentCode"
                            value={formData.studentCode} onChange={handleChange} required />
                    </div>
                </div>

                <div className="auth-pair">
                    <div>
                        <label htmlFor="email">Email address</label>
                        <input id="email" name="email" type="email" autoComplete="email" autoCapitalize="none"
                            value={formData.email} onChange={handleChange} required />
                    </div>
                    <div>
                        <label htmlFor="phoneNumber">Phone <span>(optional)</span></label>
                        <input id="phoneNumber" name="phoneNumber" type="tel" autoComplete="tel"
                            value={formData.phoneNumber} onChange={handleChange} />
                    </div>
                </div>

                <div className="auth-pair">
                    <PasswordField
                        id="password"
                        label="Password"
                        value={formData.password}
                        onChange={handleChange}
                        describedBy="password-hint"
                        required
                        minLength={6}
                        autoComplete="new-password"
                    />
                    <PasswordField
                        id="confirmPassword"
                        label="Confirm password"
                        value={formData.confirmPassword}
                        onChange={handleChange}
                        required
                        minLength={6}
                        autoComplete="new-password"
                    />
                </div>
                <p className="auth-note" id="password-hint">Use at least 6 characters.</p>

                <button type="submit" className="auth-submit" disabled={isSubmitting || success}>
                    {isSubmitting && <span className="auth-submit-spinner" aria-hidden="true" />}
                    {isSubmitting ? 'Creating account…' : 'Create account'}
                    {!isSubmitting && !success && <span aria-hidden="true">→</span>}
                </button>
            </form>

            <p className="auth-switch">
                Already have an account? <Link to="/login">Sign in</Link>
            </p>
        </section>
    );
};

export default RegisterPage;
