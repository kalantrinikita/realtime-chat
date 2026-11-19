import { describe, expect, it } from 'vitest';
import { contiguousMax, hasGap, initialState, maxSeq, mergeMessages, needsFill, reducer, type ChatState } from './chatState';
import type { Conversation, Message, PendingMessage } from './types';

const ME = 1;
const PEER = 2;

function msg(seq: number, overrides: Partial<Message> = {}): Message {
  return {
    id: 100 + seq,
    conversationId: 10,
    seq,
    senderId: PEER,
    clientMsgId: `c-${seq}`,
    body: `m${seq}`,
    createdAt: 1_000 + seq,
    ...overrides,
  };
}

const conversation: Conversation = {
  id: 10,
  peer: { id: PEER, email: 'bob@example.com', displayName: 'Bob' },
  lastSeq: 0,
  lastMessage: null,
  updatedAt: 0,
};

function withOpenThread(messages: Message[]): ChatState {
  let s = reducer(initialState, { type: 'conversationsLoaded', conversations: [conversation] });
  s = reducer(s, { type: 'threadLoaded', conversationId: 10, messages, hasOlder: false });
  return s;
}

describe('mergeMessages', () => {
  it('dedupes by seq and sorts, whatever order copies arrive in', () => {
    const merged = mergeMessages([msg(1), msg(3)], [msg(3), msg(2), msg(1)]);
    expect(merged.map((m) => m.seq)).toEqual([1, 2, 3]);
  });
});

describe('gap detection', () => {
  it('flags a jump in seq for an open thread', () => {
    const s = withOpenThread([msg(1), msg(2)]);
    expect(hasGap(s, msg(3))).toBe(false);
    expect(hasGap(s, msg(5))).toBe(true);
  });

  it('uses the contiguous high-water mark as the resync cursor, not the max seq', () => {
    const s = withOpenThread([msg(1), msg(2), msg(3), msg(6)]);
    expect(maxSeq(s.threads[10])).toBe(6);
    expect(contiguousMax(s.threads[10])).toBe(3);
    expect(needsFill(s.threads[10])).toBe(true);
    expect(hasGap(s, msg(7))).toBe(true);
  });

  it('captures pushes that arrive while the first page is loading, then reports the hole', () => {
    let s = reducer(initialState, { type: 'conversationsLoaded', conversations: [conversation] });
    s = reducer(s, { type: 'threadLoading', conversationId: 10 });
    s = reducer(s, { type: 'messageReceived', message: msg(9), me: ME });
    expect(hasGap(s, msg(10))).toBe(false); // still loading: don't fetch yet
    s = reducer(s, { type: 'threadLoaded', conversationId: 10, messages: [msg(6), msg(7)], hasOlder: true });
    expect(s.threads[10]!.messages.map((m) => m.seq)).toEqual([6, 7, 9]);
    expect(needsFill(s.threads[10])).toBe(true);
  });

  it('ignores conversations that are not open (nothing displayed, nothing to fill)', () => {
    const s = reducer(initialState, { type: 'conversationsLoaded', conversations: [conversation] });
    expect(hasGap(s, msg(42))).toBe(false);
  });
});

describe('send lifecycle', () => {
  const pending: PendingMessage = {
    clientMsgId: 'mine-1',
    conversationId: 10,
    body: 'hi',
    createdAt: 5,
    status: 'sending',
    sentAt: 5,
  };

  it('ack replaces the pending bubble with the confirmed message', () => {
    let s = withOpenThread([msg(1)]);
    s = reducer(s, { type: 'sendQueued', pending });
    s = reducer(s, { type: 'ackReceived', clientMsgId: 'mine-1', message: msg(2, { senderId: ME, clientMsgId: 'mine-1' }) });
    expect(s.pending).toHaveLength(0);
    expect(maxSeq(s.threads[10])).toBe(2);
    expect(s.conversations[10]!.lastMessage!.clientMsgId).toBe('mine-1');
  });

  it('a resync that already contains our message clears it from pending (ack was lost)', () => {
    let s = withOpenThread([msg(1)]);
    s = reducer(s, { type: 'sendQueued', pending });
    s = reducer(s, {
      type: 'messagesMerged',
      conversationId: 10,
      messages: [msg(2, { senderId: ME, clientMsgId: 'mine-1' })],
    });
    expect(s.pending).toHaveLength(0);
    expect(s.threads[10]!.messages).toHaveLength(2);
  });

  it('failed sends keep their text and can be retried with the same id', () => {
    let s = reducer(initialState, { type: 'sendQueued', pending });
    s = reducer(s, { type: 'sendFailed', clientMsgId: 'mine-1', error: 'nope' });
    expect(s.pending[0]!.status).toBe('failed');
    s = reducer(s, { type: 'retry', clientMsgId: 'mine-1', at: 9 });
    expect(s.pending[0]).toMatchObject({ status: 'sending', clientMsgId: 'mine-1', sentAt: 9 });
  });
});

describe('unread counts', () => {
  it('counts messages from others in inactive conversations and clears on open', () => {
    let s = reducer(initialState, { type: 'conversationsLoaded', conversations: [conversation] });
    s = reducer(s, { type: 'messageReceived', message: msg(1), me: ME });
    s = reducer(s, { type: 'messageReceived', message: msg(2, { senderId: ME }), me: ME });
    expect(s.unread[10]).toBe(1);
    s = reducer(s, { type: 'setActive', conversationId: 10 });
    expect(s.unread[10]).toBeUndefined();
  });

  it('does not let an older message overwrite the conversation preview', () => {
    let s = reducer(initialState, { type: 'conversationsLoaded', conversations: [conversation] });
    s = reducer(s, { type: 'messageReceived', message: msg(5), me: ME });
    s = reducer(s, { type: 'messageReceived', message: msg(4), me: ME });
    expect(s.conversations[10]!.lastSeq).toBe(5);
  });
});
