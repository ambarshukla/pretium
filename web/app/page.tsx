import { api, apiOrNull, findings, num, period, type Comparison, type Corridor, type HistoryPoint } from "@/lib/api";
import Link from "next/link";
import { HistoryChart } from "./history-chart";
import { Picker } from "./picker";

export const dynamic = "force-dynamic";

type Search = Promise<{ corridor?: string; amount?: string; period?: string }>;

export default async function ComparePage({ searchParams }: { searchParams: Search }) {
  const params = await searchParams;
  const amount = params.amount === "500" ? 500 : 200;
  const [send, receive] = (params.corridor ?? "GBR-IND").split("-");

  // Both survey amounts, so the picker can show each in the sender's own currency.
  const [small, large] = await Promise.all([
    apiOrNull<Corridor[]>("/api/v1/corridors?amountUsd=200"),
    apiOrNull<Corridor[]>("/api/v1/corridors?amountUsd=500"),
  ]);
  const qs = new URLSearchParams({ send, receive, amountUsd: String(amount) });
  if (params.period) qs.set("period", params.period);
  const comparison = await apiOrNull<Comparison>(`/api/v1/comparisons?${qs}`);
  const history = comparison
    ? await api<HistoryPoint[]>(`/api/v1/corridors/${send}/${receive}/history?amountUsd=${amount}`)
    : [];

  const quotes = comparison?.quotes ?? [];
  const cheapest = quotes[0];
  const dearest = quotes[quotes.length - 1];
  const sendAmount = cheapest?.sendAmount;
  const currency = cheapest?.sendCurrency;

  return (
    <>
      <h1>What does it really cost to send money?</h1>
      <p className="lede">
        Every provider surveyed in a corridor, cheapest first. The total cost is the fee plus the hidden markup in
        the exchange rate. Every number links to the file it came from, and every one was checked before it was
        shown. New here? <Link href="/tour">Take the five-minute tour</Link>.
      </p>

      <Picker small={small ?? []} large={large ?? []} send={send} receive={receive} amount={amount} />

      {!comparison ? (
        <p className="panel" style={{ marginTop: 16 }}>
          No prices for this corridor yet.
        </p>
      ) : (
        <>
          <div className="stats">
            <div className="stat">
              <div className="label">Corridor</div>
              <div className="value" style={{ fontSize: 17 }}>
                {comparison.sendName} → {comparison.receiveName}
              </div>
            </div>
            <div className="stat">
              <div className="label">Sending</div>
              <div className="value">
                {num(sendAmount, 0)} {currency}
              </div>
            </div>
            <div className="stat">
              <div className="label">Cheapest total cost</div>
              <div className="value">{num(cheapest?.totalCostPct)}%</div>
            </div>
            <div className="stat">
              <div className="label">Most expensive</div>
              <div className="value">{num(dearest?.totalCostPct)}%</div>
            </div>
            <div className="stat">
              <div className="label">Surveyed</div>
              <div className="value" style={{ fontSize: 17 }}>
                {period(comparison.period)} · {quotes.length} services
              </div>
            </div>
          </div>

          <form className="picker" method="get" style={{ marginBottom: 8 }}>
            <input type="hidden" name="corridor" value={`${send}-${receive}`} />
            <input type="hidden" name="amount" value={amount} />
            <label>
              Survey period
              <select name="period" defaultValue={comparison.period}>
                {comparison.periods.map((p) => (
                  <option key={p} value={p}>
                    {period(p)}
                  </option>
                ))}
              </select>
            </label>
            <button type="submit">Show</button>
          </form>

          <div className="panel table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Provider</th>
                  <th>How</th>
                  <th className="n">Fee</th>
                  <th className="n">FX margin</th>
                  <th className="n">Total cost</th>
                  <th className="n">Recipient gets</th>
                  <th>Checks</th>
                  <th>Evidence</th>
                </tr>
              </thead>
              <tbody>
                {quotes.map((q, i) => {
                  const fs = findings(q);
                  return (
                    <tr key={`${q.sourceRecordId}-${i}`} className={i === 0 ? "cheapest" : undefined}>
                      <td>
                        <strong>{q.provider}</strong>
                        <div className="muted small">{q.providerType}</div>
                      </td>
                      <td className="small">
                        {q.paymentInstrument} · {q.accessPoint}
                        <div className="muted">{q.speed}</div>
                      </td>
                      <td className="n">
                        {num(q.fee)} {q.sendCurrency}
                      </td>
                      <td className="n">{q.transparent ? `${num(q.fxMarginPct)}%` : "not disclosed"}</td>
                      <td className="n">
                        <strong>{num(q.totalCostPct)}%</strong>
                      </td>
                      <td className="n">
                        {q.receiveAmount !== null ? num(q.receiveAmount) : "–"}
                        {q.receiveAtInterbank !== null && (
                          <div className="muted small">of {num(q.receiveAtInterbank)} at interbank</div>
                        )}
                      </td>
                      <td>
                        {fs.length === 0 ? (
                          <span className="chip ok">all checks passed</span>
                        ) : (
                          fs.map((f) => (
                            <span key={f.rule} className={`chip ${f.severity}`} title={f.detail}>
                              {f.rule}
                            </span>
                          ))
                        )}
                      </td>
                      <td>
                        <details>
                          <summary>proof</summary>
                          <div className="mono">
                            source: {q.sourceId}
                            <br />
                            record: {q.sourceRecordId}
                            <br />
                            observed: {q.observedOn ?? "unknown"}
                            <br />
                            file sha256: {q.evidenceSha256.slice(0, 16)}…
                            <br />
                            run: {q.runId.slice(0, 8)}
                          </div>
                          <Link href={`/proof/${q.sourceId}/${q.evidenceSha256}/${q.sourceRecordId}?amount=${amount}`}>
                            the row in the file, and the arithmetic →
                          </Link>
                        </details>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="muted small">
            Hover a check for its detail. Prices that failed a check are held back and listed on the Operations
            page instead. &ldquo;Recipient gets&rdquo; is only shown where the provider disclosed its rate.
          </p>

          <h2>How this corridor has moved</h2>
          <div className="panel">
            <HistoryChart points={history} />
          </div>
        </>
      )}
    </>
  );
}
