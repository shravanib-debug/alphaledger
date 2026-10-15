-- One logical database per service (architecture.md §1.1, database per service).
-- Runs once, when the Postgres volume is first created.
CREATE DATABASE auth_db;
CREATE DATABASE portfolio_db;
CREATE DATABASE trade_db;
CREATE DATABASE position_db;
CREATE DATABASE valuation_db;
CREATE DATABASE recon_db;
CREATE DATABASE notification_db;
CREATE DATABASE analytics_db;
