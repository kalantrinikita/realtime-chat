import { Client, type IMessage } from '@stomp/stompjs';
import type { ConnectionStatus, SendCommand, ServerEvent } from './types';

export interface SocketHandlers {
  onEvent(event: ServerEvent): void;
  onStatus(status: ConnectionStatus): void;
  /** The server says this login session is gone (logged out elsewhere, or expired). */
  onSessionEnded(): void;
  /** Must resolve to true if the login session is still valid. See beforeConnect for why. */
  checkSession(): Promise<boolean>;
}

/** Personal queues; the server refuses subscriptions to anything else. */
const QUEUES = ['/user/queue/ready', '/user/queue/messages', '/user/queue/acks', '/user/queue/errors'];
const HEARTBEAT_MS = 10_000;
const BACKOFF_BASE_MS = 500;
const BACKOFF_MAX_MS = 30_000;
/** Server close code meaning "you were logged out": don't reconnect. */
const LOGGED_OUT = 4001;
/** Protocol version this code understands. The server sends its own in "ready". */
const PROTOCOL_VERSION = 1;

/**
 * A STOMP connection that stays connected: reconnects with jittered backoff, detects dead
 * connections through STOMP heartbeats, and tells the UI its status. Recovering messages (catch-up,
 * resending pending ones) is not done here: useChat does it when the server says "ready".
 */
export class ChatSocket {
  private readonly client: Client;
  private attempt = 0;
  private ready = false;
  private droppedBeforeReady = false;
  private stopped = false;
  private wakeUp: (() => void) | undefined;

  constructor(
    url: string,
    private readonly handlers: SocketHandlers,
  ) {
    this.client = new Client({
      brokerURL: url,
      // The server sends a heartbeat every 10 s. If nothing arrives for a while, the connection is
      // treated as dead. That's how a laptop waking from sleep (socket still "open", but every byte
      // sent into it vanishes) gets noticed instead of hanging silently.
      heartbeatIncoming: HEARTBEAT_MS,
      heartbeatOutgoing: HEARTBEAT_MS,
      // On a dead connection a normal close() waits for a closing handshake that never comes;
      // drop the socket immediately instead so the reconnect starts right away.
      discardWebsocketOnCommFailure: true,
      connectionTimeout: 8_000,
      // The library's own delay is fixed; the real, randomised delay is added in beforeConnect.
      reconnectDelay: 1,
      debug: () => {},
      beforeConnect: () => this.beforeConnect(),
      onConnect: () => this.onConnect(),
      onWebSocketClose: (event) => this.onClose(event),
      onStompError: (frame) => console.warn('STOMP error from server:', frame.headers['message']),
    });
  }

  start(): void {
    this.stopped = false;
    window.addEventListener('online', this.reconnectNow);
    document.addEventListener('visibilitychange', this.onVisibility);
    this.handlers.onStatus('connecting');
    this.client.activate();
  }

  stop(): void {
    this.stopped = true;
    window.removeEventListener('online', this.reconnectNow);
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.wakeUp?.();
    void this.client.deactivate();
  }

  /** Returns false if not connected; the caller keeps the message pending and resends on "ready". */
  send(command: SendCommand): boolean {
    if (!this.client.connected || !this.ready) return false;
    this.client.publish({
      destination: '/app/send',
      body: JSON.stringify(command),
      headers: { 'content-type': 'application/json' },
    });
    return true;
  }

  /**
   * Runs before every connection attempt.
   *
   * 1. Session check. Browsers deliberately hide the HTTP status of a failed WebSocket handshake:
   *    a 401 "not signed in" and a server that's simply down both look like close code 1006. If the
   *    last attempt died before "ready", ask the REST API which it was, so an expired session shows
   *    the login screen instead of retrying forever.
   * 2. Exponential backoff with full jitter (a random wait between 0 and the cap). When the server
   *    restarts, every client loses its connection at the same instant; without the randomness
   *    they would all reconnect in the same instant too and hammer the fresh server.
   */
  private async beforeConnect(): Promise<void> {
    if (this.droppedBeforeReady) {
      this.droppedBeforeReady = false;
      const valid = await this.handlers.checkSession().catch(() => true);
      if (!valid) {
        void this.client.deactivate(); // the pending connect sees the client inactive and stops
        this.handlers.onSessionEnded();
        return;
      }
    }
    if (this.attempt > 0) {
      this.handlers.onStatus('reconnecting');
      const cap = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** (this.attempt - 1));
      await new Promise<void>((resolve) => {
        this.wakeUp = resolve;
        setTimeout(resolve, Math.random() * cap);
      });
      this.wakeUp = undefined;
    }
    this.attempt += 1;
  }

  /**
   * Subscribe first, then say hello. SUBSCRIBE has no acknowledgement in STOMP; the server answers
   * /app/hello only after it has processed the subscriptions (it handles each connection's frames
   * in order), so "ready" is the signal that it's now safe to fetch missed messages.
   */
  private onConnect(): void {
    for (const queue of QUEUES) {
      this.client.subscribe(queue, (message) => this.onFrame(message));
    }
    this.client.publish({ destination: '/app/hello', body: '{}', headers: { 'content-type': 'application/json' } });
  }

  private onFrame(message: IMessage): void {
    let event: ServerEvent;
    try {
      event = JSON.parse(message.body) as ServerEvent;
    } catch {
      return;
    }
    if (event.type === 'ready') {
      if (event.protocol !== PROTOCOL_VERSION) {
        this.reloadForNewVersion(event.protocol);
        return;
      }
      this.ready = true;
      this.attempt = 0;
      this.handlers.onStatus('open');
    }
    this.handlers.onEvent(event);
  }

  /**
   * The server was updated to a protocol this tab's code doesn't understand, so reload once to get
   * the new code (index.html is never cached). If a reload already happened, stop instead of looping.
   */
  private reloadForNewVersion(serverVersion: number): void {
    this.stop();
    const key = 'chat.reloadedForProtocol';
    try {
      if (sessionStorage.getItem(key) !== String(serverVersion)) {
        sessionStorage.setItem(key, String(serverVersion));
        window.location.reload();
        return;
      }
    } catch {
      // No session storage: don't risk a reload loop.
    }
    console.error(`This app version doesn't support server protocol ${serverVersion}. Refresh the page.`);
  }

  private onClose(event: CloseEvent): void {
    const wasReady = this.ready;
    this.ready = false;
    if (this.stopped) return;
    if (event.code === LOGGED_OUT) {
      void this.client.deactivate();
      this.handlers.onSessionEnded();
      return;
    }
    if (!wasReady) this.droppedBeforeReady = true;
    this.handlers.onStatus('reconnecting');
  }

  /** Network came back or the tab became visible: skip the rest of the backoff wait. */
  private readonly reconnectNow = (): void => {
    if (this.stopped || this.client.connected) return;
    this.attempt = Math.min(this.attempt, 1);
    this.wakeUp?.();
  };

  private readonly onVisibility = (): void => {
    if (document.visibilityState === 'visible') this.reconnectNow();
  };
}

export function socketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/ws`;
}
