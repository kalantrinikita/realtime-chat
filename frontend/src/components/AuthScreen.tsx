import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import type { User } from '../types';

interface Props {
  onAuthenticated(user: User): void;
}

export function AuthScreen({ onAuthenticated }: Props) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [inviteRequired, setInviteRequired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api
      .config()
      .then((c) => setInviteRequired(c.inviteRequired))
      .catch(() => {});
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const { user } =
        mode === 'login'
          ? await api.login({ email, password })
          : await api.register({
              email,
              password,
              displayName: displayName || undefined,
              inviteCode: inviteRequired ? inviteCode : undefined,
            });
      onAuthenticated(user);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Cannot reach the server. Is the backend running?');
    } finally {
      setBusy(false);
    }
  }

  const isRegister = mode === 'register';
  return (
    <main className="auth">
      <form className="auth-card" onSubmit={submit} aria-labelledby="auth-title">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span>Chat</span>
        </div>
        <h1 id="auth-title">{isRegister ? 'Create your account' : 'Sign in'}</h1>
        <p className="muted">
          {isRegister ? 'Use any email; nothing is sent to it. It is how others find you.' : 'Welcome back.'}
        </p>

        <label>
          Email
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            required
            minLength={isRegister ? 8 : undefined}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {isRegister && (
          <label>
            Display name <span className="muted">(optional)</span>
            <input autoComplete="nickname" maxLength={50} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </label>
        )}
        {isRegister && inviteRequired && (
          <label>
            Invite code
            <input required value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} />
          </label>
        )}

        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}

        <button type="submit" className="primary" disabled={busy}>
          {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Sign in'}
        </button>
        <button
          type="button"
          className="link"
          onClick={() => {
            setMode(isRegister ? 'login' : 'register');
            setError(null);
          }}
        >
          {isRegister ? 'Already have an account? Sign in' : 'New here? Create an account'}
        </button>
      </form>
    </main>
  );
}
