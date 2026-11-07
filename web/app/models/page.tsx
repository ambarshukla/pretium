import Link from "next/link";
import { collector, snapshotDate } from "@/lib/collector";

export const dynamic = "force-dynamic";

type Repair = {
  accepted: boolean;
  matched: number;
  expected: number;
  wrong: number;
  error: string | null;
  recipe: Record<string, string>;
  millis: number;
  promptTokens: number;
  outputTokens: number;
  reply: string;
};

type Extract = {
  dateVisible: boolean;
  dateRight: boolean;
  dateRead: string;
  exact: number;
  wrong: number;
  invented: number;
  missing: number;
  mistakes: string[];
  millis: number;
  promptTokens: number;
  outputTokens: number;
  reply: string;
};

type PageResult = {
  snapshot: string;
  ratesDate: string;
  staleVersion: number;
  digestChars: number;
  visible: number;
  repair: Repair;
  extract: Extract;
  /** The same direct reading, shown the whole table instead of the cut-down part (some models only). */
  extractTable?: Extract | null;
  tableVisible?: number | null;
  /** Run again after a fix to the program's date finder (see the note on the page). */
  rerun?: boolean;
};

type Bench = {
  source: string;
  generatedAt: string;
  method: string;
  snapshots: string[];
  models: { model: string; pages: PageResult[] }[];
};

function sum(ps: PageResult[], f: (p: PageResult) => number): number {
  return ps.reduce((a, p) => a + f(p), 0);
}

function secs(ms: number, count: number): string {
  return count === 0 ? "–" : `${(ms / 1000 / count).toFixed(0)} s`;
}

