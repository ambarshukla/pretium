-- The catalog's schema. Every stored value carries the run that produced it and
-- the SHA-256 of the raw file it was read from: that pair is the "price proof".

-- One row per collection attempt, including the ones that produced nothing.
create table collection_run (
    run_id          uuid primary key,
    source_id       text        not null,
    url             text        not null,
    started_at      timestamptz not null,
    finished_at     timestamptz not null,
    status          text        not null,
    http_status     int         not null,
    bytes           bigint      not null,
    sha256          text,
    recipe_version  int         not null,
    extracted       int         not null,
    message         text,
    received_at     timestamptz not null default now()
);
create index collection_run_by_source on collection_run (source_id, started_at desc);

-- Reference rates, one row per channel per currency pair per day.
create table reference_rate (
    source_id        text           not null,
    base             char(3)        not null,
    quote            char(3)        not null,
    rate_date        date           not null,
    rate             numeric(24, 10) not null,
    run_id           uuid           not null,
    evidence_sha256  text           not null,
    first_seen_at    timestamptz    not null default now(),
    last_seen_at     timestamptz    not null default now(),
    primary key (source_id, base, quote, rate_date)
);
create index reference_rate_by_pair on reference_rate (base, quote, rate_date desc);

-- Provider prices, one row per service per surveyed amount.
create table provider_quote (
    source_id            text          not null,
    source_record_id     text          not null,
    amount_usd           int           not null,
    period               text          not null,
    send_country         char(3)       not null,
    send_country_name    text,
    receive_country      char(3)       not null,
    receive_country_name text,
    provider             text          not null,
    provider_type        text,
    payment_instrument   text,
    access_point         text,
    speed                text,
    transparent          boolean       not null,
    interbank_rate       numeric(24, 10),
    observed_on          date,
    send_amount          numeric(24, 6),
    send_currency        text,
    fee                  numeric(24, 6),
    fx_rate              numeric(24, 10),
    fx_margin_pct        numeric(12, 4),
    total_cost_pct       numeric(12, 4),
    -- PUBLISHED, PUBLISHED_WITH_WARNINGS or QUARANTINED, decided by the checks.
    status               text          not null,
    findings             jsonb         not null default '[]',
    run_id               uuid          not null,
    evidence_sha256      text          not null,
    first_seen_at        timestamptz   not null default now(),
    last_seen_at         timestamptz   not null default now(),
    primary key (source_id, source_record_id, amount_usd)
);
create index provider_quote_by_corridor on provider_quote (send_country, receive_country, period, amount_usd);
create index provider_quote_by_status on provider_quote (status);

-- History: when a source changes a value it already gave us, the old value is
-- kept here. A trigger does it, so no code path can update a value without
-- leaving the trail.
create table value_revision (
    id            bigserial   primary key,
    subject       text        not null,   -- 'reference_rate' or 'provider_quote'
    subject_key   text        not null,
    field         text        not null,
    old_value     text,
    new_value     text,
    old_run_id    uuid,
    new_run_id    uuid,
    revised_at    timestamptz not null default now()
);
create index value_revision_by_subject on value_revision (subject, subject_key);

create function record_rate_revision() returns trigger language plpgsql as $$
begin
    if new.rate is distinct from old.rate then
        insert into value_revision (subject, subject_key, field, old_value, new_value, old_run_id, new_run_id)
        values ('reference_rate',
                old.source_id || '|' || old.base || '|' || old.quote || '|' || old.rate_date,
                'rate', old.rate::text, new.rate::text, old.run_id, new.run_id);
    end if;
    return new;
end $$;

create trigger reference_rate_revision before update on reference_rate
    for each row execute function record_rate_revision();

create function record_quote_revision() returns trigger language plpgsql as $$
declare
    k text := old.source_id || '|' || old.source_record_id || '|' || old.amount_usd;
begin
    if new.fee is distinct from old.fee then
        insert into value_revision (subject, subject_key, field, old_value, new_value, old_run_id, new_run_id)
        values ('provider_quote', k, 'fee', old.fee::text, new.fee::text, old.run_id, new.run_id);
    end if;
    if new.fx_rate is distinct from old.fx_rate then
        insert into value_revision (subject, subject_key, field, old_value, new_value, old_run_id, new_run_id)
        values ('provider_quote', k, 'fx_rate', old.fx_rate::text, new.fx_rate::text, old.run_id, new.run_id);
    end if;
    if new.total_cost_pct is distinct from old.total_cost_pct then
        insert into value_revision (subject, subject_key, field, old_value, new_value, old_run_id, new_run_id)
        values ('provider_quote', k, 'total_cost_pct', old.total_cost_pct::text, new.total_cost_pct::text, old.run_id, new.run_id);
    end if;
    return new;
end $$;

create trigger provider_quote_revision before update on provider_quote
    for each row execute function record_quote_revision();

-- Disagreements between channels that publish the same number.
create table reconciliation_break (
    base         char(3)         not null,
    quote        char(3)         not null,
    rate_date    date            not null,
    channel      text            not null,   -- the channel that disagrees
    reference    text            not null,   -- the channel it is compared with
    kind         text            not null,   -- VALUE_MISMATCH or MISSING
    value        numeric(24, 10),
    expected     numeric(24, 10),
    detected_at  timestamptz     not null default now(),
    resolved_at  timestamptz,
    primary key (base, quote, rate_date, channel, reference, kind)
);
create index reconciliation_break_open on reconciliation_break (resolved_at) where resolved_at is null;
