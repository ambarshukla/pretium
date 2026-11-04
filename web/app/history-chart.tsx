import { num, period, type HistoryPoint } from "@/lib/api";

// A plain SVG line chart: cheapest and median total cost per survey period.
// Server-rendered, no charting library: two lines do not justify a dependency.

const W = 1000;
const H = 260;
const PAD = { top: 16, right: 16, bottom: 34, left: 48 };

export function HistoryChart({ points }: { points: HistoryPoint[] }) {
  if (points.length < 2) {
    return <p className="muted">Not enough survey periods to draw a trend.</p>;
  }
  const max = Math.max(...points.map((p) => Number(p.medianPct)), 1) * 1.15;
  const min = Math.min(0, ...points.map((p) => Number(p.cheapestPct)));
  const x = (i: number) => PAD.left + (i * (W - PAD.left - PAD.right)) / (points.length - 1);
  const y = (v: number) => PAD.top + ((max - v) * (H - PAD.top - PAD.bottom)) / (max - min);
  const line = (key: "cheapestPct" | "medianPct") =>
    points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(Number(p[key])).toFixed(1)}`).join(" ");
  const ticks = [min, (min + max) / 2, max].map((v) => Math.round(v * 10) / 10);
  const every = Math.max(1, Math.ceil(points.length / 10));

  return (
    <>
      <div className="legend">
        <span>
          <span className="swatch" style={{ background: "var(--accent)" }} />
          cheapest provider
        </span>
        <span>
          <span className="swatch" style={{ background: "var(--muted)" }} />
          median provider
        </span>
      </div>
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Total cost by survey period">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} stroke="var(--line)" />
            <text x={PAD.left - 8} y={y(t) + 4} textAnchor="end" fontSize="12" fill="var(--muted)">
              {t}%
            </text>
          </g>
        ))}
        {points.map((p, i) =>
          i % every === 0 ? (
            <text key={p.period} x={x(i)} y={H - 10} textAnchor="middle" fontSize="12" fill="var(--muted)">
              {period(p.period)}
            </text>
          ) : null,
        )}
        <path d={line("medianPct")} fill="none" stroke="var(--muted)" strokeWidth="2" />
        <path d={line("cheapestPct")} fill="none" stroke="var(--accent)" strokeWidth="2.5" />
        {points.map((p, i) => (
          <circle key={p.period} cx={x(i)} cy={y(Number(p.cheapestPct))} r="3" fill="var(--accent)">
            <title>
              {period(p.period)}: cheapest {num(p.cheapestPct)}%, median {num(p.medianPct)}%, {p.providers} providers
            </title>
          </circle>
        ))}
      </svg>
    </>
  );
}
