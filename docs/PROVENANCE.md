# Provenance

Everything pretium shows comes from open data, collected at run time. Nothing fetched is
committed to this repository except the small test fixtures listed at the end, which are
verbatim excerpts used under the same terms.

## Sources

### World Bank: Remittance Prices Worldwide (`wb-rpw`)

- **What:** a quarterly survey of what remittance service providers charge to send the
  equivalent of USD 200 and USD 500 across several hundred country corridors: fee, exchange
  rate applied, interbank rate, FX margin and total cost, provider by provider. The
  "complete dataset" workbook, sheet *Dataset (from Q2 2016)*.
- **Where:** <https://datacatalog.worldbank.org/search/dataset/0037898/remittance-prices-worldwide>,
  file `rpw_dataset_2011_2025_q3.xlsx` on `datacatalogfiles.worldbank.org`. Each quarter's
  file has a new address, so before each run the collector asks the World Bank's data
  catalogue API (`ddh-openapi.worldbank.org/datasets/0037898`) which file is current, and
  falls back to the address above if the catalogue does not answer. That host has no
  robots.txt (404), which RFC 9309 reads as no restrictions.
- **Licence:** Creative Commons Attribution 4.0, under the World Bank's Terms of Use for
  Datasets.
- **Required attribution** (shown on every page of the site): *"The World Bank, Remittance
  Prices Worldwide, available at http://remittanceprices.worldbank.org"*.
- **Conditions observed:** no claim or implication of World Bank endorsement; no World Bank
  logos or trademarks.
- **Access note:** the survey's own website (`remittanceprices.worldbank.org`) serves a bot
  challenge to automated clients. pretium does not attempt to pass it. The same dataset is
  published as a plain file on the World Bank's data catalogue, which is what is collected.
- **Provider names** appear in the data as the World Bank publishes them. Their appearance
  implies no relationship with, or endorsement by, any provider.

### European Central Bank: euro foreign exchange reference rates

Collected through three channels, which must agree:

| Source id | Channel | URL |
|---|---|---|
| `ecb-sdmx-exr` | ECB Data Portal SDMX API (CSV) | `data-api.ecb.europa.eu/service/data/EXR/...` |
| `ecb-eurofxref-daily` | Daily XML file | `www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml` |
| `ecb-reference-html` | Public web page, read with CSS selectors | `www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html` |

- **Licence:** ECB statistics and website content may be reproduced provided the source is
  acknowledged. Attribution: *"Source: ECB"*.
- **Note:** the ECB states that its reference rates are for information purposes only.
  pretium uses them as a reference, not as transaction rates.
- **robots.txt** (read on every collection, cached for a day) allows all three URLs.

### Czech National Bank: exchange rate fixing

Collected through two channels, which must agree:

| Source id | Channel | URL |
|---|---|---|
| `cnb-fixing-txt` | Daily text file (the first run reads this year's file) | `www.cnb.cz/en/financial-markets/foreign-exchange-market/central-bank-exchange-rate-fixing/central-bank-exchange-rate-fixing/daily.txt` |
| `cnb-fixing-html` | Public web page, read with CSS selectors | `www.cnb.cz/en/financial-markets/foreign-exchange-market/central-bank-exchange-rate-fixing/central-bank-exchange-rate-fixing/` |

- **Terms** (CNB "Disclaimer & copyright", read 7 November 2026): the website's information
  may be stored, distributed and reproduced, except texts with a named author and images whose
  copyright is not the CNB's. The CNB must be stated as the source, and the content may not be
  altered. Attribution: *"Source: the CNB"*. The rates are stored per one unit of currency
  (the CNB quotes some per 100 or 1,000 units); that is a change of unit, shown as such, and
  the number as published is kept in the evidence file.
- **Note:** the CNB asks users to verify information before commercial use. pretium uses the
  fixing as a reference, not as a transaction rate.
- **robots.txt** (read 7 November 2026) disallows only two unrelated paths; both URLs are
  allowed.
- **Why the CNB:** a second publisher with a public page *and* a structured channel carrying
  the same numbers, so a scraper repair can be checked exactly, as with the ECB. Other central
  banks were considered: Norges Bank's page builds its table in the browser (the HTML has no
  rates), and Danmarks Nationalbank's has no table. The Reserve Bank of Australia's page and
  data file are reusable under CC BY 4.0, but the data file credits its main pair (AUD/USD) to
  WM/Reuters, and the RBA's terms exclude third-party material from that licence: not used.
  The Bank of Canada's page names currencies in words ("Australian dollar") rather than codes,
  so it needs a name-to-code table first: a candidate for later.

