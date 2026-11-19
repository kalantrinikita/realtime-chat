import { useMemo } from 'react';
import type { User } from '../types';
import { useChat } from '../useChat';
import { ConversationPane } from './ConversationPane';
import { Sidebar } from './Sidebar';
import { initials } from './format';

interface Props {
  me: User;
  onLogout(): void;
  onSessionEnded(): void;
}

const STATUS_LABEL = {
  connecting: 'Connecting…',
  open: 'Live',
  reconnecting: 'Reconnecting…',
} as const;

export function ChatScreen({ me, onLogout, onSessionEnded }: Props) {
  const chat = useChat(me, onSessionEnded);
  const { state } = chat;
  const active = state.activeId !== null ? state.conversations[state.activeId] : undefined;
  const pendingForActive = useMemo(
    () => state.pending.filter((p) => p.conversationId === state.activeId),
    [state.pending, state.activeId],
  );

  return (
    <div className={`app ${active ? 'has-active' : ''}`}>
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span>Chat</span>
        </div>
        <span className={`status status-${state.connection}`} role="status">
          <span className="dot" aria-hidden="true" />
          {STATUS_LABEL[state.connection]}
        </span>
        <div className="me">
          <span className="avatar small" aria-hidden="true">
            {initials(me.displayName)}
          </span>
          <span className="me-text">
            <strong>{me.displayName}</strong>
            <span className="muted small">{me.email}</span>
          </span>
          <button className="ghost" onClick={onLogout}>
            Log out
          </button>
        </div>
      </header>

      {state.connection === 'reconnecting' && (
        <div className="banner" role="status">
          Connection lost. Reconnecting… Messages you send now will be delivered as soon as it is back.
        </div>
      )}

      <div className="layout">
        <Sidebar
          me={me}
          conversations={chat.conversations}
          activeId={state.activeId}
          unread={state.unread}
          onOpen={(id) => void chat.openConversation(id)}
          onStart={chat.startConversation}
        />
        {active ? (
          <ConversationPane
            me={me}
            conversation={active}
            thread={state.threads[active.id]}
            pending={pendingForActive}
            onSend={(body) => chat.sendMessage(active.id, body)}
            onRetry={chat.retry}
            onLoadOlder={() => void chat.loadOlder(active.id)}
            onBack={() => void chat.openConversation(null)}
          />
        ) : (
          <section className="pane placeholder">
            <div>
              <h2>Pick a conversation</h2>
              <p className="muted">
                Or start one by entering the email someone signed up with. To try it alone, open this page in a
                private window and create a second account.
              </p>
            </div>
          </section>
        )}
      </div>
    </div>
  );
}
