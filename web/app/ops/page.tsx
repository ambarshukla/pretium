import Link from "next/link";
import { apiOrNull, findings, num, period, type Finding } from "@/lib/api";

export const dynamic = "force-dynamic";

type SourceHealth = {
  sourceId: string;
  lastStatus: string;
  lastRunAt: string;
  lastMessage: string | null;
  lastHttpStatus: number;
  recipeVersion: number;
  lastGoodAt: string | null;
  runs7d: number;
  failures7d: number;
  freshnessTargetHours: number;
  ageHours: number | null;
  fresh: boolean;
};

type Run = {
  runId: string;
  sourceId: string;
  status: string;
  httpStatus: number;
  finishedAt: string;
  bytes: number;
  sha256: string | null;
  recipeVersion: number;
  extracted: number;
  message: string | null;
};

type Quality = {
  byStatus: { sourceId: string; status: string; prices: number }[];
  byRule: { rule: string; severity: Finding["severity"]; prices: number }[];
  rates: { sourceId: string; rates: number; from: string; to: string }[];
};

type Quarantined = {
  sourceId: string;
  sourceRecordId: string;
  evidenceSha256: string;
  period: string;
  sendName: string;
  receiveName: string;
  provider: string;
  amountUsd: number;
  totalCostPct: number;
  findings: string;
};

type Break = {
  base: string;
  quote: string;
  date: string;
  channel: string;
  reference: string;
  kind: string;
  value: number | null;
  expected: number | null;
  detectedAt: string;
  resolvedAt: string | null;
};

type ChannelGroup = {
  reference: string;
  channels: string[];
  date: string | null;
  agreeing: number;
  rows: { base: string; quote: string; rates: Record<string, number>; agree: boolean }[];
};
const CHANNEL_LABEL: Record<string, string> = {
  "cnb-fixing-txt": "Text file",
  "cnb-fixing-html": "Web page (scraped)",
};
type LatestRate = { currency: string; date: string; sdmx: number | null; xml: number | null; html: number | null };

type Payout = "LOCAL" | "SEND_CURRENCY" | "USD" | "EUR" | "INVERTED" | "UNEXPLAINED" | "NO_REFERENCE";

type Benchmark = {
  period: string;
  reference: string;
  tolerancePct: number;
  quotes: number;
  byPayout: Record<Payout, number>;
  localMedianAbsDeviationPct: number | null;
  localWithinHalfPercentPct: number | null;
  pairs: { pair: string; quotes: number; medianAbsDeviationPct: number; maxAbsDeviationPct: number }[];
  flagged: {
    payout: Payout;
    recordId: string;
    corridor: string;
    provider: string;
    observedOn: string;
    pair: string;
    surveyRate: number;
    ecbRate: number;
    deviationPct: number;
  }[];
};

const when = (s: string | null) => (s ? new Date(s).toISOString().replace("T", " ").slice(0, 16) + " UTC" : "never");

