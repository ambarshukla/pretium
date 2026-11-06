import type { Reading, Recipe } from "@/lib/collector";

// Side-by-side views used by the review pages and the lab: recipes key by key, and
// what each recipe read currency by currency, next to the reference.

const KEYS = ["dateSelector", "dateFormat", "dateRegex", "rowSelector", "currencySelector", "rateSelector", "base"];

export function RecipeCompare({ columns }: { columns: { label: string; recipe: Recipe | null }[] }) {
  const keys = KEYS.filter((k) => columns.some((c) => c.recipe?.[k]));
  const first = columns[0]?.recipe ?? {};
  return (
    <div className="table-wrap">
      <table className="compare">
        <thead>
          <tr>
            <th>Setting</th>
            {columns.map((c) => (
              <th key={c.label}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {keys.map((k) => (
            <tr key={k}>
              <td className="mono">{k}</td>
              {columns.map((c, i) => {
                const v = c.recipe?.[k];
                const changed = i > 0 && c.recipe && (v ?? "") !== (first[k] ?? "");
                return (
                  <td key={c.label} className={`mono${changed ? " changed" : ""}`}>
                    {c.recipe ? (v ?? <span className="muted">–</span>) : <span className="muted">none</span>}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function cell(r: Reading | undefined) {
  if (!r || r.read === null) return { text: "not read", cls: "missing" };
  if (r.reference === null) return { text: String(r.read), cls: "bad" };
  return { text: String(r.read), cls: Number(r.read) === Number(r.reference) ? "good" : "bad" };
}

export function ReadingsCompare({ columns }: { columns: { label: string; readings: Reading[] }[] }) {
  const reference = new Map<string, number | null>();
  for (const c of columns) {
    for (const r of c.readings) {
      if (r.reference !== null || !reference.has(r.currency)) reference.set(r.currency, r.reference);
    }
  }
  const currencies = [...reference.keys()].sort();
  if (currencies.length === 0) {
    return <p className="muted">Nothing was read, and without a date there is no reference to compare with.</p>;
  }
  const byColumn = columns.map((c) => new Map(c.readings.map((r) => [r.currency, r])));
  return (
    <div className="table-wrap">
      <table className="compare">
        <thead>
          <tr>
            <th>Currency</th>
            <th className="n">ECB reference</th>
            {columns.map((c) => (
              <th key={c.label} className="n">
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {currencies.map((cur) => (
            <tr key={cur}>
              <td className="mono">{cur}</td>
              <td className="n mono">{reference.get(cur) ?? <span className="muted">none</span>}</td>
              {byColumn.map((m, i) => {
                const { text, cls } = cell(m.get(cur));
                return (
                  <td key={columns[i].label} className={`n mono reading ${cls}`}>
                    {text}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** "31 of 31 exact" style tally for one attempt. */
export function Tally({ a }: { a: { accepted: boolean; matched: number; expected: number; read: number; error: string | null } }) {
  return (
    <span>
      <span className={`chip ${a.accepted ? "ok" : "ERROR"}`}>{a.accepted ? "accepted" : "rejected"}</span>
      <span className="small">
        {a.matched} of {a.expected} reference rates read exactly
        {a.read > a.matched ? `, ${a.read - a.matched} wrong or unknown` : ""}
        {a.error && !a.accepted ? ` — ${a.error}` : ""}
      </span>
    </span>
  );
}
