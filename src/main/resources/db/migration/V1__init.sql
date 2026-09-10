-- Initial schema. Money is NUMERIC(15,2), times are TIMESTAMPTZ (UTC).

CREATE TABLE customer (
    id          BIGSERIAL PRIMARY KEY,
    full_name   VARCHAR(200) NOT NULL,
    email       VARCHAR(320) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL
);

-- Sequences give readable business numbers like POL-2026-000123.
CREATE SEQUENCE policy_number_seq;
CREATE SEQUENCE claim_number_seq;

CREATE TABLE policy (
    id              BIGSERIAL PRIMARY KEY,
    policy_number   VARCHAR(30)   NOT NULL UNIQUE,
    customer_id     BIGINT        NOT NULL REFERENCES customer (id),
    type            VARCHAR(20)   NOT NULL CHECK (type IN ('AUTO', 'HOME', 'PROPERTY')),
    coverage_limit  NUMERIC(15,2) NOT NULL CHECK (coverage_limit > 0),
    deductible      NUMERIC(15,2) NOT NULL CHECK (deductible >= 0),
    start_date      DATE          NOT NULL,
    end_date        DATE          NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('ACTIVE', 'LAPSED', 'CANCELLED')),
    CONSTRAINT policy_dates_valid CHECK (end_date > start_date)
);

CREATE INDEX idx_policy_customer_id ON policy (customer_id);

CREATE TABLE claim (
    id               BIGSERIAL PRIMARY KEY,
    claim_number     VARCHAR(30)   NOT NULL UNIQUE,
    policy_id        BIGINT        NOT NULL REFERENCES policy (id),
    incident_date    DATE          NOT NULL,
    reported_at      TIMESTAMPTZ   NOT NULL,
    description      VARCHAR(2000) NOT NULL,
    claimed_amount   NUMERIC(15,2) NOT NULL CHECK (claimed_amount > 0),
    approved_amount  NUMERIC(15,2) CHECK (approved_amount >= 0),
    status           VARCHAR(40)   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_claim_status    ON claim (status);
CREATE INDEX idx_claim_policy_id ON claim (policy_id);

-- One row per fraud flag raised on a claim.
CREATE TABLE claim_fraud_flag (
    claim_id   BIGINT       NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    rule_name  VARCHAR(60)  NOT NULL,
    reason     VARCHAR(500) NOT NULL
);

CREATE INDEX idx_claim_fraud_flag_claim_id ON claim_fraud_flag (claim_id);

-- Audit trail: one row per status change. from_status is NULL for the first event.
CREATE TABLE claim_event (
    id           BIGSERIAL PRIMARY KEY,
    claim_id     BIGINT        NOT NULL REFERENCES claim (id),
    from_status  VARCHAR(40),
    to_status    VARCHAR(40)   NOT NULL,
    reason       VARCHAR(1000),
    actor        VARCHAR(100)  NOT NULL,
    occurred_at  TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_claim_event_claim_id ON claim_event (claim_id);