### Bank of Canada daily exchange rates (`boc-valet-fx`, `boc-daily-html`)

Collected through two channels, which must agree:

| Source id | Channel | URL |
|---|---|---|
| `boc-valet-fx` | Valet API, CSV (the first run reads from 2017) | `www.bankofcanada.ca/valet/observations/group/FX_RATES_DAILY/csv` |
| `boc-daily-html` | Public web page, read with CSS selectors and a currency-name table | `www.bankofcanada.ca/rates/exchange/daily-exchange-rates/` |

- **Terms** (Bank of Canada "Terms of Use and Disclaimers", read 9 November 2026, section 1):
  website content may be freely used, copied, distributed and transmitted if the Bank is
  attributed as the source, changes are indicated, and due diligence is exercised on accuracy.
  Section 2 excepts bank note images, the logo and wordmark, and third-party content; none is
  used. Attribution: *"Source: Bank of Canada"*. The Bank describes its rates as indicative
  averages of quotes from financial institutions; pretium uses them as a reference.
- **robots.txt** (read 9 November 2026) disallows only the WordPress admin path; both URLs are
  allowed.
- **Name table:** the page writes "Australian dollar", not "AUD".
  `collector/src/main/resources/currency-names/boc.tsv` maps the 24 names on the page to ISO
  codes; the codes were checked against the series list in the Valet CSV (`FXAUDCAD` is
  described as "the Australian dollar in Canadian dollars", and so on). A name not in the table
  stops the extraction, naming it.
- **Why:** a third publisher with a different shape: block-structured CSV with one column per
  currency, and a page that shows five days side by side. In May 2026 the Bank began publishing
  the Malaysian ringgit, Polish zloty and Thai baht and stopped the Russian ruble and Saudi
  riyal (the page says so), so the set of currencies really does change.
- **Fixtures:** `boc-daily-2026-11-09.html` is the page as fetched on 9 November 2026,
  unaltered. `boc-valet-2026-11-03-to-09.csv` is the Valet CSV for `start_date=2026-11-03`,
  unaltered (five days: 3, 4, 5, 6 and 9 November).

### ISO 4217 currency codes (reference table, not collected)

- **What:** which currency each country uses, for the comparison of the survey's interbank rate
  with the ECB's (`catalog/src/main/resources/db/migration/V2__country_currency.sql`).
- **Where:** the ISO 4217 lists published by SIX Financial Information, the ISO 4217
  maintenance agency: list one (current currencies, published 2026-09-17) and list three
  (historic currencies, for the euro changeovers), downloaded on 5 November 2026.
- **Rows:** only countries in the remittance survey whose currency the ECB quotes, or the euro.
  Each row keeps the ISO country name, and a test checks every row against the lists.

## How collection behaves

- The User-Agent names the project and links to this repository.
- robots.txt is read before any fetch; a disallowed URL is not requested.
- Requests to the same host are at least five seconds apart.
- Unchanged files are not downloaded again (conditional requests with `If-None-Match`).
- A refusal (401, 403, 451, or a bot challenge) is recorded as `BLOCKED` and not retried.
  There is no identity rotation, no proxy pool and no challenge solving.

## Test fixtures

`collector/src/test/resources/fixtures/` holds verbatim excerpts of the three ECB
responses for 3 November 2026 (Source: ECB). The World Bank rows used in tests are three
real rows from the Q2 2016 survey, copied into the test code with their record ids.

One navigation-menu link (to the ECB press section) was removed from the 3 November 2026 page;
the rates table and everything the extractor reads are untouched.

