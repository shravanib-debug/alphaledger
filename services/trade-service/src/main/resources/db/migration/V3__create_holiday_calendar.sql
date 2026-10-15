-- Local copy of the exchange holiday calendar (database per service), loaded at startup from the committed
-- reference-data/holidays/*.csv files. Drives T+1 settle dates and the auto-settlement job.
CREATE TABLE holiday_calendar (
    holiday_date DATE         NOT NULL,
    exchange     VARCHAR(10)  NOT NULL DEFAULT 'NSE',
    segment      VARCHAR(10)  NOT NULL DEFAULT 'CM',
    description  VARCHAR(200) NOT NULL,
    PRIMARY KEY (holiday_date, exchange, segment)
);
