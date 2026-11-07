import Link from "next/link";
import { num } from "@/lib/api";

export const dynamic = "force-dynamic";

const COLLECTOR_URL = process.env.COLLECTOR_URL ?? "http://localhost:8091";

type Cell = { column: string; value: string };

type Proof = {
  source: string;
  sourceName: string;
  url: string;
  fetchedAt: string;
  sha256: string;
  bytes: number;
  hashMatches: boolean;
  sheet: string;
  recordId: string;
  row: number;
  cells: Cell[];
  millis: number;
};

type Params = Promise<{ source: string; sha: string; record: string }>;
type Search = Promise<{ amount?: string }>;

async function proof(source: string, sha: string, record: string): Promise<{ data?: Proof; error?: string }> {
  try {
    const res = await fetch(
      `${COLLECTOR_URL}/api/v1/evidence/${encodeURIComponent(source)}/${encodeURIComponent(sha)}/records/${encodeURIComponent(record)}`,
      { cache: "no-store", signal: AbortSignal.timeout(120_000) },
    );
    const text = await res.text();
    if (!res.ok) {
      try {
        const j = JSON.parse(text) as { detail?: string; error?: string };
        return { error: j.detail ?? j.error ?? `HTTP ${res.status}` };
      } catch {
        return { error: `HTTP ${res.status}` };
      }
    }
    return { data: JSON.parse(text) as Proof };
  } catch (e) {
    return { error: e instanceof Error ? e.message : String(e) };
  }
}

/** Spreadsheets count days from 30 December 1899. */
function excelDate(v: string): string {
  return new Date(Date.UTC(1899, 11, 30) + Number(v) * 86_400_000).toISOString().slice(0, 10);
}

function n(v: string | undefined): number | null {
  if (v === undefined || v.trim() === "" || v === "..") return null;
  const x = Number(v);
  return Number.isFinite(x) ? x : null;
}

