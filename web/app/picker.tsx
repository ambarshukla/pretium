"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { Corridor } from "@/lib/api";

// The corridor and amount picker. A client component so that it can
//  - show the amount in the sender's own currency (it changes with the corridor),
//  - swap the direction of a corridor with one click (only where the reverse corridor was surveyed),
//  - tell the reader something is happening while the next page loads, instead of freezing.

type Props = { small: Corridor[]; large: Corridor[]; send: string; receive: string; amount: number };

const key = (send: string, receive: string) => `${send}-${receive}`;

export function Picker({ small, large, send, receive, amount }: Props) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [corridor, setCorridor] = useState(key(send, receive));
  const [tier, setTier] = useState(String(amount));

  // Prefer the larger list if the smaller one failed to load, and vice versa.
  const corridors = small.length >= large.length ? small : large;
  const byKey = (list: Corridor[], k: string) => list.find((c) => key(c.send, c.receive) === k);
  const current = byKey(corridors, corridor);
  const reverse = current ? byKey(corridors, key(current.receive, current.send)) : undefined;

  function go(nextCorridor: string, nextTier: string) {
    setCorridor(nextCorridor);
    setTier(nextTier);
    startTransition(() => router.push(`/?corridor=${nextCorridor}&amount=${nextTier}`));
  }

  function label(list: Corridor[], usd: number) {
    const c = byKey(list, corridor);
    return c ? `${c.sendCurrency} ${Number(c.sendAmount).toLocaleString("en-GB", { maximumFractionDigits: 0 })} (≈ USD ${usd})` : `≈ USD ${usd}`;
  }

  return (
    <div className="picker panel" aria-busy={pending}>
      <label>
        Corridor
        <select value={corridor} onChange={(e) => go(e.target.value, tier)} disabled={pending}>
          {!current && <option value={corridor}>{corridor}</option>}
          {corridors.map((c) => (
            <option key={key(c.send, c.receive)} value={key(c.send, c.receive)}>
              {c.sendName} → {c.receiveName}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        className="swap"
        title={reverse ? "Swap sending and receiving country" : "This direction was not surveyed the other way round"}
        aria-label="Swap sending and receiving country"
        disabled={!reverse || pending}
        onClick={() => reverse && go(key(reverse.send, reverse.receive), tier)}
      >
        ⇄
      </button>
      <label>
        Amount sent
        <select value={tier} onChange={(e) => go(corridor, e.target.value)} disabled={pending}>
          <option value="200">{label(small, 200)}</option>
          <option value="500">{label(large, 500)}</option>
        </select>
      </label>
      <span className="muted small" role="status">
        {pending ? "Loading prices…" : ""}
      </span>
    </div>
  );
}