export default async function OpsPage() {
  const [sources, runs, quality, quarantine, breaks, latest, benchmark, groups] = await Promise.all([
    apiOrNull<SourceHealth[]>("/api/v1/ops/sources"),
    apiOrNull<Run[]>("/api/v1/ops/runs?limit=20"),
    apiOrNull<Quality>("/api/v1/ops/quality"),
    apiOrNull<Quarantined[]>("/api/v1/ops/quarantine?limit=25"),
    apiOrNull<Break[]>("/api/v1/ops/breaks?limit=50"),
    apiOrNull<LatestRate[]>("/api/v1/rates/latest"),
    apiOrNull<Benchmark>("/api/v1/ops/benchmark"),
    apiOrNull<ChannelGroup[]>("/api/v1/rates/channels"),
  ]);
  const others = (groups ?? []).filter((g) => g.reference !== "ecb-sdmx-exr");
  const ecbCzk = (latest ?? []).find((r) => r.currency === "CZK");
  const b = benchmark?.byPayout;
  const checked = b ? benchmark!.quotes - b.NO_REFERENCE : 0;
  const otherCurrency = b ? b.SEND_CURRENCY + b.USD + b.EUR : 0;

  const total = (quality?.byStatus ?? []).reduce((a, r) => a + Number(r.prices), 0);
  const count = (s: string) => (quality?.byStatus ?? []).filter((r) => r.status === s).reduce((a, r) => a + Number(r.prices), 0);
  const openBreaks = (breaks ?? []).filter((b) => !b.resolvedAt);
  const agreeing = (latest ?? []).filter((r) => r.sdmx !== null && r.sdmx === r.xml && r.sdmx === r.html).length;

  return (
    <>
      <h1>Operations</h1>
      <p className="lede">
        What the people running the platform look at: is every source fresh, what did the checks catch, and do
        the channels that publish the same number agree?
      </p>

      <div className="stats">
        <div className="stat">
          <div className="label">Sources fresh</div>
          <div className="value">
            {(sources ?? []).filter((s) => s.fresh).length} / {(sources ?? []).length}
          </div>
        </div>
        <div className="stat">
          <div className="label">Prices published clean</div>
          <div className="value">{total ? num((100 * count("PUBLISHED")) / total, 1) : "–"}%</div>
        </div>
        <div className="stat">
          <div className="label">Published with warnings</div>
          <div className="value">{num(count("PUBLISHED_WITH_WARNINGS"), 0)}</div>
        </div>
        <div className="stat">
          <div className="label">Quarantined</div>
          <div className="value">{num(count("QUARANTINED"), 0)}</div>
        </div>
        <div className="stat">
          <div className="label">Open channel breaks</div>
          <div className="value">{openBreaks.length}</div>
        </div>
      </div>

      <h2>Sources</h2>
      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Source</th>
              <th>Last run</th>
              <th>Last good</th>
              <th className="n">Age / target</th>
              <th className="n">Runs 7d</th>
              <th className="n">Recipe</th>
              <th>Note</th>
            </tr>
          </thead>
          <tbody>
            {(sources ?? []).map((s) => (
              <tr key={s.sourceId}>
                <td className="mono">{s.sourceId}</td>
                <td>
                  <span className={`chip ${s.lastStatus === "SUCCEEDED" || s.lastStatus === "UNCHANGED" ? "ok" : "ERROR"}`}>
                    {s.lastStatus}
                  </span>
                  <div className="muted small">{when(s.lastRunAt)}</div>
                </td>
                <td className="small">{when(s.lastGoodAt)}</td>
                <td className="n">
                  <span className={`chip ${s.fresh ? "ok" : "ERROR"}`}>
                    {s.ageHours ?? "–"}h / {s.freshnessTargetHours}h
                  </span>
                </td>
                <td className="n">
                  {s.runs7d}
                  {Number(s.failures7d) > 0 && <span className="chip ERROR">{s.failures7d} failed</span>}
                </td>
                <td className="n">v{s.recipeVersion}</td>
                <td className="small muted">{s.lastMessage}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Three channels, one number</h2>
      <p className="muted small" style={{ marginTop: 0 }}>
        The ECB publishes its reference rates as an SDMX API, an XML file and a web page; the web page is read by a
        CSS-selector recipe. They must agree to the last digit. Today {agreeing} of {(latest ?? []).length}{" "}
        currencies agree across all three, and {openBreaks.length} disagreements are open.
      </p>
      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Currency</th>
              <th>Date</th>
              <th className="n">SDMX API</th>
              <th className="n">XML</th>
              <th className="n">Web page (scraped)</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {(latest ?? []).map((r) => {
              const ok = r.sdmx !== null && r.sdmx === r.xml && r.sdmx === r.html;
              return (
                <tr key={r.currency}>
                  <td className="mono">EUR/{r.currency}</td>
                  <td className="small">{r.date}</td>
                  <td className="n">{r.sdmx ?? "–"}</td>
                  <td className="n">{r.xml ?? "–"}</td>
                  <td className="n">{r.html ?? "–"}</td>
                  <td>
                    <span className={`chip ${ok ? "ok" : "WARN"}`}>{ok ? "agree" : "check"}</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {others.map((g) => {
        const all = [g.reference, ...g.channels];
        const eurCzk = g.rows.find((r) => r.base === "EUR" && r.quote === "CZK");
        return (
          <section key={g.reference}>
            <h2>A second publisher: the Czech National Bank</h2>
            <p className="muted small" style={{ marginTop: 0 }}>
              The same check for a different page: the CNB publishes its daily fixing as a text file and as a web
              page, priced in korunas, some per 100 or 1,000 units (the recipe divides by the amount column). On{" "}
              {g.date ?? "–"}, {g.agreeing} of {g.rows.length} currencies agree in both.
              {eurCzk && ecbCzk && ecbCzk.date === g.date && (
                <>
                  {" "}
                  Across publishers the same pair differs, and should: EUR/CZK was {eurCzk.rates[g.reference]} at
                  the CNB&apos;s 14:30 fixing and {ecbCzk.sdmx} in the ECB&apos;s, set at about 14:10 CET. That is
                  why channels are only reconciled with their own publisher.
                </>
              )}
            </p>
            <div className="panel table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Pair</th>
                    {all.map((c) => (
                      <th key={c} className="n">
                        {CHANNEL_LABEL[c] ?? c}
                      </th>
                    ))}
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {g.rows.map((r) => (
                    <tr key={r.base + r.quote}>
                      <td className="mono">
                        {r.base}/{r.quote}
                      </td>
                      {all.map((c) => (
                        <td key={c} className="n">
                          {r.rates[c] ?? "–"}
                        </td>
                      ))}
                      <td>
                        <span className={`chip ${r.agree ? "ok" : "WARN"}`}>{r.agree ? "agree" : "check"}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}

      {benchmark && b && (
        <>
          <h2>Two publishers, one market rate</h2>
          <p className="muted small" style={{ marginTop: 0 }}>
            Every FX margin on this site is measured against the World Bank survey&apos;s own &ldquo;interbank
            rate&rdquo;. Here that rate is checked against the ECB&apos;s, for {period(benchmark.period)}: {num(checked, 0)}{" "}
            of {num(benchmark.quotes, 0)} quotes are between currencies the ECB publishes. {num(b.LOCAL, 0)} match
            the rate into the receiving country&apos;s currency (median gap{" "}
            {benchmark.localMedianAbsDeviationPct ?? "–"}%, {benchmark.localWithinHalfPercentPct ?? "–"}% within
            0.5%); {num(otherCurrency, 0)} match a payout in another currency (US dollars {b.USD}, euros {b.EUR},
            the currency sent {b.SEND_CURRENCY}); {b.INVERTED} are written the wrong way round and{" "}
            {b.UNEXPLAINED} fit nothing within {benchmark.tolerancePct}%.
          </p>
          <div className="panel table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Pair</th>
                  <th className="n">Quotes</th>
                  <th className="n">Median gap</th>
                  <th className="n">Largest gap</th>
                </tr>
              </thead>
              <tbody>
                {benchmark.pairs.slice(0, 10).map((p) => (
                  <tr key={p.pair}>
                    <td className="mono">{p.pair}</td>
                    <td className="n">{p.quotes}</td>
                    <td className="n">{p.medianAbsDeviationPct}%</td>
                    <td className="n">{p.maxAbsDeviationPct}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {benchmark.flagged.length > 0 && (
            <div className="panel table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Flag</th>
                    <th>Corridor</th>
                    <th>Provider</th>
                    <th>Observed</th>
                    <th className="n">Survey</th>
                    <th className="n">ECB</th>
                    <th className="n">Gap</th>
                  </tr>
                </thead>
                <tbody>
                  {benchmark.flagged.slice(0, 20).map((f) => (
                    <tr key={f.recordId}>
                      <td>
                        <span className="chip WARN">{f.payout === "INVERTED" ? "wrong way round" : "unexplained"}</span>
                      </td>
                      <td className="small">
                        {f.corridor} <span className="mono">{f.pair}</span>
                      </td>
                      <td>{f.provider}</td>
                      <td className="small">{f.observedOn}</td>
                      <td className="n">{f.surveyRate}</td>
                      <td className="n">{f.ecbRate}</td>
                      <td className="n">{f.deviationPct}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {(breaks ?? []).length > 0 && (
        <>
          <h2>Channel breaks</h2>
          <div className="panel table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Pair</th>
                  <th>Date</th>
                  <th>Channel</th>
                  <th>Kind</th>
                  <th className="n">Value</th>
                  <th className="n">Expected</th>
                  <th>Resolved</th>
                </tr>
              </thead>
              <tbody>
                {(breaks ?? []).map((b) => (
                  <tr key={`${b.quote}-${b.date}-${b.channel}-${b.kind}`}>
                    <td className="mono">
                      {b.base}/{b.quote}
                    </td>
                    <td>{b.date}</td>
                    <td className="mono">{b.channel}</td>
                    <td>{b.kind}</td>
                    <td className="n">{b.value ?? "–"}</td>
                    <td className="n">{b.expected ?? "–"}</td>
                    <td className="small">{b.resolvedAt ? when(b.resolvedAt) : <span className="chip ERROR">open</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <h2>What the checks found</h2>
      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Check</th>
              <th>Severity</th>
              <th className="n">Prices</th>
            </tr>
          </thead>
          <tbody>
            {(quality?.byRule ?? []).map((r) => (
              <tr key={`${r.rule}-${r.severity}`}>
                <td className="mono">{r.rule}</td>
                <td>
                  <span className={`chip ${r.severity}`}>{r.severity}</span>
                </td>
                <td className="n">{num(r.prices, 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Held back (quarantined)</h2>
      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Period</th>
              <th>Corridor</th>
              <th>Provider</th>
              <th className="n">Total</th>
              <th>Why</th>
              <th>Check it</th>
            </tr>
          </thead>
          <tbody>
            {(quarantine ?? []).map((q) => (
              <tr key={`${q.sourceRecordId}-${q.amountUsd}`}>
                <td className="small">{period(q.period)}</td>
                <td className="small">
                  {q.sendName} → {q.receiveName}
                  <div className="muted">USD {q.amountUsd}</div>
                </td>
                <td>{q.provider}</td>
                <td className="n">{num(q.totalCostPct)}%</td>
                <td className="small">
                  {findings(q)
                    .filter((f) => f.severity === "ERROR")
                    .map((f) => (
                      <div key={f.rule}>
                        <span className={`chip ${f.severity}`}>{f.rule}</span> {f.detail}
                      </div>
                    ))}
                </td>
                <td className="small">
                  <Link href={`/proof/${q.sourceId}/${q.evidenceSha256}/${q.sourceRecordId}?amount=${q.amountUsd}`}>
                    the row, and the sums →
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">
        Each held-back price links to the row of the source file it came from, with the arithmetic the check did, so a
        person can see why it failed and whether the check or the source is wrong.
      </p>

      <h2>Recent runs</h2>
      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Finished</th>
              <th>Source</th>
              <th>Status</th>
              <th className="n">HTTP</th>
              <th className="n">Bytes</th>
              <th className="n">Observations</th>
              <th>Evidence</th>
            </tr>
          </thead>
          <tbody>
            {(runs ?? []).map((r) => (
              <tr key={r.runId}>
                <td className="small">{when(r.finishedAt)}</td>
                <td className="mono">{r.sourceId}</td>
                <td>
                  <span className={`chip ${r.status === "SUCCEEDED" || r.status === "UNCHANGED" ? "ok" : "ERROR"}`}>{r.status}</span>
                  {r.message && <div className="muted small">{r.message}</div>}
                </td>
                <td className="n">{r.httpStatus || "–"}</td>
                <td className="n">{num(r.bytes, 0)}</td>
                <td className="n">{num(r.extracted, 0)}</td>
                <td className="mono">{r.sha256 ? r.sha256.slice(0, 12) + "…" : "–"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <h2>Reference rates held</h2>
      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Channel</th>
              <th className="n">Rates</th>
              <th>From</th>
              <th>To</th>
            </tr>
          </thead>
          <tbody>
            {(quality?.rates ?? []).map((r) => (
              <tr key={r.sourceId}>
                <td className="mono">{r.sourceId}</td>
                <td className="n">{num(r.rates, 0)}</td>
                <td>{r.from}</td>
                <td>{r.to}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
