const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' });
const dateFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' });

/** Today: "14:05"; earlier: "12 Nov". Display only; ordering always uses seq. */
export function formatTime(epochMs: number): string {
  const d = new Date(epochMs);
  const now = new Date();
  return d.toDateString() === now.toDateString() ? timeFmt.format(d) : dateFmt.format(d);
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const chars = parts.length > 1 ? [parts[0]![0], parts[1]![0]] : [...(parts[0] ?? '?')].slice(0, 2);
  return chars.join('').toUpperCase();
}
