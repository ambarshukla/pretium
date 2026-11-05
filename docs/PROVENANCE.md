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
  file `rpw_dataset_2011_2025_q3.xlsx` on `datacatalogfiles.worldbank.org`.
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

`ecb-sdmx-2026-10-29.csv` holds the 29 rows for 29 October 2026, copied verbatim from the
SDMX history file collected on 4 November 2026 (Source: ECB). It is the answer key for a test
in which a recipe reads the 3 November page but takes its date from a news caption on it.
