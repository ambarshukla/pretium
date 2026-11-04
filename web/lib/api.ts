// Server-side access to the catalog API. Pages are server components, so the
// browser never talks to the catalog directly: only this Next.js server does,
// over the private network, and the catalog stays off the public internet.

const CATALOG_URL = process.env.CATALOG_URL ?? "http://localhost:8082";

export async function api<T>(path: string): Promise<T> {
  const res = await fetch(`${CATALOG_URL}${path}`, {
    // The data changes at most a few times a day; five minutes of caching keeps
    // pages fast without ever showing anything meaningfully stale.
    next: { revalidate: 300 },
  });
  if (!res.ok) {
    throw new Error(`${path}: HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export async function apiOrNull<T>(path: string): Promise<T | null> {
  try {
    return await api<T>(path);
  } catch {
    return null;
  }
}

export type Corridor = {
  send: string;
  sendName: string;
  receive: string;
  receiveName: string;
  latestPeriod: string;
  providers: number;
  cheapestPct: number;
  averagePct: number;
};

export type Finding = { rule: string; severity: "INFO" | "WARN" | "ERROR"; detail: string };

export type Quote = {
  provider: string;
  providerType: string | null;
  paymentInstrument: string | null;
  accessPoint: string | null;
  speed: string | null;
  sendAmount: number;
  sendCurrency: string;
  fee: number | null;
  fxRate: number | null;
  fxMarginPct: number | null;
  totalCostPct: number;
  receiveAmount: number | null;
  interbankRate: number | null;
  receiveAtInterbank: number | null;
  transparent: boolean;
  observedOn: string | null;
  status: string;
  findings: string;
  sourceId: string;
  sourceRecordId: string;
  runId: string;
  evidenceSha256: string;
};

export type Comparison = {
  send: string;
  sendName: string;
  receive: string;
  receiveName: string;
  amountUsd: number;
  period: string;
  periods: string[];
  quotes: Quote[];
  attribution: string;
};

export type HistoryPoint = {
  period: string;
  providers: number;
  cheapestPct: number;
  medianPct: number;
  averagePct: number;
  averageMarginPct: number | null;
};

export function findings(q: { findings: string }): Finding[] {
  try {
    return JSON.parse(q.findings) as Finding[];
  } catch {
    return [];
  }
}

/** "2025_3Q" -> "Q3 2025" */
export function period(p: string): string {
  const m = /^(\d{4})_(\d)Q$/.exec(p);
  return m ? `Q${m[2]} ${m[1]}` : p;
}

export function num(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined) return "–";
  return Number(v).toLocaleString("en-GB", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
