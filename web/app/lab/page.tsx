import Link from "next/link";
import { collector, type LabInfo } from "@/lib/collector";
import { LabClient } from "./lab-client";

export const dynamic = "force-dynamic";

type Search = Promise<{ page?: string; parser?: string }>;

export default async function LabPage({ searchParams }: { searchParams: Search }) {
  const { page, parser } = await searchParams;
  const info = await collector<LabInfo>("/api/v1/lab");
  return (
    <>
      <h1>Parser lab</h1>
      <p className="lede">
        A workbench for the parser that reads the ECB rates page. Pick a raw copy of the page and read it here, pick a
        parser (a recipe that once worked, one you write, or what a model proposes), run it, and see every value it
        extracts, step by step, against the ECB&apos;s own data feed for that day. Nothing run here is stored or
        published.
      </p>
      {info ? (
        <LabClient info={info} startPage={page} startParser={parser} />
      ) : (
        <p className="panel">The collector is not reachable right now.</p>
      )}
      <p className="small muted">
        How the repairs went over the page&apos;s whole history: <Link href="/repairs">repairs</Link>.
      </p>
    </>
  );
}
