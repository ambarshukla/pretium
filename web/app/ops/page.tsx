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
  sourceRecordId: string;
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

type LatestRate = { currency: string; date: string; sdmx: number | null; xml: number | null; html: number | null };

const when = (s: string | null) => (s ? new Date(s).toISOString().replace("T", " ").slice(0, 16) + " UTC" : "never");

export default async function OpsPage() {
  const [sources, runs, quality, quarantine, breaks, latest] = await Promise.all([
    apiOrNull<SourceHealth[]>("/api/v1/ops/sources"),
    apiOrNull<Run[]>("/api/v1/ops/runs?limit=20"),
    apiOrNull<Quality>("/api/v1/ops/quality"),
    apiOrNull<Quarantined[]>("/api/v1/ops/quarantine?limit=25"),
    apiOrNull<Break[]>("/api/v1/ops/breaks?limit=50"),
    apiOrNull<LatestRate[]>("/api/v1/rates/latest"),
  ]);

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
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