export default async function ProofPage({ params, searchParams }: { params: Params; searchParams: Search }) {
  const { source, sha, record } = await params;
  const { amount } = await searchParams;
  const { data: p, error } = await proof(source, sha, record);

  if (!p) {
    return (
      <>
        <h1>Where this price came from</h1>
        <p className="panel">
          The record could not be shown: {error}. <Link href="/">Back to the comparison</Link>
        </p>
      </>
    );
  }

  const cell = (name: string) => p.cells.find((c) => c.column.toLowerCase() === name.toLowerCase())?.value;
  // The survey prices two amounts per service (cc1, cc2). The one the comparison showed is the
  // tier whose "denomination amount" (in US dollars) is the amount chosen on the page.
  const tier =
    ["cc1", "cc2"].find((t) => cell(`${t} denomination amount`) === (amount ?? "200")) ??
    (amount === "500" ? "cc2" : "cc1");
  const used = new Set(
    [
      "id",
      "period",
      "source_code",
      "destination_code",
      "firm",
      "firm_type",
      "payment instrument",
      "access point",
      "speed actual",
      "transparent",
      "inter lcu bank fx",
      "date",
      `${tier} lcu amount`,
      `${tier} denomination amount`,
      `${tier} lcu code`,
      `${tier} lcu fee`,
      `${tier} lcu fx rate`,
      `${tier} fx margin`,
      `${tier} total cost %`,
    ].map((s) => s.toLowerCase()),
  );

  const sent = n(cell(`${tier} lcu amount`));
  const fee = n(cell(`${tier} lcu fee`));
  const margin = n(cell(`${tier} fx margin`));
  const total = n(cell(`${tier} total cost %`));
  const rate = n(cell(`${tier} lcu fx rate`));
  const interbank = n(cell("inter lcu bank fx"));
  const code = cell(`${tier} lcu code`) ?? "";
  const feePct = sent && fee !== null ? (fee / sent) * 100 : null;
  const sum = feePct !== null && margin !== null ? feePct + margin : null;
  const impliedMargin = rate !== null && interbank ? (1 - rate / interbank) * 100 : null;

  return (
    <>
      <h1>Where this price came from</h1>
      <p className="lede">
        {cell("firm")}, {cell("source_name")} → {cell("destination_name")}, sending {num(sent, 0)} {code} (
        {cell(`${tier} denomination amount`)} US dollars), surveyed in {cell("period")?.replace(/^(\d{4})_(\d)Q$/, "Q$2 $1")}.
        This is the row of the World Bank&apos;s file the comparison read it from, taken from the stored copy of the
        file just now.
      </p>

      <div className="stats">
        <div className="stat">
          <div className="label">File</div>
          <div className="value small mono" style={{ fontSize: 13 }}>
            {p.url.split("/").pop()}
          </div>
        </div>
        <div className="stat">
          <div className="label">Fetched</div>
          <div className="value" style={{ fontSize: 17 }}>
            {p.fetchedAt.slice(0, 16).replace("T", " ")} UTC
          </div>
        </div>
        <div className="stat">
          <div className="label">Still the same file?</div>
          <div className="value" style={{ fontSize: 17 }}>
            {p.hashMatches ? (
              <span className="chip ok">yes, sha256 matches</span>
            ) : (
              <span className="chip ERROR">no: the stored file has changed</span>
            )}
          </div>
        </div>
        <div className="stat">
          <div className="label">Where in the file</div>
          <div className="value" style={{ fontSize: 17 }}>
            row {p.row.toLocaleString("en-GB")}
          </div>
          <div className="small muted">sheet &ldquo;{p.sheet}&rdquo;</div>
        </div>
      </div>

      <h2>The arithmetic, redone</h2>
      <div className="panel">
        <p className="small muted">
          Before a price is published it has to satisfy identities the numbers must obey. Here they are again, from
          the cells below.
        </p>
        <table>
          <tbody>
            <tr>
              <td>Fee as a share of the amount sent</td>
              <td className="mono n">
                {num(fee)} / {num(sent, 0)} × 100 = {num(feePct, 3)}%
              </td>
            </tr>
            <tr>
              <td>Plus the exchange-rate margin</td>
              <td className="mono n">+ {num(margin, 3)}%</td>
            </tr>
            <tr>
              <td>
                <b>Total cost</b>, as the file states it
              </td>
              <td className="mono n">
                = {num(sum, 3)}% {total !== null && sum !== null && Math.abs(total - sum) < 0.01 ? "✓" : "≠"}{" "}
                {num(total, 3)}%
              </td>
            </tr>
            {impliedMargin !== null && (
              <tr>
                <td>The margin, from the provider&apos;s rate and the interbank rate</td>
                <td className="mono n">
                  (1 − {rate} / {num(interbank, 4)}) × 100 = {num(impliedMargin, 3)}%
                  {margin !== null && Math.abs(impliedMargin - margin) < 0.05 ? " ✓" : ""}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <h2>The whole row</h2>
      <div className="panel table-wrap">
        <p className="small muted">
          Every column, exactly as the file has it. Highlighted: what the comparison used for this price.
        </p>
        <table>
          <thead>
            <tr>
              <th>Column</th>
              <th>Value</th>
            </tr>
          </thead>
          <tbody>
            {p.cells
              .filter((c) => c.column !== "" || c.value !== "")
              .map((c, i) => (
                <tr key={i} className={used.has(c.column.toLowerCase()) ? "used" : undefined}>
                  <td className="mono">{c.column || <span className="muted">(no header)</span>}</td>
                  <td className="mono">
                    {c.value === "" ? <span className="muted">empty</span> : c.value}
                    {c.column.toLowerCase() === "date" && /^\d{5}$/.test(c.value) && (
                      <span className="muted"> (a spreadsheet day number: {excelDate(c.value)})</span>
                    )}
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      <h2>Check it yourself</h2>
      <div className="panel small">
        <ol>
          <li>
            Download the file: <a href={p.url}>{p.url}</a> ({(p.bytes / 1_000_000).toFixed(1)} MB).
          </li>
          <li>
            Check it is the same file: <span className="mono">sha256sum {p.url.split("/").pop()}</span> should print{" "}
            <span className="mono">{p.sha256}</span>. If the World Bank has since replaced the file, the hash differs;
            the copy this site read is kept.
          </li>
          <li>
            Open the sheet &ldquo;{p.sheet}&rdquo; and go to row {p.row.toLocaleString("en-GB")}, or search for id{" "}
            <span className="mono">{p.recordId}</span>.
          </li>
        </ol>
        <p className="muted">
          Source: The World Bank, Remittance Prices Worldwide (CC BY 4.0). Looked up in {p.millis} ms.
        </p>
      </div>
    </>
  );
}
