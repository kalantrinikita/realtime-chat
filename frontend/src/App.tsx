import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from './api';
import { AuthScreen } from './components/AuthScreen';
import { ChatScreen } from './components/ChatScreen';
import type { User } from './types';

type Session = { status: 'checking' } | { status: 'anonymous' } | { status: 'signed-in'; user: User };

export function App() {
  const [session, setSession] = useState<Session>({ status: 'checking' });

  useEffect(() => {
    api
      .me()
      .then(({ user }) => setSession({ status: 'signed-in', user }))
      .catch((e) => {
        if (!(e instanceof ApiError)) console.warn('Backend unreachable', e);
        setSession({ status: 'anonymous' });
      });
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setSession({ status: 'anonymous' });
    }
  }, []);

  const sessionEnded = useCallback(() => setSession({ status: 'anonymous' }), []);

  if (session.status === 'checking') return <div className="splash muted">Loading…</div>;
  if (session.status === 'anonymous') {
    return <AuthScreen onAuthenticated={(user) => setSession({ status: 'signed-in', user })} />;
  }
  // key: a different user gets a completely fresh chat state and socket.
  return (
    <ChatScreen key={session.user.id} me={session.user} onLogout={() => void logout()} onSessionEnded={sessionEnded} />
  );
}
