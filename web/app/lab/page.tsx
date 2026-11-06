import Link from "next/link";
import { collector, type LabInfo } from "@/lib/collector";
import { LabClient } from "./lab-client";

export const dynamic = "force-dynamic";

export default async function LabPage() {
  const info = await collector<LabInfo>("/api/v1/lab");
  return (
    <>
      <h1>Parser lab</h1>
      <p className="lede">
        Pick a raw copy of the ECB rates page and a parser, and see what it reads, rate by rate, against the
        ECB&apos;s own data feed for that day. The parser can be a recipe that once worked, one you write, or whatever
        a local model proposes when shown the page. Nothing run here is stored or published.
      </p>
      {info ? (
        <LabClient info={info} />
      ) : (
        <p className="panel">The collector is not reachable right now.</p>
      )}
      <p className="small muted">
        Pages are the ones the collector fetched itself, plus archived copies where this server has them. How the
        repairs went over the page&apos;s whole history: <Link href="/repairs">repairs</Link>.
      </p>
    </>
  );
}
