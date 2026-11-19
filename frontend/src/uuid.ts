/**
 * RFC 4122 v4 UUID for clientMsgId (the idempotency key of a send).
 *
 * crypto.randomUUID() only exists in secure contexts (https:// or localhost). Opening the app
 * from another device on the LAN as http://192.168.x.x:8080 is NOT a secure context, and calling
 * it there throws "crypto.randomUUID is not a function", so nobody on a phone could send anything.
 * crypto.getRandomValues() is available everywhere, so build the UUID from it as a fallback.
 */
export function uuidv4(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6]! & 0x0f) | 0x40; // version 4
  b[8] = (b[8]! & 0x3f) | 0x80; // RFC 4122 variant
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
