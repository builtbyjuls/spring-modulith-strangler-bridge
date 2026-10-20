-- Wholesale Energy Settlement Reference Schema
-- PostgreSQL 16 Authority Migration

CREATE TABLE market_participant (
    id BIGSERIAL PRIMARY KEY,
    participant_code VARCHAR(32) NOT NULL UNIQUE,
    short_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL
);

CREATE TABLE bilateral_contract (
    id BIGSERIAL PRIMARY KEY,
    contract_code VARCHAR(64) NOT NULL UNIQUE,
    seller_id BIGINT NOT NULL REFERENCES market_participant(id),
    buyer_id BIGINT NOT NULL REFERENCES market_participant(id)
);

CREATE INDEX idx_contract_seller ON bilateral_contract(seller_id);
CREATE INDEX idx_contract_buyer ON bilateral_contract(buyer_id);

CREATE TABLE contract_declaration (
    id BIGSERIAL PRIMARY KEY,
    trading_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    bilateral_contract_id BIGINT NOT NULL REFERENCES bilateral_contract(id)
);

CREATE INDEX idx_decl_trading_date ON contract_declaration(trading_date);
CREATE INDEX idx_decl_contract ON contract_declaration(bilateral_contract_id);

CREATE TABLE interval_quantity (
    id BIGSERIAL PRIMARY KEY,
    dispatch_interval INTEGER NOT NULL,
    grid_node_code VARCHAR(32) NOT NULL,
    declared_mwh NUMERIC(18, 6) NOT NULL,
    declaration_id BIGINT NOT NULL REFERENCES contract_declaration(id),
    CONSTRAINT uk_interval_decl_node UNIQUE (declaration_id, dispatch_interval, grid_node_code)
);

CREATE INDEX idx_interval_decl_id ON interval_quantity(declaration_id);

CREATE TABLE meter_reading_header (
    id BIGSERIAL PRIMARY KEY,
    meter_code VARCHAR(64) NOT NULL,
    reading_date DATE NOT NULL
);

CREATE INDEX idx_meter_reading_date ON meter_reading_header(reading_date);

CREATE TABLE meter_interval_reading (
    id BIGSERIAL PRIMARY KEY,
    interval_index INTEGER NOT NULL,
    actual_mwh NUMERIC(18, 6) NOT NULL,
    header_id BIGINT NOT NULL REFERENCES meter_reading_header(id)
);

CREATE INDEX idx_meter_reading_header ON meter_interval_reading(header_id);
