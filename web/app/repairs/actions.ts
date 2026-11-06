"use server";

import { revalidatePath } from "next/cache";
import { collectorPost, type Action, type Decision, type LabResult, type Posted, type Recipe } from "@/lib/collector";

// Server actions: the browser calls these, and only this Next.js server talks to the
// collector. The review key travels from the form to the collector and is checked there.

export async function decide(
  source: string,
  snapshot: string,
  action: Action,
  note: string,
  recipe: Recipe | null,
  key: string,
): Promise<Posted<Decision>> {
  const res = await collectorPost<Decision>(
    `/api/v1/repairs/${encodeURIComponent(source)}/${encodeURIComponent(snapshot)}/decisions`,
    { action, note, recipe },
    key,
  );
  if (res.ok) {
    revalidatePath("/repairs");
    revalidatePath(`/repairs/${source}/${snapshot}`);
  }
  return res;
}

export type RunChoice = { recipeVersion: number } | { model: string } | { recipe: Recipe };

export async function runLab(pageId: string, choice: RunChoice, key: string): Promise<Posted<LabResult>> {
  return collectorPost<LabResult>("/api/v1/lab/runs", { pageId, ...choice }, key);
}
