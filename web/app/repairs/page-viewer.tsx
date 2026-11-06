"use client";

import { useEffect, useMemo, useRef, useState, useTransition } from "react";
import type { Mark, PageViewPart, Recipe, ViewPart } from "@/lib/collector";
import { viewPage } from "./actions";

// One raw page in the browser: rendered (in a sandboxed frame that runs no script and
// loads nothing from anywhere), as source with line numbers, and as the cut-down part
// a model is shown, with the prompt it would get. Whatever the chosen recipe's
// selectors match is outlined in the rendering and highlighted in the source.

export type Outline = { label: string; recipe: Recipe };

type Tab = "region" | "page" | "source" | "digest" | "prompt";

const PART: Record<Tab, ViewPart | null> = {
  region: "region",
  page: "rendered",
  source: "source",
  digest: null,
  prompt: null,
};

const KIND_TEXT: Record<Mark["kind"], string> = {
  date: "dateSelector",
  row: "rowSelector",
  currency: "currencySelector",
  rate: "rateSelector",
};

export function PageViewer({
  pageId,
  outlines,
  initial,
  pick,
  onPick,
}: {
  pageId: string;
  outlines: Outline[];
  /** The "region" part for the last outline, fetched by the server with the page. */
  initial?: PageViewPart | null;
  /** Which outline is shown; controlled by the parent when given. */
  pick?: number;
  onPick?: (i: number) => void;
}) {
  const [own, setOwn] = useState(Math.max(0, outlines.length - 1));
  const chosen = Math.min(pick ?? own, Math.max(0, outlines.length - 1));
  const outline = outlines[chosen];
  const wanted = `${pageId}|${JSON.stringify(outline?.recipe ?? null)}`;
  const [tab, setTab] = useState<Tab>("region");
  const part = PART[tab];
  const key = `${wanted}|${part}`;

  // What has been fetched, by page, recipe and part; and the last thing shown, kept on
  // screen while the next one loads.
  const [cache, setCache] = useState<Record<string, PageViewPart>>(() =>
    initial ? { [`${wanted}|region`]: initial } : {},
  );
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const shown = useRef<PageViewPart | null>(initial ?? null);
  const latest = useRef(key);
  latest.current = key;

  const view = cache[key] ?? null;
  if (view) shown.current = view;
  const display = view ?? shown.current;

  useEffect(() => {
    if (cache[key] || !pageId) return;
    const recipe = JSON.parse(wanted.slice(pageId.length + 1)) as Recipe | null;
    // Wait for typing to pause: every change of a selector re-reads the page.
    const timer = setTimeout(() => {
      start(async () => {
        const res = await viewPage(pageId, recipe, part);
        if (res.ok) {
          setCache((c) => ({ ...c, [key]: res.data }));
          if (latest.current === key) setError(null);
        } else if (latest.current === key) {
          setError(res.error);
        }
      });
    }, 400);
    return () => clearTimeout(timer);
  }, [key, cache, pageId, wanted, part]);

  const counts = useMemo(() => {
    const c: Record<string, number> = { date: 0, row: 0, currency: 0, rate: 0 };
    for (const m of display?.marks ?? []) c[m.kind]++;
    return c;
  }, [display]);

  const stale = !view;
  const tabs: { id: Tab; text: string }[] = [
    { id: "region", text: "Rendered: around the matches" },
    { id: "page", text: "Rendered: whole page" },
    {
      id: "source",
      text: `Source${display ? ` (${display.lines.toLocaleString()} lines, ${Math.round(display.bytes / 1024)} KB)` : ""}`,
    },
    { id: "digest", text: "What a model is shown" },
    { id: "prompt", text: "The prompt a model gets" },
  ];
  const content = display && display.part === part ? display.content : null;

  return (
    <div className="panel viewer">
      <div className="viewer-bar">
        <div className="tabs">
          {tabs.map((t) => (
            <button key={t.id} type="button" className={tab === t.id ? "tab on" : "tab"} onClick={() => setTab(t.id)}>
              {t.text}
            </button>
          ))}
        </div>
        {outlines.length > 0 && (
          <label className="small">
            Outline what this recipe matches{" "}
            <select
              value={chosen}
              onChange={(e) => (onPick ? onPick(Number(e.target.value)) : setOwn(Number(e.target.value)))}
            >
              {outlines.map((o, i) => (
                <option key={i} value={i}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      <p className="legend small">
        <span className="key k-date">date</span> {counts.date} · <span className="key k-row">row</span> {counts.row} ·{" "}
        <span className="key k-currency">currency</span> {counts.currency} · <span className="key k-rate">rate</span>{" "}
        {counts.rate}
        {(pending || stale) && <span className="muted"> · loading…</span>}
        {display?.archiveUrl && (
          <>
            {" "}
            · <a href={display.archiveUrl}>this copy at the Internet Archive</a>
          </>
        )}
        <span className="muted"> · Source: ECB</span>
      </p>
      {error && <p className="chip ERROR">{error}</p>}
      {display?.selectorError && <p className="chip ERROR">Not a valid selector: {display.selectorError}</p>}
      <div style={{ opacity: stale ? 0.55 : 1 }}>
        {!display ? (
          <p className="muted">Loading the page…</p>
        ) : tab === "region" ? (
          content ? (
            <iframe className="page-frame" sandbox="" srcDoc={content} title="The part of the page the recipe matched" />
          ) : display.part === "region" ? (
            <p className="muted">
              The recipe matched nothing on this page, so there is nothing to zoom in on: see the whole page or the
              source.
            </p>
          ) : null
        ) : tab === "page" ? (
          content && <iframe className="page-frame" sandbox="" srcDoc={content} title="The page as archived" />
        ) : tab === "source" ? (
          content !== null && <SourceView source={content} marks={display.marks} />
        ) : tab === "digest" ? (
          <>
            <p className="small muted">
              Scripts, styles and navigation removed, then cut to the markup around the first cell that says USD, at
              most 6,000 characters (<span className="mono">PageDigest.java</span>). This is all of the page a model
              sees.
            </p>
            <pre className="mono reply">{display.digest}</pre>
          </>
        ) : (
          <>
            <p className="small muted">
              Exactly what the repair loop would send for this page: the recipe in use, its error here, and the digest.
              A model replies with selectors only, never rates. Paste this into any model, and try its reply as your
              own recipe.
            </p>
            <pre className="mono reply">{display.prompt}</pre>
          </>
        )}
      </div>
    </div>
  );
}

function SourceView({ source, marks }: { source: string; marks: Mark[] }) {
  const lines = useMemo(() => source.split("\n"), [source]);
  const byLine = useMemo(() => {
    const m = new Map<number, Set<Mark["kind"]>>();
    for (const k of marks) {
      if (!m.has(k.line)) m.set(k.line, new Set());
      m.get(k.line)!.add(k.kind);
    }
    return m;
  }, [marks]);
  const box = useRef<HTMLDivElement>(null);
  const [filter, setFilter] = useState("");
  const [from, setFrom] = useState(0);

  function jump(line: number) {
    const el = box.current?.querySelector<HTMLElement>(`[data-line="${line}"]`);
    if (el && box.current) box.current.scrollTop = el.offsetTop - box.current.clientHeight / 3;
  }

  function find(e: React.FormEvent) {
    e.preventDefault();
    if (!filter) return;
    const needle = filter.toLowerCase();
    let i = lines.findIndex((l, n) => n >= from && l.toLowerCase().includes(needle));
    if (i < 0) i = lines.findIndex((l) => l.toLowerCase().includes(needle));
    if (i >= 0) {
      jump(i + 1);
      setFrom(i + 1);
    }
  }

  const firsts = (["date", "row", "currency", "rate"] as const)
    .map((k) => ({ k, m: marks.find((x) => x.kind === k) }))
    .filter((x) => x.m);
  return (
    <>
      <form className="small source-bar" onSubmit={find}>
        Jump to:{" "}
        {firsts.map(({ k, m }) => (
          <button key={k} type="button" className="link" onClick={() => jump(m!.line)}>
            first {KIND_TEXT[k]} match (line {m!.line})
          </button>
        ))}
        {firsts.length === 0 && <span className="muted">the recipe matched nothing </span>}
        <input
          type="text"
          placeholder="find text, Enter for next"
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setFrom(0);
          }}
        />
      </form>
      <div className="source" ref={box}>
        {lines.map((l, i) => {
          const kinds = byLine.get(i + 1);
          return (
            <div key={i} data-line={i + 1} className={kinds ? `src ${[...kinds].map((k) => `k-${k}`).join(" ")}` : "src"}>
              <span className="ln">{i + 1}</span>
              {l || " "}
            </div>
          );
        })}
      </div>
    </>
  );
}
