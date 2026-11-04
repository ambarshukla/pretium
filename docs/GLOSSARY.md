# Glossary

**Corridor.** A pair of countries money is sent between, in one direction: United Kingdom
→ India is one corridor, India → United Kingdom another.

**Remittance.** Money sent by a person in one country to a person in another, typically to
family.

**Remittance service provider (RSP).** A business that sends remittances: a bank, a money
transfer operator, a post office, a mobile operator.

**Interbank rate / mid-market rate.** The exchange rate banks trade at between themselves,
the midpoint between buying and selling. The fairest reference for what a currency is
"worth" at a moment.

**FX margin (markup).** How far the rate a provider gives a customer sits below the
interbank rate, in percent. A hidden fee: the customer receives less than the amount at
interbank.

**Total cost.** The fee as a percentage of the amount sent, plus the FX margin.

**Transparent (provider).** One that disclosed the exchange rate it applies. If it did not,
the FX margin cannot be known and the total cost shown understates the real cost.

**Reference rate.** A rate published by a central bank for information. The ECB's euro
reference rates are set once a day at around 14:10 CET and published at around 16:00 CET.

**Channel.** One of several ways a publisher distributes the same numbers (API, file, web
page).

**SDMX (Statistical Data and Metadata eXchange).** The standard that central banks and
statistics offices use to publish data; the ECB's data API speaks it.

**Evidence.** The raw response exactly as received, named by its SHA-256 hash.

**SHA-256.** A cryptographic hash: a 64-character fingerprint of a file. Any change to the
file changes the fingerprint.

**ETag.** A version label a web server gives a file. Sending it back (`If-None-Match`) lets
the server answer "304 Not Modified" instead of sending the file again.

**robots.txt.** A file at the root of a website saying which paths automated clients may
fetch (RFC 9309).

**Recipe.** The versioned set of selectors that tells an extractor where values sit in a
page.

**CSS selector.** A pattern that picks elements out of an HTML page, e.g.
`table.forextable tbody tr` means "every row in the body of the table with class
forextable".

**Identity (check).** A relationship the numbers must satisfy by definition, such as total
cost = fee + margin. A breach means at least one number is wrong.

**Quarantine.** Stored, but not served: the price failed a check badly enough that showing
it would mislead.

**Reconciliation break.** A recorded disagreement between two channels that should agree.

**Revision.** A change a source makes to a value it published earlier. Kept, never
overwritten.

**Kafka topic / partition / offset / consumer group.** A topic is a named, append-only log
of messages; it is split into partitions, each strictly ordered. An offset is a position
in a partition. A consumer group is a named reader that remembers its offsets; a new group
starts from the beginning, which is how the catalog replays.

**KRaft.** Kafka's built-in consensus mode, which replaced ZooKeeper in Kafka 4.

**Upsert.** Insert a row, or update it if one with the same key exists
(`INSERT ... ON CONFLICT DO UPDATE`). Makes re-delivery harmless.

**Idempotent.** Doing it twice has the same effect as doing it once.

**Flyway.** A tool that applies numbered SQL migration files to a database in order, once.

**SLO (service level objective).** A target for how a service should behave, here: how old
each source's last good collection may be.
