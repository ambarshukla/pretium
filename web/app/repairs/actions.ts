"use server";

import { revalidatePath } from "next/cache";
import { collectorPost, viewPart, type Batch, type ViewPart, type Action, type Decision, type LabResult, type PageView, type PageViewPart, type Posted, type Recipe } from "@/lib/collector";

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

/** One recipe over every page the lab has, judged by the verifier. A typed recipe needs the key. */
export async function batchLab(
  choice: { recipeVersion: number } | { recipe: Recipe },
  key: string,
  source?: string,
): Promise<Posted<Batch>> {
  return collectorPost<Batch>("/api/v1/lab/batch", { ...choice, source }, key);
}

/**
 * A raw page, with what the recipe matched outlined. Selectors only: no key needed.
 * Only the part being looked at goes to the browser: a page and its renderings can be 1 MB.
 */
export async function viewPage(
  pageId: string,
  recipe: Recipe | null,
  part: ViewPart | null,
): Promise<Posted<PageViewPart>> {
  const res = await collectorPost<PageView>("/api/v1/lab/view", { pageId, recipe });
  return res.ok ? { ok: true, data: viewPart(res.data, part) } : res;
}
