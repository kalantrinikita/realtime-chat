// Shapes shared with the backend. Kept in one file so a protocol change is a one-file diff on
// this side; the matching Java records live in ChatModels.java and RealtimeEvents.java.

export interface User {
  id: number;
  email: string;
  displayName: string;
}

export interface Message {
  id: number;
  conversationId: number;
  /** Per-conversation, gap-free, strictly increasing. The ONLY ordering key. */
  seq: number;
  senderId: number;
  clientMsgId: string;
  body: string;
  /** Epoch ms, display only. Never sort by it: clocks drift and ties happen. */
  createdAt: number;
}

export interface Peer {
  id: number;
  email: string;
  displayName: string;
}

export interface Conversation {
  id: number;
  peer: Peer;
  lastSeq: number;
  lastMessage: Message | null;
  updatedAt: number;
}

/** A message typed locally that the server has not acknowledged yet. */
export interface PendingMessage {
  clientMsgId: string;
  conversationId: number;
  body: string;
  createdAt: number;
  status: 'sending' | 'failed';
  error?: string;
  /** When we last put it on the wire; used to time out a lost ack. */
  sentAt: number;
}

export type ConnectionStatus = 'connecting' | 'open' | 'reconnecting';

/** Server -> client STOMP payloads. One handler for every queue. */
export type ServerEvent =
  | { type: 'ready'; protocol: number; user: User }
  | { type: 'ack'; clientMsgId: string; message: Message }
  | { type: 'message'; message: Message }
  | { type: 'error'; clientMsgId?: string | null; code: string; message: string; retryable: boolean };

/** Client -> server, published to /app/send. */
export interface SendCommand {
  clientMsgId: string;
  conversationId: number;
  body: string;
}
