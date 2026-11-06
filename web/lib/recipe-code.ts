import type { Recipe } from "./collector";

// A recipe is data: CSS selectors and a date pattern. The code that applies it is one
// generic Java class (collector/.../extract/HtmlRateTableExtractor.java). To read a recipe
// as a parser, and to see what a repair changed as a code change, this writes out the
// Java that the extractor in effect runs with that recipe, step for step.

export const EXTRACTOR_URL =
  "https://github.com/ambarshukla/pretium/blob/main/collector/src/main/java/io/pretium/collector/extract/HtmlRateTableExtractor.java";

function str(s: string | undefined): string {
  if (s === undefined) return "null /* missing */";
  return `"${s.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

export function recipeCode(recipe: Recipe): string {
  const r = recipe;
  const lines = [
    `Document page = Jsoup.parse(file, "UTF-8");`,
    ``,
    `// 1. The date the rates are for`,
    `Element dateElement = page.selectFirst(${str(r.dateSelector)});`,
    `String dateText = dateElement.text().strip();`,
  ];
  if (r.dateRegex) {
    lines.push(
      `Matcher m = Pattern.compile(${str(r.dateRegex)}).matcher(dateText);`,
      `m.find();`,
      `dateText = m.group(1).strip();`,
    );
  }
  lines.push(
    `LocalDate date = LocalDate.parse(dateText,`,
    `    DateTimeFormatter.ofPattern(${str(r.dateFormat)}, Locale.ENGLISH));`,
    ``,
    `// 2. One row per currency`,
    `for (Element row : page.select(${str(r.rowSelector)})) {`,
    `  Element currency = row.selectFirst(${str(r.currencySelector)});`,
    `  Element rate = row.selectFirst(${str(r.rateSelector)});`,
    `  if (currency == null || rate == null) continue; // header or spacer row`,
    `  emit(${str(r.base ?? "EUR")}, currency.text(), date, new BigDecimal(rate.text()));`,
    `}`,
  );
  return lines.join("\n");
}

export type DiffLine = { op: " " | "-" | "+"; text: string; oldNo: number | null; newNo: number | null };

/** Line diff by longest common subsequence. Recipes are a few dozen lines, so O(n·m) is fine. */
export function lineDiff(a: string, b: string): DiffLine[] {
  const x = a.split("\n");
  const y = b.split("\n");
  const n = x.length;
  const m = y.length;
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = x[i] === y[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n || j < m) {
    if (i < n && j < m && x[i] === y[j]) {
      out.push({ op: " ", text: x[i], oldNo: i + 1, newNo: j + 1 });
      i++;
      j++;
    } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
      out.push({ op: "+", text: y[j], oldNo: null, newNo: j + 1 });
      j++;
    } else {
      out.push({ op: "-", text: x[i], oldNo: i + 1, newNo: null });
      i++;
    }
  }
  // Show removals before additions within a changed block, as diffs usually do.
  for (let k = 0; k < out.length; k++) {
    let end = k;
    while (end < out.length && out[end].op !== " ") end++;
    if (end > k) {
      const block = out.slice(k, end);
      const sorted = [...block.filter((d) => d.op === "-"), ...block.filter((d) => d.op === "+")];
      out.splice(k, end - k, ...sorted);
      k = end;
    }
  }
  return out;
}

export const RECIPE_KEYS = [
  "dateSelector",
  "dateRegex",
  "dateFormat",
  "rowSelector",
  "currencySelector",
  "rateSelector",
  "base",
] as const;
