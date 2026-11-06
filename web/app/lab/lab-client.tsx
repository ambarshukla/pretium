"use client";

import { useState, useTransition } from "react";
import type { LabInfo, LabResult, Recipe } from "@/lib/collector";
import { runLab, type RunChoice } from "../repairs/actions";
import { ReadingsCompare, RecipeCompare, Tally } from "../repairs/compare";
import { useReviewKey } from "../repairs/use-review-key";

type Run = { id: number; label: string; result: LabResult };

function pageLabel(p: LabInfo["pages"][number]): string {
  const day = p.when.slice(0, 10);
  return p.origin === "collected" ? `${day} · fetched by the collector` : `${day} · archived copy`;
}

export function LabClient({ info }: { info: LabInfo }) {
  const [key, setKey] = useReviewKey();
  const [pageId, setPageId] = useState(info.pages[0]?.id ?? "");
  const latest = info.recipes.at(-1);
  const [parser, setParser] = useState(latest ? `recipe:${latest.version}` : "custom");
  const [custom, setCustom] = useState(JSON.stringify(info.recipes[0]?.settings ?? {}, null, 2));
  const [runs, setRuns] = useState<Run[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const needsKey = parser.startsWith("model:") || parser === "custom";
  const page = info.pages.find((p) => p.id === pageId);
  const shown = runs.filter((r) => r.result.pageId === pageId);

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
      try {
        choice = { recipe: JSON.parse(custom) as Recipe };
      } catch {
        setError("The recipe is not valid JSON.");
        return;
      }
      label = "your recipe";
    }
    setError(null);
    start(async () => {
      const res = await runLab(pageId, choice, key);
      if (res.ok) {
        setRuns((rs) => [...rs, { id: rs.length + 1, label: `${rs.length + 1}. ${label}`, result: res.data }]);
      } else {
        setError(res.error);
      }
    });
  }

  return (
    <>
      <form className="panel picker" onSubmit={run}>
        <label>
          Raw page
          <select value={pageId} onChange={(e) => setPageId(e.target.value)}>
            {info.pages.map((p) => (
              <option key={p.id} value={p.id}>
                {pageLabel(p)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Parser
          <select value={parser} onChange={(e) => setParser(e.target.value)}>
            <optgroup label="Recipes that read a real version of the page">
              {info.recipes.map((r) => (
                <option key={r.version} value={`recipe:${r.version}`}>
                  recipe v{r.version}
                </option>
              ))}
            </optgroup>
            <optgroup label="Ask a model (review key)">
              {info.models.length === 0 && <option disabled>no model runner on this server</option>}
              {info.models.map((m) => (
                <option key={m} value={`model:${m}`}>
                  {m}
                </option>
              ))}
            </optgroup>
            <optgroup label="Write one (review key)">
              <option value="custom">your own recipe</option>
            </optgroup>
          </select>
        </label>
        {needsKey && (
          <label>
            Review key
            <input type="password" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" />
          </label>
        )}
        <button type="submit" disabled={pending || !pageId}>
          {pending ? (parser.startsWith("model:") ? "The model is thinking (about a minute)…" : "Reading…") : "Run"}
        </button>
        {parser === "custom" && (
          <label className="block wide">
            Recipe (JSON)
            <textarea className="mono" rows={9} value={custom} onChange={(e) => setCustom(e.target.value)} />
          </label>
        )}
      </form>
      {error && <p className="chip ERROR">{error}</p>}

      {page && shown.length > 0 && (
        <>
          <h2>Results for this page</h2>
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
          <div className="panel">
            <ReadingsCompare columns={shown.map((r) => ({ label: r.label, readings: r.result.readings }))} />
          </div>
          <h2>The recipes</h2>
          <div className="panel">
            <RecipeCompare columns={shown.map((r) => ({ label: r.label, recipe: r.result.recipe }))} />
          </div>
          {shown
            .filter((r) => r.result.modelReply !== null)
            .map((r) => (
              <details key={r.id} className="panel" style={{ marginTop: 8 }}>
                <summary>What {r.label} was shown, and what it replied</summary>
                <p className="small muted">The error it was given: {r.result.promptError}</p>
                <p className="small muted">The part of the page it was shown (scripts and navigation removed):</p>
                <pre className="mono reply">{r.result.digest}</pre>
                <p className="small muted">Its reply, exactly as it came back:</p>
                <pre className="mono reply">{r.result.modelReply}</pre>
              </details>
            ))}
        </>
      )}
    </>
  );
}
