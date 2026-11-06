import Link from "next/link";
import { ACTION_TEXT, collector, OUTCOME_TEXT, snapshotDate, type Listing } from "@/lib/collector";

export const dynamic = "force-dynamic";

export default async function RepairsPage() {
  const listing = await collector<Listing>("/api/v1/repairs");

  if (!listing) {
    return (
      <>
        <h1>Repairs</h1>
        <p className="panel">The collector is not reachable right now, so the repair record cannot be shown.</p>
      </>
    );
  }

  const cases = listing.cases;
  const open = cases.filter((c) => c.outcome === "HUMAN_NEEDED" && !c.decision);
  const unconfirmed = cases.filter((c) => c.outcome === "HEALED" && !c.decision);

  return (
    <>
      <h1>Repairs</h1>
      <p className="lede">
        When the ECB redesigns its rates page, the recipe that reads it breaks. A local model then proposes a new
        one, and a program checks every number the proposal reads against the ECB&apos;s own data feed for that day.
        Only an exact match is accepted. This page lists every time that happened, so a person can check the
        machine&apos;s work. Where nothing worked, it asks for a person.
      </p>

      <div className="stats">
        <div className="stat">
          <div className="label">Pages a recipe could not read</div>
          <div className="value">{cases.length}</div>
        </div>
        <div className="stat">
          <div className="label">Repaired by the model, verified</div>
          <div className="value">{cases.filter((c) => c.outcome === "HEALED").length}</div>
        </div>
        <div className="stat">
          <div className="label">Waiting for a person</div>
          <div className="value">{open.length}</div>
        </div>
        <div className="stat">
          <div className="label">Repairs not yet confirmed</div>
          <div className="value">{unconfirmed.length}</div>
        </div>
      </div>

      <div className="panel table-wrap">
        <table>
          <thead>
            <tr>
              <th>Page as archived</th>
              <th>Rates for</th>
              <th>What happened</th>
              <th>Recipe</th>
              <th className="n">Model proposals</th>
              <th>Review</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => (
              <tr key={`${c.source}/${c.snapshot}`}>
                <td>
                  <Link href={`/repairs/${c.source}/${c.snapshot}`}>{snapshotDate(c.snapshot)}</Link>
                </td>
                <td>{c.ratesDate === "null" ? <span className="muted">unknown</span> : c.ratesDate}</td>
                <td>
                  <span className={`chip ${c.outcome === "HUMAN_NEEDED" ? "ERROR" : c.outcome === "HEALED" ? "ok" : "INFO"}`}>
                    {OUTCOME_TEXT[c.outcome]}
                  </span>
                </td>
                <td className="mono">
                  v{c.fromVersion}
                  {c.toVersion !== null ? ` → v${c.toVersion}` : ""}
                </td>
                <td className="n">{c.proposals}</td>
                <td>
                  {c.decision ? (
                    <span className="chip ok">{ACTION_TEXT[c.decision.action]}</span>
                  ) : c.outcome === "HUMAN_NEEDED" ? (
                    <span className="chip WARN">needs a decision</span>
                  ) : (
                    <span className="muted small">not reviewed</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="small muted">
        From a replay of the page&apos;s archived history ({Object.values(listing.models).join(", ")}, run{" "}
        {Object.values(listing.generatedAt)
          .map((g) => g.slice(0, 10))
          .join(", ")}
        ). Try any recipe or model against any page in the <Link href="/lab">parser lab</Link>.
        {listing.reviewsEnabled ? "" : " Reviews are read-only on this server."}
      </p>
    </>
  );
}
