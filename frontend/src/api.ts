import type { Conversation, Message, User } from './types';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
  });
  if (res.status === 204) return undefined as T;
  const data: unknown = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = (data as { error?: { code?: string; message?: string } }).error;
    throw new ApiError(res.status, err?.code ?? 'error', err?.message ?? res.statusText);
  }
  return data as T;
}

export const api = {
  config: () => request<{ inviteRequired: boolean }>('GET', '/api/config'),
  me: () => request<{ user: User }>('GET', '/api/me'),
  register: (body: { email: string; password: string; displayName?: string; inviteCode?: string }) =>
    request<{ user: User }>('POST', '/api/auth/register', body),
  login: (body: { email: string; password: string }) => request<{ user: User }>('POST', '/api/auth/login', body),
  logout: () => request<void>('POST', '/api/auth/logout', {}),
  conversations: () => request<{ conversations: Conversation[] }>('GET', '/api/conversations'),
  startConversation: (email: string) =>
    request<{ conversation: Conversation }>('POST', '/api/conversations', { email }),
  messages: (conversationId: number, params: { after?: number; before?: number; limit?: number }) => {
    const q = new URLSearchParams();
    if (params.after !== undefined) q.set('after', String(params.after));
    if (params.before !== undefined) q.set('before', String(params.before));
    if (params.limit !== undefined) q.set('limit', String(params.limit));
    return request<{ messages: Message[] }>('GET', `/api/conversations/${conversationId}/messages?${q}`);
  },
};
