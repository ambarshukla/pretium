-- Which currency each country uses, for comparing the World Bank's "interbank rate" with the
-- ECB's rates. The survey records the sending currency but only the receiving *country*.
--
-- Rows: every country in the remittance survey whose currency the ECB quotes (or the euro),
-- taken from the official ISO 4217 lists (SIX, list one published 2026-09-17, and the list of
-- historic currencies for the euro changeovers). country_name is the ISO name, so each row can be
-- checked against the list (CountryCurrencyTest does). Validity is [valid_from, valid_until).
--
-- Left out on purpose: El Salvador, Panama, Haiti (also USD), Namibia, Lesotho (also ZAR), where
-- a transfer may arrive in either legal tender; Kosovo and West Bank and Gaza, which have no
-- ISO 4217 entry. Changeover dates are the month ISO gives; within the ECB history used here
-- (2016 onwards) the two that matter, Croatia and Bulgaria, both changed on 1 January.
create table country_currency (
    country      char(3) not null,
    country_name text    not null,
    currency     char(3) not null,
    valid_from   date,
    valid_until  date,
    primary key (country, currency)
);

insert into country_currency (country, country_name, currency, valid_from, valid_until) values
    ('AUS', 'AUSTRALIA', 'AUD', null, null),
    ('AUT', 'AUSTRIA', 'EUR', null, null),
    ('BEL', 'BELGIUM', 'EUR', null, null),
    ('BGR', 'BULGARIA', 'BGN', null, '2026-01-01'),
    ('BGR', 'BULGARIA', 'EUR', '2026-01-01', null),
    ('BRA', 'BRAZIL', 'BRL', null, null),
    ('CAN', 'CANADA', 'CAD', null, null),
    ('CHE', 'SWITZERLAND', 'CHF', null, null),
    ('CHN', 'CHINA', 'CNY', null, null),
    ('CZE', 'CZECHIA', 'CZK', null, null),
    ('DEU', 'GERMANY', 'EUR', null, null),
    ('DZA', 'ALGERIA', 'DZD', null, null),
    ('ECU', 'ECUADOR', 'USD', null, null),
    ('ESP', 'SPAIN', 'EUR', null, null),
    ('EST', 'ESTONIA', 'EEK', null, '2011-01-01'),
    ('EST', 'ESTONIA', 'EUR', '2011-01-01', null),
    ('FRA', 'FRANCE', 'EUR', null, null),
    ('GBR', 'UNITED KINGDOM OF GREAT BRITAIN AND NORTHERN IRELAND (THE)', 'GBP', null, null),
    ('HRV', 'CROATIA', 'EUR', '2023-01-01', null),
    ('HRV', 'CROATIA', 'HRK', null, '2023-01-01'),
    ('HUN', 'HUNGARY', 'HUF', null, null),
    ('IDN', 'INDONESIA', 'IDR', null, null),
    ('IND', 'INDIA', 'INR', null, null),
    ('ISR', 'ISRAEL', 'ILS', null, null),
    ('ITA', 'ITALY', 'EUR', null, null),
    ('JPN', 'JAPAN', 'JPY', null, null),
    ('KOR', 'KOREA (THE REPUBLIC OF)', 'KRW', null, null),
    ('LTU', 'LITHUANIA', 'EUR', '2014-12-01', null),
    ('LTU', 'LITHUANIA', 'LTL', null, '2014-12-01'),
    ('LVA', 'LATVIA', 'EUR', '2014-01-01', null),
    ('LVA', 'LATVIA', 'LVL', null, '2014-01-01'),
    ('MAR', 'MOROCCO', 'MAD', null, null),
    ('MEX', 'MEXICO', 'MXN', null, null),
    ('MYS', 'MALAYSIA', 'MYR', null, null),
    ('NLD', 'NETHERLANDS (THE)', 'EUR', null, null),
    ('NOR', 'NORWAY', 'NOK', null, null),
    ('NZL', 'NEW ZEALAND', 'NZD', null, null),
    ('PHL', 'PHILIPPINES (THE)', 'PHP', null, null),
    ('POL', 'POLAND', 'PLN', null, null),
    ('PRT', 'PORTUGAL', 'EUR', null, null),
    ('ROU', 'ROMANIA', 'RON', null, null),
    ('RUS', 'RUSSIAN FEDERATION (THE)', 'RUB', null, null),
    ('SGP', 'SINGAPORE', 'SGD', null, null),
    ('SWE', 'SWEDEN', 'SEK', null, null),
    ('THA', 'THAILAND', 'THB', null, null),
    ('TUR', 'TÜRKİYE', 'TRY', null, null),
    ('USA', 'UNITED STATES OF AMERICA (THE)', 'USD', null, null),
    ('ZAF', 'SOUTH AFRICA', 'ZAR', null, null);
