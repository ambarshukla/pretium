"use client";

import { useState, useTransition } from "react";
import type { LabInfo, LabPage, LabResult, Recipe } from "@/lib/collector";
import { OUTCOME_TEXT, snapshotDate } from "@/lib/collector";
import { RECIPE_KEYS } from "@/lib/recipe-code";
import { batchLab, runLab, type RunChoice } from "../repairs/actions";
import { BatchStrips, type NamedBatch } from "./batch-view";
import { CodeDiff, ExtractorNote, RecipeCode, TraceView } from "../repairs/code";
import { ReadingsCompare, Tally } from "../repairs/compare";
import { PageViewer, type Outline } from "../repairs/page-viewer";
import { useReviewKey } from "../repairs/use-review-key";

type Run = { id: number; label: string; result: LabResult };
type Named = { label: string; recipe: Recipe };

function pageLabel(p: LabPage, info: LabInfo): string {
  if (p.origin === "collected") return `${p.when.slice(0, 16).replace("T", " ")} UTC · fetched by the collector`;
  const ts = p.id.slice("archive:".length);
  const outcome = info.cases[ts];
  return `${snapshotDate(ts)} · archived${outcome ? ` · repair case: ${OUTCOME_TEXT[outcome]}` : ""}`;
}

function groups(info: LabInfo): { label: string; pages: LabPage[] }[] {
  const collected = info.pages.filter((p) => p.origin === "collected");
  const archived = info.pages.filter((p) => p.origin === "archive");
  const cases = archived.filter((p) => info.cases[p.id.slice(8)]);
  const byYear = new Map<string, LabPage[]>();
  for (const p of archived) {
    const y = p.id.slice(8, 12);
    if (!byYear.has(y)) byYear.set(y, []);
    byYear.get(y)!.push(p);
  }
  return [
    { label: "Fetched by the collector", pages: collected },
    { label: "Repair cases", pages: cases },
    ...[...byYear.entries()].map(([y, pages]) => ({ label: `Archived in ${y}`, pages })),
  ].filter((g) => g.pages.length > 0);
}

