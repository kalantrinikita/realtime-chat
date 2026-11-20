import { Fragment, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import type { Thread } from '../chatState';
import type { Conversation, PendingMessage, User } from '../types';
import { formatClock, formatDay, sameDay } from './format';

const MAX_CHARS = 4000;

interface Props {
  me: User;
  conversation: Conversation;
  thread: Thread | undefined;
  pending: PendingMessage[];
  onSend(body: string): void;
  onRetry(clientMsgId: string): void;
  onLoadOlder(): void;
  onBack(): void;
}

/** Code points, matching the server's limit (an emoji is 1 here, 2 in String.length). */
function charCount(s: string): number {
  return [...s].length;
}

export function ConversationPane({ me, conversation, thread, pending, onSend, onRetry, onLoadOlder, onBack }: Props) {
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);
  const messages = thread?.messages ?? [];
  const count = charCount(draft);

  // Follow new messages only if the user is already at the bottom; don't yank them away from
  // history they scrolled up to read.
  useLayoutEffect(() => {
    const el = listRef.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [messages.length, pending.length, conversation.id]);

  useEffect(() => {
    stickToBottom.current = true;
    setDraft('');
  }, [conversation.id]);

  function onScroll() {
    const el = listRef.current;
    if (el) stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!draft.trim() || count > MAX_CHARS) return;
    stickToBottom.current = true;
    onSend(draft);
    setDraft('');
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends, Shift+Enter adds a newline. isComposing: don't send while an IME (Chinese,
    // Japanese, Korean input) is still composing; that Enter confirms the composition.
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit();
    }
  }

  return (
    <section className="pane" aria-label={`Conversation with ${conversation.peer.displayName}`}>
      <header className="pane-header">
        <button className="back" onClick={onBack} aria-label="Back to conversations">
          ‹
        </button>
        <div>
          <strong>{conversation.peer.displayName}</strong>
          <div className="muted small">{conversation.peer.email}</div>
        </div>
      </header>

      <div className="messages" ref={listRef} onScroll={onScroll} aria-live="polite">
        {thread?.loading && <p className="muted center">Loading…</p>}
        {thread && !thread.loading && thread.hasOlder && (
          <button className="link center" onClick={onLoadOlder}>
            Load earlier messages
          </button>
        )}
        {thread && !thread.loading && messages.length === 0 && pending.length === 0 && (
          <p className="muted center">No messages yet. Say hi 👋</p>
        )}

        {messages.map((m, i) => {
          const mine = m.senderId === me.id;
          // Day label above the first message of each day. Messages are already in seq order.
          const newDay = i === 0 || !sameDay(messages[i - 1]!.createdAt, m.createdAt);
          return (
              <Fragment key={m.seq}>
                {newDay && <div className="day-divider">{formatDay(m.createdAt)}</div>}
                <div className={`bubble ${mine ? 'mine' : 'theirs'}`} data-seq={m.seq}>
                  <p>{m.body}</p>
                  <span className="meta">
                  {formatClock(m.createdAt)}
                    {mine && <span title="Delivered to server"> ✓</span>}
                </span>
                </div>
              </Fragment>
          );
        })}

        {pending.length > 0 &&
            (messages.length === 0 || !sameDay(messages[messages.length - 1]!.createdAt, Date.now())) && (
                <div className="day-divider">Today</div>
            )}

        {pending.map((p) => (
          <div key={p.clientMsgId} className={`bubble mine ${p.status}`}>
            <p>{p.body}</p>
            <span className="meta">
              {p.status === 'sending' ? (
                <span title="Waiting for the server to confirm">Sending…</span>
              ) : (
                <>
                  <span className="error-text">Not sent{p.error ? `: ${p.error}` : ''}</span>{' '}
                  <button className="link" onClick={() => onRetry(p.clientMsgId)}>
                    Retry
                  </button>
                </>
              )}
            </span>
          </div>
        ))}
      </div>

      <form className="composer" onSubmit={submit}>
        <textarea
          aria-label="Message"
          placeholder="Type a message… (Enter to send, Shift+Enter for a new line)"
          rows={1}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
        />
        {count > MAX_CHARS - 200 && (
          <span className={`counter ${count > MAX_CHARS ? 'over' : ''}`}>
            {count}/{MAX_CHARS}
          </span>
        )}
        <button type="submit" className="primary" disabled={!draft.trim() || count > MAX_CHARS}>
          Send
        </button>
      </form>
    </section>
  );
}
