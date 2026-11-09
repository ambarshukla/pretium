import Link from "next/link";
import { collector, PUBLISHER, type LabInfo } from "@/lib/collector";
import { LabClient } from "./lab-client";

export const dynamic = "force-dynamic";

type Search = Promise<{ page?: string; parser?: string; source?: string }>;

export default async function LabPage({ searchParams }: { searchParams: Search }) {
  const { page, parser, source } = await searchParams;
  const info = await collector<LabInfo>(`/api/v1/lab?source=${encodeURIComponent(source ?? "ecb-reference-html")}`);
  return (
    <>
      <h1>Parser lab</h1>
      <p className="lede">
        A workbench for the parsers that read rates pages. Pick a source, then a raw copy of its page, and read it here;
        pick a parser (a recipe that once worked, one you write, or what a model proposes), run it, and see every value
        it extracts, step by step, against the publisher&apos;s own data feed for that day. Nothing run here is stored
        or published.
      </p>
      {info && info.sources.length > 1 && (
        <p className="small">
          Source:{" "}
          {info.sources.map((s, i) => (
            <span key={s}>
              {i > 0 && " · "}
              {s === info.source ? (
                <strong>{PUBLISHER[s]?.page ?? s}</strong>
              ) : (
                <Link href={`/lab?source=${encodeURIComponent(s)}`}>{PUBLISHER[s]?.page ?? s}</Link>
              )}
            </span>
          ))}
        </p>
      )}
      {info ? (
        <LabClient key={info.source} info={info} startPage={page} startParser={parser} />
      ) : (
        <p className="panel">The collector is not reachable right now.</p>
      )}
      <p className="small muted">
        How the repairs went over the page&apos;s whole history: <Link href="/repairs">repairs</Link>.
      </p>
    </>
  );
}
