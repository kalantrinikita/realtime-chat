import { useState, type FormEvent } from 'react';
import { ApiError } from '../api';
import type { Conversation, User } from '../types';
import { formatTime, initials } from './format';

interface Props {
  me: User;
  conversations: Conversation[];
  activeId: number | null;
  unread: Record<number, number>;
  onOpen(id: number): void;
  onStart(email: string): Promise<void>;
}

export function Sidebar({ me, conversations, activeId, unread, onOpen, onStart }: Props) {
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function start(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await onStart(email.trim());
      setEmail('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the conversation');
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside className="sidebar">
      <form className="new-chat" onSubmit={start}>
        <label htmlFor="new-chat-email">Start a chat</label>
        <div className="row">
          <input
            id="new-chat-email"
            type="email"
            placeholder="their@email.com"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <button type="submit" className="primary" disabled={busy}>
            Chat
          </button>
        </div>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
      </form>

      <nav aria-label="Conversations">
        {conversations.length === 0 ? (
          <p className="empty-list muted">No conversations yet. Enter someone's email above to start one.</p>
        ) : (
          <ul className="conversation-list">
            {conversations.map((c) => {
              const last = c.lastMessage;
              const count = unread[c.id] ?? 0;
              return (
                <li key={c.id}>
                  <button
                    className={`conversation${c.id === activeId ? ' active' : ''}`}
                    onClick={() => onOpen(c.id)}
                    aria-current={c.id === activeId ? 'true' : undefined}
                  >
                    <span className="avatar" aria-hidden="true">
                      {initials(c.peer.displayName)}
                    </span>
                    <span className="conversation-text">
                      <span className="conversation-top">
                        <strong>{c.peer.displayName}</strong>
                        {last && <time className="muted">{formatTime(last.createdAt)}</time>}
                      </span>
                      <span className="conversation-bottom">
                        <span className="preview muted">
                          {last ? `${last.senderId === me.id ? 'You: ' : ''}${last.body}` : c.peer.email}
                        </span>
                        {count > 0 && (
                          <span className="badge" aria-label={`${count} unread`}>
                            {count}
                          </span>
                        )}
                      </span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </nav>
    </aside>
  );
}
