import type { Reading, Recipe, Trace } from "@/lib/collector";
import { EXTRACTOR_URL, lineDiff, recipeCode } from "@/lib/recipe-code";

// A recipe shown as the parser it amounts to, two recipes as a code diff, and the
// step-by-step trace of one extraction.

export function RecipeCode({ recipe }: { recipe: Recipe }) {
  return <pre className="mono code">{recipeCode(recipe)}</pre>;
}

export function CodeDiff({
  before,
  after,
  beforeLabel,
  afterLabel,
}: {
  before: Recipe;
  after: Recipe;
  beforeLabel: string;
  afterLabel: string;
}) {
  const lines = lineDiff(recipeCode(before), recipeCode(after));
  const changed = lines.filter((l) => l.op !== " ").length;
  return (
    <div className="diff">
      <div className="diff-head mono small">
        <span className="del">--- {beforeLabel}</span>
        <span className="add">+++ {afterLabel}</span>
        <span className="muted">{changed === 0 ? "no change" : `${changed} lines changed`}</span>
      </div>
      <pre className="mono code">
        {lines.map((l, i) => (
          <div key={i} className={l.op === "+" ? "add" : l.op === "-" ? "del" : undefined}>
            <span className="ln">{l.oldNo ?? ""}</span>
            <span className="ln">{l.newNo ?? ""}</span>
            <span className="op">{l.op}</span>
            {l.text}
          </div>
        ))}
      </pre>
    </div>
  );
}

export function ExtractorNote() {
  return (
    <p className="small muted">
      A recipe is data: a few CSS selectors and a date pattern. One generic class applies it (
      <a href={EXTRACTOR_URL}>HtmlRateTableExtractor.java</a>). The code shown is what that class does with this
      recipe, step for step, so a repair reads as a code change.
    </p>
  );
}

function status(row: Trace["rows"][number], ref: Reading | undefined) {
  if (row.skipped) return { text: `skipped: ${row.skipped}`, cls: "missing" };
  if (!ref || ref.reference === null) return { text: "no ECB rate that day", cls: "bad" };
  return Number(row.rate) === Number(ref.reference)
    ? { text: "exact", cls: "good" }
    : { text: "wrong number", cls: "bad" };
}

/** Every step one recipe took on one page: the date, then each row its selector matched. */
export function TraceView({ trace, readings }: { trace: Trace; readings: Reading[] }) {
  const byCurrency = new Map(readings.map((r) => [r.currency, r]));
  const d = trace.dateStep;
  const read = new Set(trace.rows.filter((r) => !r.skipped).map((r) => r.currencyText));
  const missed = readings.filter((r) => r.reference !== null && !read.has(r.currency));
  return (
    <div className="trace">
      <h4>1. The date</h4>
      {d ? (
        <table className="kv small">
          <tbody>
            <tr>
              <th>dateSelector</th>
              <td className="mono">{d.selector}</td>
            </tr>
            <tr>
              <th>matched element&apos;s text</th>
              <td className="mono">{d.elementText ?? <span className="bad-text">nothing matched</span>}</td>
            </tr>
            {d.regex && (
              <tr>
                <th>dateRegex, group 1</th>
                <td className="mono">
                  {d.regex} → {d.dateText ?? <span className="bad-text">no match</span>}
                </td>
              </tr>
            )}
            <tr>
              <th>parsed with {d.format ?? "no format"}</th>
              <td className="mono">{trace.date ?? <span className="bad-text">not a date</span>}</td>
            </tr>
          </tbody>
        </table>
      ) : (
        <p className="small bad-text">The recipe has no date selector.</p>
      )}

      <h4>
        2. The rows: <span className="mono">{trace.rowSelector}</span> matched {trace.rows.length}
      </h4>
      {trace.rows.length > 0 && (
        <div className="table-wrap scroll-y">
          <table className="compare small">
            <thead>
              <tr>
                <th className="n">#</th>
                <th>currency cell</th>
                <th>rate cell</th>
                <th className="n">parsed</th>
                <th className="n">ECB reference</th>
                <th>result</th>
              </tr>
            </thead>
            <tbody>
              {trace.rows.map((row) => {
                const ref = row.currencyText ? byCurrency.get(row.currencyText) : undefined;
                const s = status(row, ref);
                return (
                  <tr key={row.index}>
                    <td className="n">{row.index}</td>
                    <td className="mono">{row.currencyText ?? <span className="muted">none</span>}</td>
                    <td className="mono">{row.rateText ?? <span className="muted">none</span>}</td>
                    <td className="n mono">{row.rate ?? ""}</td>
                    <td className="n mono">{ref?.reference ?? ""}</td>
                    <td className={`reading ${s.cls}`}>{s.text}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {missed.length > 0 && trace.date && (
        <p className="small">
          <b>Not on the page, or not reached:</b>{" "}
          <span className="mono">{missed.map((m) => m.currency).join(" ")}</span> ({missed.length} of the ECB&apos;s{" "}
          {readings.filter((r) => r.reference !== null).length} rates that day)
        </p>
      )}
      {trace.error && (
        <p className="small">
          <span className="chip ERROR">stopped</span> <span className="mono">{trace.error}</span>
        </p>
      )}
    </div>
  );
}
