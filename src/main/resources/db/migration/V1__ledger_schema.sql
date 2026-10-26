-- Double-entry ledger schema. Kept portable: runs on PostgreSQL in production and H2 (PostgreSQL mode) in tests.

create table account (
    id          uuid           primary key,
    owner_name  varchar(120)   not null,
    currency    varchar(3)     not null,
    type        varchar(16)    not null,   -- CUSTOMER accounts can never go negative; SETTLEMENT accounts fund deposits
    status      varchar(16)    not null,
    balance     numeric(19, 4) not null,
    version     bigint         not null,
    created_at  timestamp(6) with time zone not null
);

create table payment (
    id                 uuid           primary key,
    idempotency_key    varchar(64)    not null,
    request_hash       varchar(64)    not null,
    source_account_id  uuid           not null references account (id),
    target_account_id  uuid           not null references account (id),
    amount             numeric(19, 4) not null,
    currency           varchar(3)     not null,
    status             varchar(16)    not null,
    reference          varchar(140),
    created_at         timestamp(6) with time zone not null,
    constraint uk_payment_idempotency_key unique (idempotency_key)
);

create table ledger_entry (
    id             uuid           primary key,
    payment_id     uuid           not null references payment (id),
    account_id     uuid           not null references account (id),
    direction      varchar(6)     not null,  -- DEBIT (money out) or CREDIT (money in)
    amount         numeric(19, 4) not null,
    balance_after  numeric(19, 4) not null,
    created_at     timestamp(6) with time zone not null
);

-- Statement queries read one account's entries newest first.
create index ix_ledger_account_created on ledger_entry (account_id, created_at);
create index ix_ledger_payment on ledger_entry (payment_id);

create table outbox_event (
    id              uuid         primary key,
    aggregate_type  varchar(40)  not null,
    aggregate_id    uuid         not null,
    event_type      varchar(60)  not null,
    payload         text         not null,
    created_at      timestamp(6) with time zone not null,
    published_at    timestamp(6) with time zone
);

-- The relay polls for unpublished events in creation order.
create index ix_outbox_pending on outbox_event (published_at, created_at);

-- One settlement account per currency funds opening deposits, so every rupee in the system has a matching entry.
insert into account (id, owner_name, currency, type, status, balance, version, created_at) values
    ('00000000-0000-0000-0000-000000000001', 'INR Settlement', 'INR', 'SETTLEMENT', 'ACTIVE', 0, 0, current_timestamp),
    ('00000000-0000-0000-0000-000000000002', 'USD Settlement', 'USD', 'SETTLEMENT', 'ACTIVE', 0, 0, current_timestamp);
