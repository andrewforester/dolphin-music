const enc = new TextEncoder();

export function secretMatches(presented: string | null, expected: string | undefined): boolean {
  if (!presented || !expected) return false;
  const a = enc.encode(presented);
  const b = enc.encode(expected);
  let diff = a.length ^ b.length;
  const n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

function parseAccessCodes(
  raw: string | undefined,
): { label: string; code: string }[] {
  return (raw ?? "")
    .split(",")
    .map((entry) => {
      const at = entry.indexOf(":");
      return at < 0 ? null : {
        label: entry.slice(0, at).trim(),
        code: entry.slice(at + 1).trim(),
      };
    })
    .filter((e): e is { label: string; code: string } => e !== null && e.label !== "" && e.code !== "");
}

export function matchAccessCode(
  presented: string | null,
  raw: string | undefined,
): string | null {
  let label: string | null = null;
  for (const entry of parseAccessCodes(raw)) {
    if (secretMatches(presented, entry.code) && label === null) {
      label = entry.label;
    }
  }
  return label;
}