`cnb-fixing-2026-11-06.html` and `cnb-fixing-2026-11-06.txt` are the CNB's page and daily text
file as fetched on 7 November 2026, both showing the fixing of 6 November (Source: the CNB). The
text file is verbatim. From the page, two footer links whose text is unrelated to the rates
were removed (the Governor's speeches and articles); the table, the date line and everything
the recipe reads are untouched. `cnb-fixing-year-2005-excerpt.txt` is six verbatim lines of
the CNB's 2005 yearly file (`year.txt?year=2005`): the first two, and the turn of March into
April, when the header changed from 31 currencies to 36.

`ecb-sdmx-2026-10-29.csv` holds the 29 rows for 29 October 2026, copied verbatim from the
SDMX history file collected on 4 November 2026 (Source: ECB). It is the answer key for a test
in which a recipe reads the 3 November page but takes its date from a news caption on it.

`ecb-reference-2019-04-12-archived-truncated.html` is the ECB rates page as archived by the
Internet Archive on 15 April 2019 (snapshot `20190415031708`): from the page's `<main>` element to
the end of the file, verbatim, with a minimal `<html><body>` wrapper in front. The archived copy
itself ends in the middle of the rates table (13 of 36 currencies), which is what the test is
about. `ecb-sdmx-2019-04-12.csv` holds the 36 rows for that day from the SDMX history file.

`collector/src/main/resources/repair/ecb-reference-html.json` is the casebook written by the
repair evaluation (`RepairEval`) over 119 archived copies of the ECB rates page: for each page
the recipe in use could not read, every rate each recipe read, the ECB's rate for the same day,
the prompts as sent and the model's replies exactly as they came back. Rates are Source: ECB.

The deployed server also holds the 119 archived copies of the ECB rates page that the repair
evaluation replays (Internet Archive snapshots, downloaded with the archive's unmodified
`id_` form). They are not in this repository. The parser lab shows them with scripts
removed and nothing loaded from elsewhere, credited "Source: ECB" with a link to each copy
at the Internet Archive.

`collector/src/main/resources/bench/ecb-reference-html.json` is the model comparison written by
`ModelBench` over a sample of the same archived pages: for each page and model, the selectors
proposed, the rates the model typed when asked to read them directly, and how each compares with
the ECB's rate for the day, with the replies exactly as they came back. Rates are Source: ECB.

`wb-rpw-2025q3-three-rows.rows.tsv` holds the header and three rows of the World Bank file
collected on 4 November 2026 (`rpw_dataset_2011_2025_q3.xlsx`, sheet "Dataset (from Q2 2016)"),
cell text verbatim, in the tab-separated form the price-proof index writes. The deployed
server writes the same form for the whole file next to its stored copy; it is a reading aid
and is rebuilt from the original whenever it is missing. Source: The World Bank, Remittance
Prices Worldwide (CC BY 4.0).

`wb-ddh-dataset-0037898.json` is an excerpt of the World Bank data catalogue's entry for the
dataset, captured on 5 November 2026: the identifying fields and the `resources` list,
verbatim (the description, keywords and contact fields are left out).

`catalog/src/test/resources/fixtures/iso4217-excerpt.xml` holds, verbatim, every entry of ISO
4217 lists one and three for the countries in that table (SIX, downloaded 5 November 2026).
The benchmark tests use real survey records from Q2 2016, Q4 2016 and Q3 2025 (record ids in
the test code) and the ECB's reference rates for the days they were observed.

`collector/src/main/resources/repair/cnb-fixing-html.json` is the same kind of casebook for 29
archived copies of the CNB's fixing page (2006 to 2025, from the Internet Archive), each checked
against the CNB's yearly text file for that year. Rates are Source: the CNB.

`cnb-fixing-2025-07-01-archived.html` is the CNB's fixing page as the Internet Archive captured it
on 1 July 2025 (snapshot 20250701172207), unaltered. `cnb-fixing-year-2025-07-01-excerpt.txt` is
two verbatim lines of the CNB's 2025 yearly file: the header and the fixing of 1 July 2025.
