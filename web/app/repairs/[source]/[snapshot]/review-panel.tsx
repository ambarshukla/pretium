"use client";

import { useState, useTransition } from "react";
import type { Action, Recipe, RepairCase } from "@/lib/collector";
import { decide } from "../../actions";
import { useReviewKey } from "../../use-review-key";

type Props = {
  source: string;
  snapshot: string;
  outcome: RepairCase["outcome"];
  hasRepair: boolean;
  pageAvailable: boolean;
  startRecipe: Recipe;
};

const CHOICES: { action: Action; text: string; needs: "repair" | "page" | null }[] = [
  { action: "CONFIRM_REPAIR", text: "The repair is right", needs: "repair" },
  { action: "REJECT_REPAIR", text: "Reject the repair (say why)", needs: "repair" },
  { action: "NEW_RECIPE", text: "Write a recipe myself (it is verified first)", needs: "page" },
  { action: "DISMISS_PAGE", text: "The page itself is unreadable (say why)", needs: null },
];

export function ReviewPanel({ source, snapshot, outcome, hasRepair, pageAvailable, startRecipe }: Props) {
  const [key, setKey] = useReviewKey();
  const choices = CHOICES.filter((c) => (c.needs === "repair" ? hasRepair : c.needs === "page" ? pageAvailable : true));
  const [action, setAction] = useState<Action>(choices[0].action);
  const [note, setNote] = useState("");
  const [recipe, setRecipe] = useState(JSON.stringify(startRecipe, null, 2));
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [pending, start] = useTransition();

  function submit(e: React.FormEvent) {
    e.preventDefault();
    let parsed: Recipe | null = null;
    if (action === "NEW_RECIPE") {
      try {
        parsed = JSON.parse(recipe) as Recipe;
      } catch {
        setMessage({ ok: false, text: "The recipe is not valid JSON." });
        return;
      }
    }
    start(async () => {
      const res = await decide(source, snapshot, action, note, parsed, key);
      setMessage(res.ok ? { ok: true, text: "Recorded." } : { ok: false, text: res.error });
    });
  }

  return (
    <form className="panel review" onSubmit={submit}>
      <p className="small muted">
        {outcome === "HUMAN_NEEDED"
          ? "Nothing the machine tried read this page exactly. Decide what to do with it."
          : "The machine's repair passed the verifier. A person can confirm it or reject it."}{" "}
        A recipe a person writes is held to the same rule as the model&apos;s: it is recorded only if every rate it
        reads matches the ECB&apos;s data exactly.
        {!pageAvailable && " This server does not hold the archived page, so a new recipe cannot be checked here."}
      </p>
      <fieldset>
        {choices.map((c) => (
          <label key={c.action} className="radio">
            <input type="radio" name="action" checked={action === c.action} onChange={() => setAction(c.action)} />{" "}
            {c.text}
          </label>
        ))}
      </fieldset>
      {action === "NEW_RECIPE" && (
        <label className="block">
          Recipe (JSON)
          <textarea className="mono" rows={10} value={recipe} onChange={(e) => setRecipe(e.target.value)} />
        </label>
      )}
      <label className="block">
        Note
        <input type="text" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Why" />
      </label>
      <label className="block">
        Review key
        <input type="password" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" />
      </label>
      <button type="submit" disabled={pending}>
        {pending ? "Checking…" : "Record decision"}
      </button>
      {message && <p className={`chip ${message.ok ? "ok" : "ERROR"}`}>{message.text}</p>}
    </form>
  );
}