export function LabClient({ info, startPage, startParser }: { info: LabInfo; startPage?: string; startParser?: string }) {
  const [key, setKey] = useReviewKey();
  const known = new Map(info.recipes.map((r) => [r.version, r.settings]));
  const inUse = known.get(info.inUse) ?? info.recipes.at(-1)?.settings ?? {};
  const [pageId, setPageId] = useState(
    startPage && info.pages.some((p) => p.id === startPage) ? startPage : (info.pages[0]?.id ?? ""),
  );
  const [parser, setParser] = useState(startParser ?? `recipe:${info.inUse}`);
  const [custom, setCustom] = useState<Recipe>({ ...inUse });
  const [customFrom, setCustomFrom] = useState<Named>({ label: `recipe v${info.inUse}`, recipe: inUse });
  const [paste, setPaste] = useState("");
  const [runs, setRuns] = useState<Run[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const [outlinePick, setOutlinePick] = useState(0);
  const [diffA, setDiffA] = useState(0);
  const [diffB, setDiffB] = useState(-1);
  const [batches, setBatches] = useState<NamedBatch[]>([]);
  const [batchPending, startBatch] = useTransition();

  const needsKey = parser.startsWith("model:") || parser === "custom";
  const shown = runs.filter((r) => r.result.pageId === pageId);
  const order = info.pages.map((p) => p.id);
  const at = order.indexOf(pageId);

  const selected: Named | null = parser.startsWith("recipe:")
    ? { label: `recipe v${parser.slice(7)}`, recipe: known.get(Number(parser.slice(7))) ?? {} }
    : parser === "custom"
      ? { label: "your recipe, as typed", recipe: custom }
      : null;

  // Everything that can be outlined on the page or diffed: known recipes, the draft, and every run here.
  const recipes: Named[] = [
      ...info.recipes.map((r) => ({
        label: `recipe v${r.version}${r.version === info.inUse ? " (in use)" : ""}`,
        recipe: r.settings,
      })),
      ...(parser === "custom" ? [{ label: "your recipe, as typed", recipe: custom }] : []),
    ...shown.map((r) => ({ label: `run ${r.label}`, recipe: r.result.recipe })),
  ];
  const outlines: Outline[] = recipes;
  const pick = Math.min(outlinePick, outlines.length - 1);
  const b = diffB < 0 || diffB >= recipes.length ? recipes.length - 1 : diffB;
  const a = Math.min(diffA, recipes.length - 1);

  function choosePage(id: string) {
    setPageId(id);
    setError(null);
  }

  function chooseParser(p: string) {
    setParser(p);
    if (p.startsWith("recipe:")) {
      const i = info.recipes.findIndex((r) => `recipe:${r.version}` === p);
      if (i >= 0) setOutlinePick(i);
    }
    if (p === "custom") setOutlinePick(info.recipes.length);
  }

  function startFrom(n: Named) {
    setCustom({ ...n.recipe });
    setCustomFrom(n);
    chooseParser("custom");
  }

  function fillFromJson() {
    try {
      const j = JSON.parse(paste) as Record<string, unknown>;
      const next: Recipe = { ...custom };
      for (const k of RECIPE_KEYS) {
        const v = j[k];
        if (typeof v === "string") next[k] = v;
        if (v === null) delete next[k];
      }
      setCustom(next);
      setError(null);
    } catch {
      setError("That is not JSON. Paste an object with keys like rowSelector and rateSelector.");
    }
  }

  function keepBatch(label: string, batch: NamedBatch["batch"]) {
    setBatches((bs) => [...bs.filter((b) => b.label !== label), { label, batch }]);
  }

  function runBatch() {
    if (parser.startsWith("model:")) return;
    const label = parser === "custom" ? "your recipe" : `recipe v${parser.slice(7)}`;
    const choice = parser === "custom" ? { recipe: custom } : { recipeVersion: Number(parser.slice(7)) };
    setError(null);
    startBatch(async () => {
      const res = await batchLab(choice, key);
      if (res.ok) keepBatch(label, res.data);
      else setError(res.error);
    });
  }

  function runAllKnown() {
    setError(null);
    startBatch(async () => {
      for (const r of info.recipes) {
        const res = await batchLab({ recipeVersion: r.version }, key);
        if (!res.ok) {
          setError(res.error);
          return;
        }
        keepBatch(`recipe v${r.version}`, res.data);
      }
    });
  }

  function run(e: React.FormEvent) {
    e.preventDefault();
    let choice: RunChoice;
    let label: string;
    if (parser.startsWith("recipe:")) {
      choice = { recipeVersion: Number(parser.slice(7)) };
      label = `recipe v${parser.slice(7)}`;
    } else if (parser.startsWith("model:")) {
      choice = { model: parser.slice(6) };
      label = parser.slice(6);
    } else {
      choice = { recipe: custom };
      label = "your recipe";
    }
    setError(null);
    start(async () => {
      const res = await runLab(pageId, choice, key);
      if (res.ok) {
        setRuns((rs) => {
          const next = [...rs, { id: rs.length + 1, label: `${rs.length + 1}. ${label}`, result: res.data }];
          const here = next.filter((r) => r.result.pageId === pageId).length;
          const base = info.recipes.length + (parser === "custom" ? 1 : 0);
          setOutlinePick(base + here - 1);
          setDiffB(base + here - 1);
          return next;
        });
      } else {
        setError(res.error);
      }
    });
  }

  return (
    <>
      <h2>1. The raw page</h2>
      <div className="panel picker">
        <label>
          Page
          <select value={pageId} onChange={(e) => choosePage(e.target.value)}>
            {groups(info).map((g) => (
              <optgroup key={g.label} label={g.label}>
                {g.pages.map((p) => (
                  <option key={`${g.label}-${p.id}`} value={p.id}>
                    {pageLabel(p, info)}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>
        <button type="button" disabled={at <= 0} onClick={() => choosePage(order[at - 1])}>
          ← newer
        </button>
        <button type="button" disabled={at < 0 || at >= order.length - 1} onClick={() => choosePage(order[at + 1])}>
          older →
        </button>
        <span className="small muted">
          {info.pages.length} pages: what the collector fetched itself, and copies from the Internet Archive
        </span>
      </div>
      {pageId && <PageViewer pageId={pageId} outlines={outlines} pick={pick} onPick={setOutlinePick} />}

      <h2>2. The parser</h2>
      <form className="panel" onSubmit={run}>
        <div className="picker">
          <label>
            Parser
            <select value={parser} onChange={(e) => chooseParser(e.target.value)}>
              <optgroup label="Recipes that read a real version of the page">
                {info.recipes.map((r) => (
                  <option key={r.version} value={`recipe:${r.version}`}>
                    recipe v{r.version}
                    {r.version === info.inUse ? " (in use)" : ""}
                  </option>
                ))}
              </optgroup>
              <optgroup label="Ask a model for a recipe (review key)">
                {info.models.length === 0 && <option disabled>no model runner on this server</option>}
                {info.models.map((m) => (
                  <option key={m} value={`model:${m}`}>
                    {m}
                  </option>
                ))}
              </optgroup>
              <optgroup label="Write or edit one (review key)">
                <option value="custom">your own recipe</option>
              </optgroup>
            </select>
          </label>
          {selected && parser !== "custom" && (
            <button type="button" onClick={() => startFrom(selected)}>
              Edit a copy
            </button>
          )}
          {needsKey && (
            <label>
              Review key
              <input type="password" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" />
            </label>
          )}
          <button type="submit" disabled={pending || !pageId}>
            {pending ? (parser.startsWith("model:") ? "The model is thinking (about a minute)…" : "Reading…") : "Run on this page"}
          </button>
          {!parser.startsWith("model:") && (
            <button type="button" disabled={batchPending} onClick={runBatch}>
              {batchPending ? "Reading every page…" : `Run on every page (${info.pages.length})`}
            </button>
          )}
        </div>

        {parser.startsWith("recipe:") && selected && (
          <>
            <ExtractorNote />
            <RecipeCode recipe={selected.recipe} />
          </>
        )}

        {parser.startsWith("model:") && (
          <p className="small muted">
            The model is sent the prompt in the page&apos;s last tab and replies with selectors. A program then finds the
            date on the page itself and fills in the date settings, and the result is run and checked like any recipe.
            Nothing it proposes is kept.
          </p>
        )}

        {parser === "custom" && (
          <>
            <div className="fields">
              {RECIPE_KEYS.map((k) => (
                <label key={k} className="block">
                  <span className="mono">{k}</span>
                  <input
                    type="text"
                    className="mono"
                    value={custom[k] ?? ""}
                    onChange={(e) => setCustom({ ...custom, [k]: e.target.value })}
                  />
                </label>
              ))}
            </div>
            <details className="small">
              <summary>Fill from JSON, such as a model&apos;s reply to the prompt</summary>
              <textarea className="mono" rows={6} value={paste} onChange={(e) => setPaste(e.target.value)} />
              <button type="button" onClick={fillFromJson}>
                Fill the fields
              </button>
            </details>
            <p className="small muted">
              What your edits change, as code (against {customFrom.label}). The outline on the page follows the fields
              as you type.
            </p>
            <CodeDiff
              before={customFrom.recipe}
              after={custom}
              beforeLabel={customFrom.label}
              afterLabel="your recipe"
            />
          </>
        )}
      </form>
      {error && <p className="chip ERROR">{error}</p>}

      <h2>3. Over every page</h2>
      {batches.length === 0 ? (
        <p className="muted">
          &ldquo;Run on every page&rdquo; reads all {info.pages.length} pages with the chosen recipe and checks each
          against the ECB&apos;s data feed, in a few seconds.{" "}
          <button type="button" className="link" disabled={batchPending} onClick={runAllKnown}>
            {batchPending ? "Reading…" : "Run every known recipe"}
          </button>{" "}
          to see when each layout of the page was in use.
        </p>
      ) : (
        <BatchStrips batches={batches} current={pageId} onPick={choosePage} />
      )}

      <h2>4. What it read on this page</h2>
      {shown.length === 0 ? (
        <p className="muted">Run a parser on this page to see every value it extracts, step by step.</p>
      ) : (
        <>
          <ul className="small">
            {shown.map((r) => (
              <li key={r.id}>
                <b>{r.label}</b>: <Tally a={r.result} />
                {r.result.millis !== null &&
                  ` · ${(r.result.millis / 1000).toFixed(1)} s, ${r.result.promptTokens} tokens in, ${r.result.outputTokens} out`}
                {r.result.ratesDate && ` · rates for ${r.result.ratesDate}`}
              </li>
            ))}
          </ul>
          <h3>Every rate, every run, against the ECB&apos;s data feed</h3>
          <div className="panel">
            <ReadingsCompare columns={shown.map((r) => ({ label: r.label, readings: r.result.readings }))} />
          </div>

          <h3>Step by step</h3>
          {shown.map((r, i) => (
            <details key={r.id} className="panel run" open={i === shown.length - 1}>
              <summary>
                <b>{r.label}</b>: <Tally a={r.result} />
              </summary>
              {r.result.trace && <TraceView trace={r.result.trace} readings={r.result.readings} />}
              <h4>The parser that ran</h4>
              <RecipeCode recipe={r.result.recipe} />
              {r.result.prompt && (
                <>
                  <h4>The prompt it was sent</h4>
                  <pre className="mono reply">{r.result.prompt}</pre>
                </>
              )}
              {r.result.modelReply !== null && (
                <>
                  <h4>Its reply, exactly as it came back</h4>
                  <pre className="mono reply">{r.result.modelReply}</pre>
                  <button
                    type="button"
                    onClick={() => startFrom({ label: `run ${r.label}`, recipe: r.result.recipe })}
                  >
                    Edit a copy of this recipe
                  </button>
                </>
              )}
            </details>
          ))}
        </>
      )}

      <h2>5. Compare two parsers as code</h2>
      <div className="panel">
        <div className="picker">
          <label>
            From
            <select value={a} onChange={(e) => setDiffA(Number(e.target.value))}>
              {recipes.map((n, i) => (
                <option key={i} value={i}>
                  {n.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            To
            <select value={b} onChange={(e) => setDiffB(Number(e.target.value))}>
              {recipes.map((n, i) => (
                <option key={i} value={i}>
                  {n.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        {recipes[a] && recipes[b] && (
          <CodeDiff
            before={recipes[a].recipe}
            after={recipes[b].recipe}
            beforeLabel={recipes[a].label}
            afterLabel={recipes[b].label}
          />
        )}
      </div>
    </>
  );
}
