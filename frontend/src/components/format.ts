const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' });
const dateFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' });

export function formatTime(epochMs: number): string {
  const d = new Date(epochMs);
  const now = new Date();
  return d.toDateString() === now.toDateString() ? timeFmt.format(d) : dateFmt.format(d);
}

export function formatClock(epochMs: number): string {
  return timeFmt.format(new Date(epochMs));
}

export function sameDay(a: number, b: number): boolean {
  return new Date(a).toDateString() === new Date(b).toDateString();
}

const dayFmt = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long' });
const dayYearFmt = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });

export function formatDay(epochMs: number): string {
  const now = new Date();
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (sameDay(epochMs, now.getTime())) return 'Today';
  if (sameDay(epochMs, yesterday.getTime())) return 'Yesterday';
  const d = new Date(epochMs);
  return (d.getFullYear() === now.getFullYear() ? dayFmt : dayYearFmt).format(d);
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const chars = parts.length > 1 ? [parts[0]![0], parts[1]![0]] : [...(parts[0] ?? '?')].slice(0, 2);
  return chars.join('').toUpperCase();
}
