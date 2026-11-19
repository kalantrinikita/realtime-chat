import type { ConnectionStatus, Conversation, Message, PendingMessage } from './types';

/**
 * All chat state transitions as a pure reducer, so the tricky parts (dedupe, ordering, gap
 * detection, ack reconciliation) are unit-testable without a browser or a server.
 */

export interface Thread {
  /** Confirmed messages, sorted by seq, unique by seq. */
  messages: Message[];
  /** False once we have paged back to seq 1. */
  hasOlder: boolean;
  /**
   * The thread exists (so live pushes are captured) but its first page is still in flight.
   * Created BEFORE the fetch starts: if we only created it when the page arrived, a message pushed
   * while the request was in flight could be newer than the page and never shown.
   */
  loading: boolean;
}

export interface ChatState {
  conversations: Record<number, Conversation>;
  /** Only conversations the user has opened have a thread; others are summaries only. */
  threads: Record<number, Thread>;
  pending: PendingMessage[];
  activeId: number | null;
  unread: Record<number, number>;
  connection: ConnectionStatus;
}

export const initialState: ChatState = {
  conversations: {},
  threads: {},
  pending: [],
  activeId: null,
  unread: {},
  connection: 'connecting',
};

export type Action =
  | { type: 'conversationsLoaded'; conversations: Conversation[] }
  | { type: 'conversationUpserted'; conversation: Conversation }
  | { type: 'threadLoading'; conversationId: number }
  | { type: 'threadLoaded'; conversationId: number; messages: Message[]; hasOlder: boolean }
  | { type: 'olderLoaded'; conversationId: number; messages: Message[]; hasOlder: boolean }
  | { type: 'messagesMerged'; conversationId: number; messages: Message[] }
  | { type: 'messageReceived'; message: Message; me: number }
  | { type: 'ackReceived'; clientMsgId: string; message: Message }
  | { type: 'sendQueued'; pending: PendingMessage }
  | { type: 'sendAttempted'; clientMsgIds: string[]; at: number }
  | { type: 'sendFailed'; clientMsgId: string; error: string }
  | { type: 'retry'; clientMsgId: string; at: number }
  | { type: 'setActive'; conversationId: number | null }
  | { type: 'connection'; status: ConnectionStatus };

/** Merge by seq: duplicates (push + resync + ack of the same message) collapse into one. */
export function mergeMessages(existing: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) return existing;
  const bySeq = new Map<number, Message>();
  for (const m of existing) bySeq.set(m.seq, m);
  for (const m of incoming) bySeq.set(m.seq, m);
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq);
}

export function maxSeq(thread: Thread | undefined): number {
  const last = thread?.messages[thread.messages.length - 1];
  return last ? last.seq : 0;
}

/**
 * Highest seq up to which the loaded messages have no holes (starting from the oldest loaded).
 * This, not maxSeq, is the resync cursor: if we hold 1..5 and 8, fetching "after 8" would never
 * return 6 and 7, but fetching "after 5" does. Seqs are gap-free on the server, so any jump in
 * our copy means we missed something.
 */
export function contiguousMax(thread: Thread | undefined): number {
  const ms = thread?.messages;
  if (!ms || ms.length === 0) return 0;
  let s = ms[0]!.seq;
  for (let i = 1; i < ms.length; i++) {
    if (ms[i]!.seq !== s + 1) break;
    s = ms[i]!.seq;
  }
  return s;
}

/** True once a thread we display holds a hole that only a fetch can fill. */
export function needsFill(thread: Thread | undefined): boolean {
  return !!thread && !thread.loading && contiguousMax(thread) < maxSeq(thread);
}

/**
 * True if receiving `message` would leave a hole in a thread we display: e.g. we hold 1..5 and
 * seq 8 arrives because 6 and 7 were pushed while our socket was briefly gone.
 */
export function hasGap(state: ChatState, message: Message): boolean {
  const thread = state.threads[message.conversationId];
  if (!thread || thread.loading) return false;
  return message.seq > contiguousMax(thread) + 1;
}

function withSummary(state: ChatState, message: Message): Record<number, Conversation> {
  const conv = state.conversations[message.conversationId];
  if (!conv || message.seq < conv.lastSeq) return state.conversations;
  return {
    ...state.conversations,
    [conv.id]: { ...conv, lastSeq: message.seq, lastMessage: message, updatedAt: message.createdAt },
  };
}

