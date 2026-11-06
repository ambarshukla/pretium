import Link from "next/link";

// A guided walk through the site for a first-time visitor: what each page shows, what
// to click, and what to notice. Static on purpose: it has to work even if a backend
// service is down.

const PROOF = "/proof/wb-rpw/f1d7265b1856d61812c158d3d03fae686dbff58735deabe016a6bfe23482d2a8/1212252876?amount=200";

type Step = {
  title: string;
  href: string;
  link: string;
  what: React.ReactNode;
  try: React.ReactNode[];
  notice: React.ReactNode;
};

const STEPS: Step[] = [
  {
    title: "A price, and the proof behind it",
    href: "/?corridor=GBR-IND&amount=200",
    link: "Compare: United Kingdom → India",
    what: (
      <>
        Every provider the World Bank surveyed for sending 120 pounds to India, cheapest first. The total cost is the
        fee plus the markup hidden in the exchange rate.
      </>
    ),
    try: [
      <>Open &ldquo;proof&rdquo; on any row, then &ldquo;the row in the file, and the arithmetic&rdquo;.</>,
      <>
        Or go straight to <Link href={PROOF}>one price&apos;s proof</Link>: the exact row of the 50 MB file it came
        from, the file re-checked against its SHA-256 hash, and the cost recomputed from the cells.
      </>,
    ],
    notice: (
      <>
        Nothing on the page is a number the site made up or copied by hand: each one can be followed back to a stored
        file and a row in it, and each passed checks before it was shown.
      </>
    ),
  },
  {
    title: "Is it working today?",
    href: "/ops",
    link: "Operations",
    what: (
      <>
        Four sources on their own schedules: the World Bank survey, and the ECB&apos;s reference rates through three
        channels (an API, an XML file, and the public web page, scraped).
      </>
    ),
    try: [
      <>&ldquo;Sources&rdquo;: when each was last collected, and whether it is fresh.</>,
      <>&ldquo;Three channels, one number&rdquo;: the same rate read three ways must agree to the last digit.</>,
      <>&ldquo;Held back&rdquo;: prices that failed a check and were not published.</>,
    ],
    notice: (
      <>
        A scraper that silently returns nothing looks healthy until someone notices. Here, freshness, agreement
        between channels and failed checks are on the page, so a problem is visible the day it starts.
      </>
    ),
  },
  {
    title: "When the page changes, the parser repairs itself (and is checked)",
    href: "/repairs",
    link: "Repairs",
    what: (
      <>
        The ECB&apos;s rates page has changed layout several times since 2017. Replaying 119 archived copies, a local
        model proposed new CSS selectors where the old ones broke; a program accepted a proposal only if every number
        it read matched the ECB&apos;s data feed exactly.
      </>
    ),
    try: [
      <>
        <Link href="/repairs/ecb-reference-html/20190104184201">A page the model repaired</Link>: the parser before and
        after as code, the exact prompt and reply, every rate against the reference.
      </>,
      <>
        <Link href="/repairs/ecb-reference-html/20190415031708">A page nothing could repair</Link>: the archived copy
        ends halfway through the table. This is where a person decides; their decision is recorded.
      </>,
    ],
    notice: (
      <>
        The model is never trusted with a number. It proposes selectors, code reads the numbers, a check against an
        independent source decides. A bad proposal costs a retry or a person&apos;s time, never a wrong price.
      </>
    ),
  },
  {
    title: "Debug a parser by hand",
    href: "/lab?page=archive:20190415031708&parser=recipe:2",
    link: "The parser lab, on the broken page",
    what: (
      <>
        The raw page in the browser (safely: nothing on it runs), with what the parser matched outlined, the parser as
        code, and every step of a run.
      </>
    ),
    try: [
      <>Press &ldquo;Run on this page&rdquo;. In &ldquo;Step by step&rdquo;, the last row the parser found is HRK, skipped: the file ends before its rate.</>,
      <>
        &ldquo;Run every known recipe&rdquo;: one strip per parser, one cell per archived page since 2017. You can see
        when each layout of the page was in use.
      </>,
      <>
        &ldquo;Edit a copy&rdquo;, break the rate selector, and watch the outline and the code diff change as you type.
      </>,
    ],
    notice: (
      <>
        The lab runs the same code as production, not a copy of it, so what you see here is what the collector does.
      </>
    ),
  },
  {
    title: "Why not just let the model read the numbers?",
    href: "/models",
    link: "Models compared",
    what: (
      <>
        Three model sizes, the same pages, both approaches: repair the parser, or read the rates directly. Every
        number is checked against the ECB&apos;s feed.
      </>
    ),
    try: [<>Compare &ldquo;wrong numbers published&rdquo; between the two halves of the table.</>],
    notice: (
      <>
        Reading directly, models type rates they could not see and dates that are not on the page, and nothing in
        the output tells those apart from the right ones. That is the case for using models where their work can be
        checked.
      </>
    ),
  },
];

export default function TourPage() {
  return (
    <>
      <h1>A five-minute tour</h1>
      <p className="lede">
        pretium collects what it really costs to send money abroad, from open data, checks every number, and keeps
        the evidence. It is also a working example of a scraper that notices when it breaks, repairs itself with a
        language model, and never lets the model&apos;s guess reach a published number. Five stops, in order:
      </p>

      <ol className="tour">
        {STEPS.map((s, i) => (
          <li key={s.title} className="panel">
            <h2>
              {i + 1}. {s.title}
            </h2>
            <p>
              <Link href={s.href} className="tour-go">
                {s.link} →
              </Link>
            </p>
            <p>{s.what}</p>
            <p className="small">
              <b>Try:</b>
            </p>
            <ul className="small">
              {s.try.map((t, j) => (
                <li key={j}>{t}</li>
              ))}
            </ul>
            <p className="small notice">
              <b>Notice:</b> {s.notice}
            </p>
          </li>
        ))}
      </ol>

      <h2>How it is built</h2>
      <div className="panel small">
        <p>
          Java 21 and Spring Boot services joined by Kafka, PostgreSQL, a Next.js front end, Docker Compose on one
          small server; every merge to <span className="mono">main</span> deploys. The code and its reasoning are on{" "}
          <a href="https://github.com/ambarshukla/pretium">GitHub</a>:
        </p>
        <ul>
          <li>
            <a href="https://github.com/ambarshukla/pretium/blob/main/docs/ARCHITECTURE.md">Architecture</a>: how a
            number travels from source to page.
          </li>
          <li>
            <a href="https://github.com/ambarshukla/pretium/blob/main/docs/DECISIONS.md">Decisions</a>: each choice
            and its trade-off.
          </li>
          <li>
            <a href="https://github.com/ambarshukla/pretium/blob/main/docs/OPERATING.md">Operating it</a>: how a new
            source is requested and added, who approves a repair, and what to do when something breaks.
          </li>
          <li>
            <a href="https://github.com/ambarshukla/pretium/blob/main/docs/BUILD_LOG.md">Build log</a>: what was
            built, in order, and what the real data showed.
          </li>
        </ul>
        <p>
          The method and sources, in plain words: <Link href="/about">Method &amp; sources</Link>.
        </p>
      </div>
    </>
  );
}
