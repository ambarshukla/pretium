import type { Batch, BatchRow } from "@/lib/collector";
import { snapshotDate } from "@/lib/collector";

// One recipe over every page: a strip with one cell per page, oldest on the left, so
// several recipes stacked show when each layout of the page was in use and where a
// recipe stops working.

export type NamedBatch = { label: string; batch: Batch };

function when(r: BatchRow): string {
  return r.pageId.startsWith("archive:") ? snapshotDate(r.pageId.slice(8)) : `${r.when.slice(0, 10)} (collected)`;
}

function kind(r: BatchRow): string {
  if (r.accepted) return "ok";
  if (r.wrong > 0) return "wrong";
  if (r.matched > 0) return "part";
  return "none";
}

function title(r: BatchRow): string {
  const what = r.accepted
    ? `read exactly: ${r.matched} of ${r.expected}`
    : r.wrong > 0
      ? `${r.wrong} wrong numbers (blocked)`
      : r.error ?? "nothing read";
  return `${when(r)}${r.ratesDate ? `, rates for ${r.ratesDate}` : ""}: ${what}`;
}

export function BatchStrips({
  batches,
  current,
  onPick,
}: {
  batches: NamedBatch[];
  current: string;
  onPick: (pageId: string) => void;
}) {
  if (batches.length === 0) return null;
  const last = batches[batches.length - 1];
  const failing = [...last.batch.rows].filter((r) => !r.accepted).sort((a, b) => a.when.localeCompare(b.when));
  return (
    <>
      <div className="panel">
        <p className="legend small">
          One cell per page, oldest on the left. <span className="key cell-ok">read exactly</span>{" "}
          <span className="key cell-part">read some, none wrong</span>{" "}
          <span className="key cell-wrong">read wrong numbers (blocked by the verifier)</span>{" "}
          <span className="key cell-none">read nothing</span> · click a cell to open that page above
        </p>
        {batches.map((b) => {
          const rows = [...b.batch.rows].sort((x, y) => x.when.localeCompare(y.when));
          const first = rows[0];
          const lastRow = rows[rows.length - 1];
          return (
            <div key={b.label} className="strip-row">
              <div className="strip-label small">
                <b>{b.label}</b>: reads {b.batch.accepted} of {b.batch.pages} pages exactly
                {b.batch.wrongNumbers > 0 && `, ${b.batch.wrongNumbers} wrong numbers blocked`} ·{" "}
                {(b.batch.millis / 1000).toFixed(1)} s
              </div>
              <div className="strip">
                {rows.map((r) => (
                  <button
                    key={r.pageId}
                    type="button"
                    className={`cell cell-${kind(r)}${r.pageId === current ? " here" : ""}`}
                    title={title(r)}
                    aria-label={title(r)}
                    onClick={() => onPick(r.pageId)}
                  />
                ))}
              </div>
              {first && lastRow && (
                <div className="strip-ends small muted">
                  <span>{when(first)}</span>
                  <span>{when(lastRow)}</span>
                </div>
              )}
            </div>
          );
        })}
      </div>
      {failing.length > 0 && (
        <details className="panel">
          <summary>
            Pages {last.label} does not read exactly ({failing.length})
          </summary>
          <table>
            <thead>
              <tr>
                <th>Page</th>
                <th>Rates for</th>
                <th className="n">Matched</th>
                <th className="n">Wrong</th>
                <th>Why</th>
              </tr>
            </thead>
            <tbody>
              {failing.map((r) => (
                <tr key={r.pageId}>
                  <td>
                    <button type="button" className="link" onClick={() => onPick(r.pageId)}>
                      {when(r)}
                    </button>
                  </td>
                  <td>{r.ratesDate ?? <span className="muted">not found</span>}</td>
                  <td className="n">
                    {r.matched} / {r.expected}
                  </td>
                  <td className="n">{r.wrong}</td>
                  <td className="small">{r.error}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </>
  );
}