function withThreadMessages(state: ChatState, conversationId: number, messages: Message[]): Record<number, Thread> {
  const thread = state.threads[conversationId];
  if (!thread) return state.threads; // not open: the summary is enough until the user opens it
  return { ...state.threads, [conversationId]: { ...thread, messages: mergeMessages(thread.messages, messages) } };
}

export function reducer(state: ChatState, action: Action): ChatState {
  switch (action.type) {
    case 'conversationsLoaded': {
      const conversations: Record<number, Conversation> = {};
      for (const c of action.conversations) conversations[c.id] = c;
      return { ...state, conversations };
    }
    case 'conversationUpserted':
      return { ...state, conversations: { ...state.conversations, [action.conversation.id]: action.conversation } };

    case 'threadLoading': {
      if (state.threads[action.conversationId]) return state;
      return {
        ...state,
        threads: { ...state.threads, [action.conversationId]: { messages: [], hasOlder: true, loading: true } },
      };
    }
    case 'threadLoaded': {
      const existing = state.threads[action.conversationId];
      const messages = mergeMessages(existing?.messages ?? [], action.messages);
      return {
        ...state,
        threads: { ...state.threads, [action.conversationId]: { messages, hasOlder: action.hasOlder, loading: false } },
      };
    }
    case 'olderLoaded': {
      const thread = state.threads[action.conversationId];
      if (!thread) return state;
      return {
        ...state,
        threads: {
          ...state.threads,
          [action.conversationId]: {
            ...thread,
            messages: mergeMessages(thread.messages, action.messages),
            hasOlder: action.hasOlder,
          },
        },
      };
    }
    case 'messagesMerged': {
      const last = action.messages[action.messages.length - 1];
      let next: ChatState = { ...state, threads: withThreadMessages(state, action.conversationId, action.messages) };
      if (last) next = { ...next, conversations: withSummary(next, last) };
      // A resync can also deliver the ack we never got: our own pending messages that are now stored.
      const stored = new Set(action.messages.map((m) => m.clientMsgId));
      return { ...next, pending: next.pending.filter((p) => !stored.has(p.clientMsgId)) };
    }
    case 'messageReceived': {
      const m = action.message;
      const fromOther = m.senderId !== action.me;
      const unread =
        fromOther && state.activeId !== m.conversationId
          ? { ...state.unread, [m.conversationId]: (state.unread[m.conversationId] ?? 0) + 1 }
          : state.unread;
      return {
        ...state,
        threads: withThreadMessages(state, m.conversationId, [m]),
        conversations: withSummary(state, m),
        // Sent from this same tab and the push won the race against the ack: reconcile here too.
        pending: state.pending.filter((p) => p.clientMsgId !== m.clientMsgId),
        unread,
      };
    }
    case 'ackReceived':
      return {
        ...state,
        pending: state.pending.filter((p) => p.clientMsgId !== action.clientMsgId),
        threads: withThreadMessages(state, action.message.conversationId, [action.message]),
        conversations: withSummary(state, action.message),
      };

    case 'sendQueued':
      return { ...state, pending: [...state.pending, action.pending] };
    case 'sendAttempted': {
      const ids = new Set(action.clientMsgIds);
      return {
        ...state,
        pending: state.pending.map((p) => (ids.has(p.clientMsgId) ? { ...p, sentAt: action.at } : p)),
      };
    }
    case 'sendFailed':
      return {
        ...state,
        pending: state.pending.map((p) =>
          p.clientMsgId === action.clientMsgId ? { ...p, status: 'failed', error: action.error } : p,
        ),
      };
    case 'retry':
      return {
        ...state,
        pending: state.pending.map((p) =>
          p.clientMsgId === action.clientMsgId ? { ...p, status: 'sending', error: undefined, sentAt: action.at } : p,
        ),
      };

    case 'setActive': {
      if (action.conversationId === null) return { ...state, activeId: null };
      const unread = { ...state.unread };
      delete unread[action.conversationId];
      return { ...state, activeId: action.conversationId, unread };
    }
    case 'connection':
      return { ...state, connection: action.status };
  }
}

/** Conversations for the sidebar, most recently active first. */
export function sortedConversations(state: ChatState): Conversation[] {
  return Object.values(state.conversations).sort((a, b) => b.updatedAt - a.updatedAt || b.id - a.id);
}
