import Link from "next/link";
import { notFound } from "next/navigation";
import { ACTION_TEXT, collector, OUTCOME_TEXT, snapshotDate, type Attempt, type CaseDetail } from "@/lib/collector";
import { ReadingsCompare, RecipeCompare, Tally } from "../../compare";
import { ReviewPanel } from "./review-panel";

export const dynamic = "force-dynamic";

type Params = Promise<{ source: string; snapshot: string }>;

function label(a: Attempt, i: number): string {
  if (a.by === "recipe") return `v${a.recipeVersion}, in use`;
  if (a.by === "library") return `v${a.recipeVersion}, earlier recipe`;
  if (a.by === "model") return `proposal ${i}`;
  return "written by a person";
}

export default async function CasePage({ params }: { params: Params }) {
  const { source, snapshot } = await params;
  const detail = await collector<CaseDetail>(
    `/api/v1/repairs/${encodeURIComponent(source)}/${encodeURIComponent(snapshot)}`,
  );
  if (!detail) notFound();
  const c = detail.repair;

  const columns: { label: string; a: Attempt }[] = [{ label: label(c.before, 0), a: c.before }];
  c.proposals.forEach((p, i) => columns.push({ label: label(p, i + 1), a: p }));
  if (c.after && c.after.by === "library") columns.push({ label: label(c.after, 0), a: c.after });

  return (
    <>
      <p className="small">
        <Link href="/repairs">← all repairs</Link>
      </p>
      <h1>The rates page as archived on {snapshotDate(c.snapshot)}</h1>
      <p className="lede">
        <span className={`chip ${c.outcome === "HUMAN_NEEDED" ? "ERROR" : c.outcome === "HEALED" ? "ok" : "INFO"}`}>
          {OUTCOME_TEXT[c.outcome]}
        </span>{" "}
        Rates for {c.ratesDate === "null" ? "an unknown day" : c.ratesDate}. The page:{" "}
        <a href={c.archiveUrl}>Internet Archive copy</a>.
      </p>

      <h2>What each recipe read</h2>
      <p className="muted small">
        Every rate a recipe read, next to what the ECB&apos;s data feed says for the same day. Green is an exact match,
        red a wrong number: one red cell and the recipe is rejected. Wrong numbers here never left this page.
      </p>
      <ul className="small">
        {columns.map(({ label: l, a }) => (
          <li key={l}>
            <b>{l}</b>: <Tally a={a} />
          </li>
        ))}
      </ul>
      <div className="panel">
        <ReadingsCompare columns={columns.map(({ label: l, a }) => ({ label: l, readings: a.readings }))} />
      </div>

      <h2>The recipes, side by side</h2>
      <p className="muted small">CSS selectors the reader uses on the page. Changed settings are highlighted.</p>
      <div className="panel">
        <RecipeCompare columns={columns.map(({ label: l, a }) => ({ label: l, recipe: a.recipe }))} />
      </div>

      {c.proposals.length > 0 && (
        <>
          <h2>What the model said</h2>
          {c.proposals.map((p, i) => (
            <details key={i} className="panel" style={{ marginBottom: 8 }}>
              <summary>
                Proposal {i + 1}: {detail.model}, {((p.millis ?? 0) / 1000).toFixed(1)} s, {p.promptTokens} tokens in,{" "}
                {p.outputTokens} out
              </summary>
              <pre className="mono reply">{p.modelReply}</pre>
              <p className="small muted">
                The date settings are not the model&apos;s: a program finds the date on the page and fills them in.
              </p>
            </details>
          ))}
        </>
      )}

      <h2>Review</h2>
      {detail.decisions.length > 0 ? (
        <ul>
          {detail.decisions.map((d) => (
            <li key={d.decidedAt}>
              <span className="chip ok">{ACTION_TEXT[d.action]}</span> {d.decidedAt.slice(0, 16).replace("T", " ")} UTC
              {d.matched !== null ? ` · ${d.matched} of ${d.expected} exact` : ""}
              {d.note ? ` · “${d.note}”` : ""}
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted">No decision yet.</p>
      )}
      <ReviewPanel
        source={source}
        snapshot={c.snapshot}
        outcome={c.outcome}
        hasRepair={c.after !== null}
        pageAvailable={detail.pageAvailable}
        startRecipe={(c.proposals.at(-1) ?? c.before).recipe}
      />
    </>
  );
}
