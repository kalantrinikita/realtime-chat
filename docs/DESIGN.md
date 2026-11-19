# Design notes

Why the system is built this way: decisions, trade-offs, the failure modes that were anticipated,
and the path to production and long-term maintenance. The README covers what it is and how to run it.

1. [Goals and non-goals](#1-goals-and-non-goals)
2. [Architecture decisions](#2-architecture-decisions)
3. [What Spring/STOMP does vs. what this app adds](#3-what-springstomp-does-vs-what-this-app-adds)
4. [Message delivery semantics](#4-message-delivery-semantics)
5. [STOMP pitfalls handled](#5-stomp-pitfalls-handled)
6. [Security](#6-security)
7. [Edge-case catalogue](#7-edge-case-catalogue)
8. [Production roadmap](#production-roadmap)
9. [Long-term maintenance](#9-long-term-maintenance)
10. [Testing strategy](#10-testing-strategy)

---

## 1. Goals and non-goals

**Goals (MVP):** 1:1 real-time messaging between registered users across devices and tabs;
messages are ordered, durable, never duplicated and never silently lost; runs with one
`docker compose up`.

**Non-goals for now:** group chats, media, read receipts, typing indicators, presence, push
notifications, E2E encryption, more than one app instance. The design leaves room for each (section 8).

## 2. Architecture decisions

| Decision | Why | Alternative considered |
|---|---|---|
| **Spring Boot 4.1** (Java 25 runtime, Java 21 bytecode) | Industry-standard, well understood by most Java teams, and easy to change live. 4.1 is the supported line (3.5's open-source support ended June 2026). | Plain JDK with a hand-written WebSocket server: an earlier iteration did this; more depth on the protocol, more code to own. |
| **STOMP over WebSocket, Spring simple broker** | Gives framing, subscriptions, per-user destinations (`convertAndSendToUser`) and heartbeats out of the box. | Raw `TextWebSocketHandler` with our own routing; an external broker (RabbitMQ) via `enableStompBrokerRelay` (the multi-instance step, section 8). |
| **One container serves UI + REST + WebSocket** | One origin means the HttpOnly session cookie authenticates both channels with zero CORS setup; two containers total is easy to run and explain. | Separate web server container (only added in production, for TLS). |
| **PostgreSQL + JdbcTemplate (no ORM)** | Ordering correctness depends on exactly which statement takes which row lock and when the transaction commits (section 4). Plain SQL keeps that visible in one method. | JPA/Hibernate: hides flush timing; harder to reason about the lock. |
| **TransactionTemplate, not `@Transactional`** on the send path | It needs to roll back in a non-exceptional case (duplicate retry), and the transaction boundary *is* the correctness argument, so it should be visible. | `@Transactional` + `setRollbackOnly` via `TransactionAspectSupport`. |
| **Flyway** migrations | Versioned, runs at startup, takes a database lock so two instances starting together can't both migrate. | Hand-written migrator. |
| **Server-side sessions in an HttpOnly cookie** (not JWT, not Spring Security) | Logout takes effect immediately, including on already-open sockets. The browser WebSocket API can't send an Authorization header but does send cookies. One interceptor is all this app needs; Spring Security's defaults would be more to configure and explain than the feature. | JWT in a STOMP CONNECT header (needs a revocation list for logout). |
| **Virtual threads** (`spring.threads.virtual.enabled`) | Request threads mostly wait on JDBC; virtual threads make that cheap. The DB pool size is the real concurrency limit. | Default platform thread pool. |
| **PBKDF2 password hashing** (JDK only) | No native dependency; parameters are stored per hash so they can be raised later. Concurrent hashes are capped at cores-1 so a login burst can't starve chat traffic on the shared carrier threads. | bcrypt/Argon2 via Spring Security crypto. |

## 3. What Spring/STOMP does vs. what this app adds

The brief asks for the core to be built, not wrapped. Spring is used for the transport; the
messaging guarantees and rules are this app's own code.

| Spring provides | This app adds (where) |
|---|---|
| WebSocket handshake + frames, STOMP parsing | Authentication on the handshake via the session cookie, rejecting with HTTP 401 before upgrading (`SessionHandshakeInterceptor`) |
| Routing a message to a user's sessions | Which users receive what: both participants, every tab, never outsiders (`ChatMessagingController`, `ChatService`) |
| Subscriptions | A whitelist of subscribable/sendable destinations, closing eavesdropping and spoofing holes (`StompDestinationGuard`) |
| In-memory delivery, no persistence | Save before deliver; per-conversation gap-free sequence numbers; idempotent retries (`ChatService#sendMessage`) |
| Nothing about reconnects | Catch-up protocol: subscribe → `/app/hello` → `ready` → fetch `after=seq` → resend pending (`useChat.ts`, `realtime.ts`) |
| Heartbeats | Client-side zombie detection + jittered exponential backoff + session-expiry detection (`realtime.ts`) |
| Per-session buffering | Limits that turn slow consumers into disconnects, made safe by catch-up (`WebSocketConfig`) |
| Nothing about logout | Closing live sockets of a logged-out session with code 4001 (`LiveSessionRegistry`) |

## 4. Message delivery semantics

### 4.1 Ordering: per-conversation seq under a row lock

```sql
UPDATE conversations SET last_seq = last_seq + 1, last_message_at = now()
 WHERE id = ? AND (user_low = ? OR user_high = ?)
RETURNING last_seq
```

The row lock is held until commit, so within a conversation **seq order = commit order**. That
makes `GET …/messages?after=N` a cursor that can't skip anything. Rejected alternatives:

- **Timestamps:** clocks drift between devices and servers, millisecond ties happen, and NTP can
  step a clock backwards.
- **Global auto-increment id as the cursor:** ids are handed out at insert but become visible at
  commit. Tx A takes id 10, tx B takes 11 and commits first; a reader asking "after 9" gets 11,
  moves on, and never sees 10. This bug is invisible in low-traffic testing.

Cost: messages within *one* conversation are serialised. With two people typing that is
negligible, and different conversations never wait on each other.

### 4.2 No duplicates: clientMsgId

The browser creates a UUID per message and reuses it on every retry. `UNIQUE (sender_id,
client_msg_id)` turns the second insert into a no-op (`ON CONFLICT … DO NOTHING`), and the
transaction is then **rolled back**, which also undoes `last_seq + 1`. Committing it would burn a
number and leave a permanent hole that clients would try to fill forever. At-least-once sending plus
idempotent storage means each message is effectively stored exactly once.

### 4.3 Save, then ack, then push

The sending tab gets an ack on `/user/queue/acks` (only that session: `broadcast = false`). Every
session of both participants gets the message on `/user/queue/messages`, including the sender's
other tabs; the sending tab receives it too and de-duplicates by seq. All of this happens **after
commit**, so nobody ever sees a message that is then rolled back. A retried (duplicate) send is
acked again but not pushed again.

### 4.4 Recovery on the client

- **Subscribe, then hello, then fetch.** STOMP SUBSCRIBE has no acknowledgement. The client sends
  `/app/hello` after its SUBSCRIBE frames; the server processes each session's frames in order
  (`setPreserveReceiveOrder(true)`), so its `ready` reply proves the subscriptions are live. Only
  then does the client fetch missed messages. Fetching first leaves a window where a message is in
  neither the fetch nor the push stream.
- **Contiguous high-water mark** as the cursor: holding 1..5 and 8, "after 8" would never return
  6–7, but "after 5" does.
- **Gap detection on every push:** a seq that skips ahead triggers a fetch of the hole.
- **History-load race:** the thread is created empty *before* its first page is requested, so
  pushes arriving during the request are kept, and any hole is filled afterwards.
- **Pending messages** survive disconnects, are resent on `ready` with the same id, and are
  cleared by a catch-up that already contains them (the ack was lost). No ack for 15 s on a healthy
  connection shows *Not sent · Retry*, which is safe to press for the same reason.

## 5. STOMP pitfalls handled

| Pitfall | What goes wrong | Handling |
|---|---|---|
| Inbound thread pool reorders frames | Two quick sends from one tab stored in reverse order; `hello` processed before the SUBSCRIBEs sent ahead of it | `registry.setPreserveReceiveOrder(true)` |
| Outbound thread pool reorders frames | Client sees #8 before #7 (then a needless gap fetch) | `registry.setPreservePublishOrder(true)` |
| User destinations are guessable | `/user/queue/messages` resolves to `/queue/messages-user{sessionId}`; Tomcat session ids are small sequential numbers, so anyone could subscribe to someone else's queue | `StompDestinationGuard` allows only the four `/user/queue/*` subscriptions |
| SEND straight to the broker | The simple broker accepts SEND to `/queue/...`, so a client could inject fake messages into another user's queue, bypassing validation and storage | Guard allows SEND only to `/app/*` |
| No SUBSCRIBE receipt | Messages lost between subscribe and catch-up | hello/ready handshake (4.4) |
| Slow consumers | Spring buffers per session; a backgrounded phone grows the buffer without limit | `sendTimeLimit` 10 s, `sendBufferSizeLimit` 512 KB, then disconnect; catch-up makes it lossless |
| Half-open connections | Laptop sleeps or network changes without a TCP FIN; both sides think they're connected | Heartbeats 10 s both ways; client drops the socket at once on missed heartbeats (`discardWebsocketOnCommFailure`) |
| TLS-terminating proxy | App sees `http`, the browser says `Origin: https://…`, so the same-origin check rejects every WebSocket | `server.forward-headers-strategy: native` + explicit `ALLOWED_ORIGINS` in production |
| Browser hides handshake status | A 401 looks the same as "server down" (close 1006), so the client retries forever | After a close before `ready`, the client asks `/api/me`; 401 shows the login screen |
| Exceptions in interceptors with ordered receive | Not turned into ERROR frames (Spring issue #33937) | Guard drops frames (returns null) instead of throwing |

## 6. Security

| Threat | Mitigation |
|---|---|
| Password theft from a DB leak | PBKDF2-HMAC-SHA256, 600k iterations, per-user salt, parameters stored per hash. |
| User enumeration via timing | Unknown emails still run a full hash against a dummy; same error text either way. (Sign-up still reveals "email taken", an accepted trade-off.) |
| Duplicate accounts by case/locale | Emails lower-cased with `Locale.ROOT` (Turkish dotless-i) + DB CHECK that stored emails are lower-case. |
| Passwords failing across OSes | NFKC normalisation (macOS decomposes "é", Windows doesn't). |
| CSRF on REST | `SameSite=Lax` cookie + every POST `consumes = application/json` (HTML forms can't send it; cross-origin fetch with it needs a preflight we never approve). |
| Cross-site WebSocket hijacking | Spring's same-origin check on `/ws` (WebSockets aren't covered by CORS) + allow-list in production. |
| Eavesdropping / spoofing via STOMP | Destination guard (section 5). |
| XSS | React escapes text; CSP `default-src 'self'` with no inline script; `X-Frame-Options: DENY`. |
| Session theft | HttpOnly, Secure + `__Host-` prefix in production, only a hash stored, 30-day expiry, logout closes live sockets (4001). |
| Resource exhaustion | STOMP message size limit (64 KB), send buffer/time limits, 4000-character messages, 128-character passwords, DB pool as a bulkhead. |
| Open sign-up on a public URL | `INVITE_CODE` (required by the production overlay); no "list users" endpoint, so a chat needs the exact email. |
| Not done yet | Login rate limiting, audit log, email verification (section 8). |

## 7. Edge-case catalogue

| # | Scenario | What goes wrong if ignored | Handling |
|---|---|---|---|
| 1 | Two people send at the same time | Duplicate or out-of-order seq | Row-locked counter; tested with 200 concurrent senders |
| 2 | Message sent while offline | Lost, or duplicated when retried | Pending queue, resend on `ready`, server dedupe by clientMsgId |
| 3 | Ack lost but message stored | Retry duplicates it | Idempotent insert returns the original; catch-up also clears pending |
| 4 | Retry after the seq was incremented | Permanent hole, endless gap fetches | Rollback on conflict; tested |
| 5 | Push missed during reconnect | Silent hole in history | Hello/ready, contiguous-cursor catch-up, gap detection |
| 6 | Alice and Bob start a chat simultaneously | Two conversations for one pair | Canonical (low, high) + UNIQUE + ON CONFLICT; tested with 20 threads |
| 7 | Server restart / deploy | All clients reconnect at once | Full-jitter exponential backoff; graceful shutdown |
| 8 | Laptop sleeps / network switch | Zombie socket, no messages, UI says "Live" | Heartbeats, discard on failure, reconnect on `online`/visibility |
| 9 | Emoji and length limits | JS/Java count UTF-16 units (an emoji = 2) | Limits in code points on client, server and DB CHECK |
| 10 | NUL character in a message | Postgres rejects it → 500 | Validation error instead |
| 11 | Logged out in another tab | Socket keeps streaming to a logged-out tab | Logout closes that session's sockets with 4001 |
| 12 | Expired session while socket reconnects | Infinite reconnect loop | `/api/me` check after a close before ready |
| 13 | `crypto.randomUUID` over http://LAN-IP | Throws, so phone users can't send | `getRandomValues` fallback |
| 14 | IME composition (CJK input) | The Enter that confirms a character sends the message | Ignore Enter while composing |
| 15 | Old tab after a deploy | New payload shapes break old JavaScript | Additive changes only, unknown fields ignored, `protocol` in `ready` |
| 16 | Cached index.html after a deploy | Users keep the old bundle | `index.html` no-cache, hashed `/assets` cached a year |
| 17 | `docker stop` | SIGKILL mid-request, no clean close | Exec-form entrypoint, graceful shutdown, `stop_grace_period` |
| 18 | Postgres not ready at startup | Flyway fails, crash loop | Compose `depends_on: service_healthy` |
| 19 | DB volume from an older schema | Flyway refuses to start | Documented: `docker compose down -v` |
| 20 | Changing `DB_PASSWORD` later | App can't log in | Documented (only applied at volume creation) |
| 21 | Windows CRLF checkout | Healthcheck script fails | `.gitattributes` forces LF |
| 22 | Integration tests pointed at a real DB | Data wiped | Tests refuse DB names without "test" |
| 23 | Login burst | Hashing starves chat traffic | Semaphore around PBKDF2 |

## Production roadmap

1. **Deploy to a domain** (ready): `docker-compose.prod.yml` on one VM, Caddy auto-HTTPS. Next:
   GitHub Actions (test, build image tagged by commit SHA, deploy, smoke-test `/api/health` and a
   STOMP connect, roll back by redeploying the previous SHA) and Terraform (VM, static IP, firewall
   80/443, separate disk for Postgres data with `prevent_destroy`, DNS record).
2. **Rate limiting** on login/register and on `/app/send` (token bucket per IP and per user).
3. **More than one app instance.** The simple broker lives in one JVM, so a sender and recipient on
   different instances wouldn't see each other's pushes. Switch to `enableStompBrokerRelay` with
   RabbitMQ (STOMP plugin), which Spring supports with a configuration change. The user registry
   then needs to be shared too (`setUserDestinationBroadcast` / `setUserRegistryBroadcast`). No
   sticky sessions needed: all state is in Postgres and catch-up covers instance switches.
4. **Read receipts:** one `last_read_seq` per member per conversation (an integer, not per message), pushed as an event.
5. **Presence and typing:** ephemeral, never stored, throttled; presence debounced so a page refresh doesn't flash "offline".
6. **Group chats:** a `conversation_members` table instead of `user_low/user_high`; fan-out by membership; a different seq allocator for very busy groups.
7. **Data growth:** partition `messages` by time or by conversation hash; archive cold history; build indexes concurrently outside migrations.
8. **Observability:** Spring Boot Actuator + metrics for open sessions, send→ack latency, slow-consumer disconnects, pool wait time.
9. **Push notifications** for offline users (needs an external service; out of scope by the brief).

## 9. Long-term maintenance

- **Protocol evolution:** additive changes only; breaking changes bump `protocol` in `ready`, and
  the server keeps the old shape for a deprecation window (tabs stay open for days).
- **Schema evolution:** forward-only Flyway migrations; expand → migrate data → contract across
  separate deploys so old and new code can run side by side during a rolling deploy.
- **Framework upgrades:** Spring Boot minors ship every 6 months with ~12 months of open-source
  support. Plan one upgrade per minor. The code avoids deprecated APIs and keeps business logic
  (ChatService) framework-light so upgrades touch configuration, not logic.
- **Database:** Postgres image pinned to 17; major upgrades need `pg_upgrade` or dump/restore.

## 10. Testing strategy

- **Unit tests** for rules that are easy to get subtly wrong: the STOMP destination guard,
  message validation (code points, NUL), password hashing (normalisation, stored parameters).
- **Integration tests against real Postgres** for guarantees that are database behaviour:
  200 parallel senders, 10 parallel retries, 20 parallel conversation starts, access control, paging.
- **Frontend reducer tests** for ordering, gaps, races and ack reconciliation.
- **STOMP client test** (during development): the real `realtime.ts` against a fake STOMP server,
  covering subscribe-before-hello, send format, reconnect after a server restart, no reconnect
  after 4001, and the expired-session path.
- **Next:** a `@SpringBootTest` end-to-end test with Spring's `WebSocketStompClient` (two users,
  real broker, real database) and a browser test with Playwright.
