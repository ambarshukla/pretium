export default function AboutPage() {
  return (
    <div className="prose">
      <h1>Method &amp; sources</h1>
      <p className="lede">How the numbers on this site are collected, checked and shown.</p>

      <h2>Sources</h2>
      <ul>
        <li>
          <strong>World Bank Remittance Prices Worldwide.</strong> Every quarter the World Bank surveys what
          providers charge to send the equivalent of USD 200 and USD 500 across several hundred corridors: the fee,
          the exchange rate applied, and how far that rate sits from the interbank rate. Used under CC BY 4.0. The
          World Bank does not endorse this site.
        </li>
        <li>
          <strong>European Central Bank euro reference rates</strong>, collected three ways: the SDMX data API,
          the daily XML file and the public web page. The web page is read with CSS selectors, the way a scraper
          reads any page, and checked to the last digit against the other two.
        </li>
      </ul>

      <h2>How a number gets here</h2>
      <ol>
        <li>
          A collector fetches each source on its own schedule. It identifies itself, reads robots.txt first,
          waits between requests, and asks conditionally so an unchanged file is not downloaded again. If a site
          refuses, the run is recorded as blocked and left alone.
        </li>
        <li>The response is kept exactly as received, named by its SHA-256 hash: the evidence.</li>
        <li>
          An extractor reads the observations out of it and publishes them as events on Kafka. Every observation
          carries the run that produced it and the hash of its evidence.
        </li>
        <li>
          The catalog checks every price before storing it. The strongest checks are identities the numbers must
          satisfy: total cost equals fee plus exchange-rate margin; the margin equals the gap between the
          provider&apos;s rate and the interbank rate. A small breach is flagged; a large one holds the price back.
        </li>
        <li>
          Channels that publish the same rate are reconciled daily, and every value a source later changes is kept
          as a revision.
        </li>
      </ol>

      <h2>Reading the checks</h2>
      <ul>
        <li>
          <span className="chip WARN">better-than-interbank</span> the provider applied a rate better than
          interbank: usually a first-transfer promotion or a parallel-market rate. Real, but not an everyday price.
        </li>
        <li>
          <span className="chip INFO">rate-not-disclosed</span> the provider did not say what rate it applies, so
          the true cost may be higher than shown.
        </li>
        <li>
          <span className="chip INFO">margin-unverifiable</span> the source published the rates with too few digits
          for the margin to be checked.
        </li>
        <li>
          <span className="chip WARN">margin-identity</span> / <span className="chip WARN">cost-identity</span> the
          source&apos;s own numbers disagree with each other by more than rounding.
        </li>
      </ul>
    </div>
  );
}
