// Server-side access to the collector's review and lab API. Like the catalog, the
// collector is never reached from the browser: pages and server actions call it
// over the private network.

const COLLECTOR_URL = process.env.COLLECTOR_URL ?? "http://localhost:8091";

export type Reading = { currency: string; read: number | null; reference: number | null };

export type Recipe = Record<string, string>;

export type Attempt = {
  by: "recipe" | "library" | "model" | "human";
  recipeVersion: number;
  recipe: Recipe;
  accepted: boolean;
  error: string | null;
  read: number;
  expected: number;
  matched: number;
  readings: Reading[];
  millis: number | null;
  promptTokens: number | null;
  outputTokens: number | null;
  modelReply: string | null;
};

export type RepairCase = {
  snapshot: string;
  archiveUrl: string;
  ratesDate: string;
  outcome: "REUSED" | "HEALED" | "HUMAN_NEEDED";
  before: Attempt;
  proposals: Attempt[];
  after: Attempt | null;
};

export type Action = "CONFIRM_REPAIR" | "REJECT_REPAIR" | "NEW_RECIPE" | "DISMISS_PAGE";

export type Decision = {
  source: string;
  snapshot: string;
  action: Action;
  recipe: Recipe | null;
  matched: number | null;
  expected: number | null;
  note: string;
  decidedAt: string;
};

export type CaseSummary = {
  source: string;
  snapshot: string;
  ratesDate: string;
  outcome: RepairCase["outcome"];
  error: string | null;
  proposals: number;
  wrongNumbersBlocked: number;
  fromVersion: number | null;
  toVersion: number | null;
  decision: Decision | null;
};

export type Listing = {
  reviewsEnabled: boolean;
  cases: CaseSummary[];
  models: Record<string, string>;
  generatedAt: Record<string, string>;
};

export type CaseDetail = {
  source: string;
  pageUrl: string;
  model: string;
  repair: RepairCase;
  pageAvailable: boolean;
  decisions: Decision[];
};

export type LabPage = { id: string; origin: "collected" | "archive"; when: string };

export type LabInfo = {
  source: string;
  reviewsEnabled: boolean;
  pages: LabPage[];
  recipes: { version: number; settings: Recipe }[];
  models: string[];
};

export type LabResult = {
  pageId: string;
  by: "recipe" | "model" | "human";
  model: string | null;
  recipe: Recipe;
  accepted: boolean;
  error: string | null;
  ratesDate: string | null;
  read: number;
  expected: number;
  matched: number;
  readings: Reading[];
  millis: number | null;
  promptTokens: number | null;
  outputTokens: number | null;
  modelReply: string | null;
  promptError: string | null;
  digest: string | null;
};

export async function collector<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(`${COLLECTOR_URL}${path}`, { cache: "no-store" });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

export type Posted<T> = { ok: true; data: T } | { ok: false; status: number; error: string };

export async function collectorPost<T>(path: string, body: unknown, key?: string): Promise<Posted<T>> {
  try {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (key) headers["X-Review-Key"] = key;
    const res = await fetch(`${COLLECTOR_URL}${path}`, {
      method: "POST",
      headers,
      body: JSON.stringify(body),
      cache: "no-store",
      // A model run takes about a minute on a laptop.
      signal: AbortSignal.timeout(10 * 60 * 1000),
    });
    const text = await res.text();
    if (!res.ok) {
      let error = text;
      try {
        const j = JSON.parse(text) as { error?: string; detail?: string; message?: string };
        error = j.detail ?? j.error ?? j.message ?? text;
      } catch {
        // not JSON: keep the text
      }
      return { ok: false, status: res.status, error: error || `HTTP ${res.status}` };
    }
    return { ok: true, data: JSON.parse(text) as T };
  } catch (e) {
    return { ok: false, status: 0, error: e instanceof Error ? e.message : String(e) };
  }
}

/** "20200103000915" -> "3 Jan 2020" */
export function snapshotDate(ts: string): string {
  const d = new Date(Date.UTC(+ts.slice(0, 4), +ts.slice(4, 6) - 1, +ts.slice(6, 8)));
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

export const OUTCOME_TEXT: Record<RepairCase["outcome"], string> = {
  REUSED: "an earlier recipe read it",
  HEALED: "repaired by the model, verified",
  HUMAN_NEEDED: "needs a person",
};

export const ACTION_TEXT: Record<Action, string> = {
  CONFIRM_REPAIR: "repair confirmed",
  REJECT_REPAIR: "repair rejected",
  NEW_RECIPE: "recipe written by a person",
  DISMISS_PAGE: "page dismissed",
};
