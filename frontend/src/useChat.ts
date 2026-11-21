import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react';
import { api, ApiError } from './api';
import { contiguousMax, hasGap, initialState, needsFill, reducer, sortedConversations } from './chatState';
import { ChatSocket, socketUrl } from './realtime';
import type { PendingMessage, ServerEvent, User } from './types';
import { uuidv4 } from './uuid';

const PAGE_SIZE = 50;
const RESYNC_PAGE = 100;
/** A message put on the wire without an ack for this long is shown as failed. */
const ACK_TIMEOUT_MS = 15_000;

export function useChat(me: User, onSessionEnded: () => void) {
  const [state, dispatch] = useReducer(reducer, initialState);
  // Event handlers outlive renders; they read the latest state through this ref.
  const stateRef = useRef(state);
  stateRef.current = state;
  const socketRef = useRef<ChatSocket | null>(null);
  const sessionEndedRef = useRef(onSessionEnded);
  sessionEndedRef.current = onSessionEnded;
  const fillsInFlight = useRef(new Map<number, boolean>());

  const handleApiError = useCallback((e: unknown) => {
    if (e instanceof ApiError && e.status === 401) sessionEndedRef.current();
    else console.warn(e);
  }, []);

  const refreshConversations = useCallback(async () => {
    try {
      const { conversations } = await api.conversations();
      dispatch({ type: 'conversationsLoaded', conversations });
    } catch (e) {
      handleApiError(e);
    }
  }, [handleApiError]);

  const fillThread = useCallback(
    async (conversationId: number) => {
      const inflight = fillsInFlight.current;
      if (inflight.has(conversationId)) {
        inflight.set(conversationId, true); // ask the running fill to go round once more
        return;
      }
      inflight.set(conversationId, false);
      try {
        do {
          inflight.set(conversationId, false);
          let after = contiguousMax(stateRef.current.threads[conversationId]);
          for (;;) {
            const { messages } = await api.messages(conversationId, { after, limit: RESYNC_PAGE });
            if (messages.length > 0) dispatch({ type: 'messagesMerged', conversationId, messages });
            if (messages.length < RESYNC_PAGE) break;
            after = messages[messages.length - 1]!.seq;
          }
        } while (inflight.get(conversationId));
      } catch (e) {
        handleApiError(e);
      } finally {
        inflight.delete(conversationId);
      }
    },
    [handleApiError],
  );

  const transmit = useCallback((p: Pick<PendingMessage, 'clientMsgId' | 'conversationId' | 'body'>) => {
    const sent = socketRef.current?.send({
      clientMsgId: p.clientMsgId,
      conversationId: p.conversationId,
      body: p.body,
    });
    if (sent) dispatch({ type: 'sendAttempted', clientMsgIds: [p.clientMsgId], at: Date.now() });
    return !!sent;
  }, []);

  const onReady = useCallback(async () => {
    // The server sends "ready" only after this socket is registered for pushes, so from here on
    // nothing new can slip past us; now fetch whatever arrived while we were disconnected.
    await refreshConversations();
    await Promise.all(Object.keys(stateRef.current.threads).map((id) => fillThread(Number(id))));
    // The fills above also clear pending messages whose ack was lost. Whatever is left was never
    // stored, or might not have been: resend. Same clientMsgId, so a duplicate is impossible.
    for (const p of stateRef.current.pending) {
      if (p.status === 'sending') transmit(p);
    }
  }, [refreshConversations, fillThread, transmit]);

  const handleEvent = useCallback(
    (e: ServerEvent) => {
      switch (e.type) {
        case 'ready':
          void onReady();
          break;
        case 'ack':
          dispatch({ type: 'ackReceived', clientMsgId: e.clientMsgId, message: e.message });
          break;
        case 'message': {
          const s = stateRef.current;
          // First message of a conversation someone else just started with us.
          if (!s.conversations[e.message.conversationId]) void refreshConversations();
          const gap = hasGap(s, e.message);
          dispatch({ type: 'messageReceived', message: e.message, me: me.id });
          if (gap) void fillThread(e.message.conversationId);
          break;
        }
        case 'error':
          if (e.clientMsgId) dispatch({ type: 'sendFailed', clientMsgId: e.clientMsgId, error: e.message });
          else console.warn('server error', e);
          break;
      }
    },
    [me.id, onReady, refreshConversations, fillThread],
  );
  const handleEventRef = useRef(handleEvent);
  handleEventRef.current = handleEvent;

  useEffect(() => {
    const socket = new ChatSocket(socketUrl(), {
      onEvent: (e) => handleEventRef.current(e),
      onStatus: (status) => dispatch({ type: 'connection', status }),
      onSessionEnded: () => sessionEndedRef.current(),
      checkSession: async () => {
        try {
          await api.me();
          return true;
        } catch (e) {
          if (e instanceof ApiError && e.status === 401) return false;
          throw e;
        }
      },
    });
    socketRef.current = socket;
    socket.start();
    return () => {
      socket.stop();
      socketRef.current = null;
    };
  }, []);

  // A send whose ack never came while the socket stayed up is surfaced as failed.
  useEffect(() => {
    const timer = setInterval(() => {
      const s = stateRef.current;
      if (s.connection !== 'open') return;
      const now = Date.now();
      for (const p of s.pending) {
        if (p.status === 'sending' && p.sentAt > 0 && now - p.sentAt > ACK_TIMEOUT_MS) {
          dispatch({ type: 'sendFailed', clientMsgId: p.clientMsgId, error: 'Not confirmed by the server' });
        }
      }
    }, 3_000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    for (const [id, thread] of Object.entries(state.threads)) {
      if (needsFill(thread)) void fillThread(Number(id));
    }
  }, [state.threads, fillThread]);

  const totalUnread = Object.values(state.unread).reduce((a, b) => a + b, 0);
  useEffect(() => {
    document.title = totalUnread > 0 ? `(${totalUnread}) Chat` : 'Chat';
  }, [totalUnread]);

  const openConversation = useCallback(
    async (conversationId: number | null) => {
      dispatch({ type: 'setActive', conversationId });
      if (conversationId === null || stateRef.current.threads[conversationId]) return;
      dispatch({ type: 'threadLoading', conversationId });
      try {
        const { messages } = await api.messages(conversationId, { limit: PAGE_SIZE });
        const first = messages[0];
        dispatch({
          type: 'threadLoaded',
          conversationId,
          messages,
          hasOlder: messages.length === PAGE_SIZE && !!first && first.seq > 1,
        });
      } catch (e) {
        handleApiError(e);
      }
    },
    [handleApiError],
  );

  const loadOlder = useCallback(
    async (conversationId: number) => {
      const oldest = stateRef.current.threads[conversationId]?.messages[0];
      if (!oldest) return;
      try {
        const { messages } = await api.messages(conversationId, { before: oldest.seq, limit: PAGE_SIZE });
        const first = messages[0];
        dispatch({
          type: 'olderLoaded',
          conversationId,
          messages,
          hasOlder: messages.length === PAGE_SIZE && !!first && first.seq > 1,
        });
      } catch (e) {
        handleApiError(e);
      }
    },
    [handleApiError],
  );

  const sendMessage = useCallback(
    (conversationId: number, body: string) => {
      const pending: PendingMessage = {
        clientMsgId: uuidv4(),
        conversationId,
        body,
        createdAt: Date.now(),
        status: 'sending',
        sentAt: 0, // 0 = not on the wire yet (offline); queued until the next "ready"
      };
      dispatch({ type: 'sendQueued', pending });
      transmit(pending);
    },
    [transmit],
  );

  const retry = useCallback(
    (clientMsgId: string) => {
      const p = stateRef.current.pending.find((x) => x.clientMsgId === clientMsgId);
      if (!p) return;
      dispatch({ type: 'retry', clientMsgId, at: Date.now() });
      transmit(p);
    },
    [transmit],
  );

  /** Throws ApiError so the form can show "no user with that email". */
  const startConversation = useCallback(
    async (email: string) => {
      const { conversation } = await api.startConversation(email);
      dispatch({ type: 'conversationUpserted', conversation });
      await openConversation(conversation.id);
    },
    [openConversation],
  );

  const conversations = useMemo(() => sortedConversations(state), [state]);

  return { state, conversations, openConversation, loadOlder, sendMessage, retry, startConversation };
}
