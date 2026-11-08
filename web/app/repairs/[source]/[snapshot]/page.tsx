import Link from "next/link";
import { notFound } from "next/navigation";
import {
  ACTION_TEXT,
  archivePageId,
  collector,
  collectorPost,
  OUTCOME_TEXT,
  PUBLISHER,
  snapshotDate,
  type Attempt,
  type CaseDetail,
  type PageView,
  viewPart,
} from "@/lib/collector";
import { CodeDiff, ExtractorNote } from "../../code";
import { ReadingsCompare, Tally } from "../../compare";
import { PageViewer } from "../../page-viewer";
import { ReviewPanel } from "./review-panel";

export const dynamic = "force-dynamic";

type Params = Promise<{ source: string; snapshot: string }>;

function label(a: Attempt, i: number): string {
  if (a.by === "recipe") return `recipe v${a.recipeVersion}, in use`;
  if (a.by === "library") return `recipe v${a.recipeVersion}, earlier`;
  if (a.by === "date-fix") return `date settings only (no model)`;
  if (a.by === "model") return `model proposal ${i}`;
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
  const outlines = columns.map(({ label: l, a }) => ({ label: l, recipe: a.recipe }));
  const pageId = archivePageId(source, c.snapshot);
  const publisher = PUBLISHER[source]?.short ?? source;
  const shownFirst = outlines.length - 1;
  const initial = detail.pageAvailable
    ? await collectorPost<PageView>("/api/v1/lab/view", { pageId, recipe: outlines[shownFirst].recipe })
    : null;
  const afterLabel = !c.after
    ? null
    : c.after.by === "model"
      ? label(c.after, c.proposals.findIndex((p) => p.accepted) + 1)
      : label(c.after, 0);
  const rejected = c.proposals.map((p, i) => ({ p, i })).filter(({ p }) => !p.accepted);

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
        Rates for {c.ratesDate === "null" ? "an unknown day" : c.ratesDate}. The recipe in use failed with:{" "}
        <span className="mono">{c.before.error}</span>
      </p>
      <p className="small">
        <Link href={`/lab?page=${encodeURIComponent(pageId)}&parser=recipe:${c.before.recipeVersion}`}>
          Open this page in the parser lab
        </Link>{" "}
        to run any recipe or model on it, or edit a selector and see what changes.
      </p>

      <h2>The page</h2>
      {detail.pageAvailable ? (
        <PageViewer
          pageId={pageId}
          outlines={outlines}
          initial={initial?.ok ? viewPart(initial.data, "region") : null}
        />
      ) : (
        <p className="panel muted">
          This server does not hold the archived copy. <a href={c.archiveUrl}>It is at the Internet Archive</a>.
        </p>
      )}

      <h2>What the repair changed, as code</h2>
      <ExtractorNote />
      {c.after && afterLabel ? (
        <div className="panel">
          <p className="small">
            <b>{afterLabel}</b>: <Tally a={c.after} />
          </p>
          <CodeDiff before={c.before.recipe} after={c.after.recipe} beforeLabel={columns[0].label} afterLabel={afterLabel} />
        </div>
      ) : (
        <p className="small muted">No recipe read this page exactly, so nothing was changed.</p>
      )}
      {rejected.length > 0 && (
        <>
          <h3>Rejected proposals, as changes</h3>
          {rejected.map(({ p, i }) => (
            <div key={i} className="panel" style={{ marginBottom: 8 }}>
              <p className="small">
                <b>{label(p, i + 1)}</b>: <Tally a={p} />
              </p>
              <CodeDiff before={c.before.recipe} after={p.recipe} beforeLabel={columns[0].label} afterLabel={label(p, i + 1)} />
            </div>
          ))}
        </>
      )}

      <h2>What each recipe read</h2>
      <p className="muted small">
        Every rate a recipe read, next to what {publisher}&apos;s data feed says for the same day. Green is an exact
        match, red a wrong number: one red cell and the recipe is rejected. Wrong numbers here never left this page.
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

      {c.proposals.length > 0 && (
        <>
          <h2>What the model was asked, and what it said</h2>
          {c.proposals.map((p, i) => (
            <details key={i} className="panel" style={{ marginBottom: 8 }} open={i === 0}>
              <summary>
                Proposal {i + 1}: {detail.model}, {((p.millis ?? 0) / 1000).toFixed(1)} s, {p.promptTokens} tokens in,{" "}
                {p.outputTokens} out
              </summary>
              {p.prompt ? (
                <>
                  <h4>The prompt, exactly as sent</h4>
                  <pre className="mono reply">{p.prompt}</pre>
                </>
              ) : (
                <p className="small muted">The prompt was not recorded for this run.</p>
              )}
              <h4>The reply, exactly as it came back</h4>
              <pre className="mono reply">{p.modelReply}</pre>
              <p className="small muted">
                The date settings are not the model&apos;s: a program finds the date on the page and fills them in.
                {i > 0 && " The second proposal is told why the first was rejected."}
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