export default async function ModelsPage() {
  const bench = await collector<Bench>("/api/v1/lab/bench");

  if (!bench) {
    return (
      <>
        <h1>Models compared</h1>
        <p className="panel">No model comparison has been published yet, or the collector is not reachable.</p>
      </>
    );
  }

  const pages = bench.snapshots.length;
  // How many currencies the ECB published that day: the verifier's count, from whichever run found
  // the page's date.
  const currencies = new Map<string, number>();
  for (const m of bench.models)
    for (const p of m.pages) currencies.set(p.snapshot, Math.max(currencies.get(p.snapshot) ?? 0, p.repair.expected));

  return (
    <>
      <h1>Models compared: fix the parser, or read the numbers?</h1>
      <p className="lede">
        There are two ways to use a language model when a web page changes and the parser breaks. It can{" "}
        <b>repair the parser</b>: propose new CSS selectors, which ordinary code then uses to read the numbers. Or it
        can <b>read the numbers itself</b> and type them out. This page runs both, with {bench.models.length} models
        of different sizes, on the same {pages} archived copies of the ECB&apos;s rates page, and checks every number against the ECB&apos;s own
        data feed for that day.
      </p>

      <div className="panel">
        <table>
          <thead>
            <tr>
              <th rowSpan={2}>Model</th>
              <th colSpan={4} className="group">
                Repair the parser (selectors, verified)
              </th>
              <th colSpan={5} className="group">
                Read the numbers itself (nothing to check them against but the model)
              </th>
            </tr>
            <tr>
              <th className="n">Pages repaired, first try</th>
              <th className="n">Wrong numbers published</th>
              <th className="n">Wrong numbers caught</th>
              <th className="n">Time per page</th>
              <th className="n">Rates typed exactly</th>
              <th className="n">Wrong or made up</th>
              <th className="n">Dates right</th>
              <th className="n">Rates it could see</th>
              <th className="n">Time per page</th>
            </tr>
          </thead>
          <tbody>
            {bench.models.map((m) => {
              const ps = m.pages;
              const repaired = ps.filter((p) => p.repair.accepted).length;
              const caught = sum(ps, (p) => p.repair.wrong);
              const exact = sum(ps, (p) => p.extract.exact);
              const bad = sum(ps, (p) => p.extract.wrong + p.extract.invented);
              const typed = exact + bad;
              const datesRight = ps.filter((p) => p.extract.dateRight).length;
              const visible = sum(ps, (p) => p.visible);
              const expected = sum(ps, (p) => currencies.get(p.snapshot) ?? 0);
              return (
                <tr key={m.model}>
                  <td className="mono">
                    {m.model}
                    {ps.length < pages && <div className="small muted">{ps.length} of {pages} pages so far</div>}
                  </td>
                  <td className="n">
                    <b>
                      {repaired} of {ps.length}
                    </b>
                  </td>
                  <td className="n">
                    <span className="chip ok">0</span>
                  </td>
                  <td className="n">{caught}</td>
                  <td className="n">{secs(sum(ps, (p) => p.repair.millis), ps.length)}</td>
                  <td className="n">
                    {exact} of {typed}
                  </td>
                  <td className="n">
                    <span className={bad > 0 ? "chip ERROR" : "chip ok"}>{bad}</span>
                  </td>
                  <td className="n">
                    {datesRight} of {ps.length}
                  </td>
                  <td className="n">
                    {visible} of {expected}
                  </td>
                  <td className="n">{secs(sum(ps, (p) => p.extract.millis), ps.length)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {bench.models.some((m) => m.pages.some((p) => p.extractTable)) && (
        <>
          <h2>Fair to the direct reading: shown the whole table</h2>
          <p className="muted small" style={{ marginTop: 0 }}>
            The cut-down part of the page often holds only some of the table, which handicaps a model reading the
            numbers itself. So the same models were also shown the whole table (its date heading and every row), to
            separate &ldquo;could not see it&rdquo; from &ldquo;copied it wrong&rdquo;.
          </p>
          <div className="panel">
            <table>
              <thead>
                <tr>
                  <th>Model</th>
                  <th className="n">Rates it could see</th>
                  <th className="n">Rates typed exactly</th>
                  <th className="n">Wrong or made up</th>
                  <th className="n">Left out</th>
                  <th className="n">Dates right</th>
                  <th className="n">Time per page</th>
                </tr>
              </thead>
              <tbody>
                {bench.models.map((m) => {
                  const ps = m.pages.filter((p) => p.extractTable);
                  if (ps.length === 0) return null;
                  const t = (p: PageResult) => p.extractTable!;
                  const bad = sum(ps, (p) => t(p).wrong + t(p).invented);
                  return (
                    <tr key={m.model}>
                      <td className="mono">{m.model}</td>
                      <td className="n">
                        {sum(ps, (p) => p.tableVisible ?? 0)} of {sum(ps, (p) => currencies.get(p.snapshot) ?? 0)}
                      </td>
                      <td className="n">{sum(ps, (p) => t(p).exact)}</td>
                      <td className="n">
                        <span className={bad > 0 ? "chip ERROR" : "chip ok"}>{bad}</span>
                      </td>
                      <td className="n">{sum(ps, (p) => t(p).missing)}</td>
                      <td className="n">
                        {ps.filter((p) => t(p).dateRight).length} of {ps.length}
                      </td>
                      <td className="n">{secs(sum(ps, (p) => t(p).millis), ps.length)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      <h2>How to read this</h2>
      <div className="panel small">
        <ul>
          <li>
            <b>Wrong numbers published</b> is zero for repair <i>by construction</i>: a proposed parser is only used
            if every number it reads equals the ECB&apos;s data feed exactly. A bad proposal costs a retry or a person,
            never a wrong price. &ldquo;Wrong numbers caught&rdquo; counts what bad proposals read and the check
            stopped.
          </li>
          <li>
            <b>Wrong or made up</b> is what reading the numbers directly would have published: values for a real
            currency that differ from the ECB&apos;s, plus currencies or values that are not there. In production
            there is usually no second channel to compare against, so these would look exactly like right ones.
          </li>
          <li>
            <b>Dates right</b>: on the older pages the date is not in the part of the page the model is shown at
            all. Asked for it, the models did not say so: they gave a date anyway (one gave 14 April 2023 for every
            one of them). A repair does not have this problem, because the date is found by a program on the full
            page and checked like every rate.
          </li>
          <li>
            <b>Rates it could see</b>: both approaches are shown the same cut-down part of the page (6,000
            characters, the &ldquo;What a model is shown&rdquo; tab in the <Link href="/lab">lab</Link>), which
            often holds only some of the table. Selectors found in that part still read the whole table, because code
            applies them to the full page. A model reading directly can only type what it saw.
          </li>
          {bench.models.some((m) => m.pages.some((p) => p.rerun)) && (
            <li>
              Pages marked <i>re-run</i> were run again after this comparison found a bug in the program (not the
              model) that finds the page&apos;s date for a repair: it took the first date on the page, which since
              2022 is often a news item&apos;s. The repairs then read every rate correctly under the wrong day, and the
              verifier rejected them as wrong numbers. The program now takes the date just before the rates table.
            </li>
          )}
          <li>
            Each model is asked once per page, at temperature 0, on a laptop CPU through Ollama. The repair
            starts from a known recipe that fails on that page. {bench.method}. Generated{" "}
            {bench.generatedAt.slice(0, 10)}; the program is{" "}
            <a href="https://github.com/ambarshukla/pretium/blob/main/collector/src/main/java/io/pretium/collector/repair/ModelBench.java">
              ModelBench.java
            </a>
            .
          </li>
        </ul>
      </div>

      <h2>Page by page</h2>
      {bench.snapshots.map((s) => {
        const rows = bench.models
          .map((m) => ({ model: m.model, p: m.pages.find((p) => p.snapshot === s) }))
          .filter((r): r is { model: string; p: PageResult } => r.p !== undefined);
        if (rows.length === 0) return null;
        const first = rows[0].p;
        return (
          <details key={s} className="panel run">
            <summary>
              <b>{snapshotDate(s)}</b>
              {first.rerun && <span className="muted"> (re-run)</span>} · rates for {first.ratesDate} · repair starts from recipe v{first.staleVersion}{" "}
              · the model sees {first.visible} of {currencies.get(s)} currencies
              {first.extract.dateVisible ? "" : ", and not the date"} ·{" "}
              <Link href={`/lab?page=archive:${s}&parser=recipe:${first.staleVersion}`}>open in the lab</Link>
            </summary>
            <table>
              <thead>
                <tr>
                  <th>Model</th>
                  <th>Repair</th>
                  <th>Read directly</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(({ model, p }) => (
                  <tr key={model}>
                    <td className="mono">{model}</td>
                    <td className="small">
                      {p.repair.accepted ? (
                        <span className="chip ok">
                          repaired: {p.repair.matched} of {p.repair.expected} exact
                        </span>
                      ) : (
                        <span className="chip WARN">not repaired</span>
                      )}{" "}
                      {!p.repair.accepted && p.repair.error}
                      <div className="mono muted">
                        rows: {p.repair.recipe.rowSelector} · rate: {p.repair.recipe.rateSelector}
                      </div>
                    </td>
                    <td className="small">
                      {p.extract.exact} exact, {p.extract.wrong} wrong, {p.extract.invented} made up,{" "}
                      {p.extract.missing} left out · date {p.extract.dateRead || "none"}{" "}
                      {p.extract.dateRight ? "✓" : <span className="chip ERROR">wrong date</span>}
                      {p.extract.mistakes.length > 0 && (
                        <div className="mono">{p.extract.mistakes.slice(0, 8).join(" · ")}</div>
                      )}
                      {p.extractTable && (
                        <div>
                          shown the whole table: {p.extractTable.exact} exact, {p.extractTable.wrong} wrong,{" "}
                          {p.extractTable.invented} made up, {p.extractTable.missing} left out · date{" "}
                          {p.extractTable.dateRead || "none"} {p.extractTable.dateRight ? "✓" : "✗"}
                          {p.extractTable.mistakes.length > 0 && (
                            <div className="mono">{p.extractTable.mistakes.slice(0, 8).join(" · ")}</div>
                          )}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        );
      })}
    </>
  );
}
