-- Double-entry ledger schema for MySQL 8. Runs at startup (spring.sql.init) and is safe to re-run.
-- Ids are UUID strings (CHAR(36)); the same script runs on H2 in MySQL mode for tests.

create table if not exists account (
    id          char(36)       not null primary key,
    owner_name  varchar(120)   not null,
    currency    varchar(3)     not null,
    type        varchar(16)    not null,   -- CUSTOMER accounts can never go negative; SETTLEMENT accounts fund deposits
    status      varchar(16)    not null,
    balance     decimal(19, 4) not null,
    version     bigint         not null,
    created_at  datetime(6)    not null
);

create table if not exists payment (
    id                 char(36)       not null primary key,
    idempotency_key    varchar(64)    not null,
    request_hash       varchar(64)    not null,
    source_account_id  char(36)       not null,
    target_account_id  char(36)       not null,
    amount             decimal(19, 4) not null,
    currency           varchar(3)     not null,
    status             varchar(16)    not null,
    reference          varchar(140),
    created_at         datetime(6)    not null,
    constraint uk_payment_idempotency_key unique (idempotency_key),
    constraint fk_payment_source foreign key (source_account_id) references account (id),
    constraint fk_payment_target foreign key (target_account_id) references account (id)
);

create table if not exists ledger_entry (
    id             char(36)       not null primary key,
    payment_id     char(36)       not null,
    account_id     char(36)       not null,
    direction      varchar(6)     not null,  -- DEBIT (money out) or CREDIT (money in)
    amount         decimal(19, 4) not null,
    balance_after  decimal(19, 4) not null,
    created_at     datetime(6)    not null,
    -- Statement queries read one account's entries newest first.
    index ix_ledger_account_created (account_id, created_at),
    index ix_ledger_payment (payment_id),
    constraint fk_ledger_payment foreign key (payment_id) references payment (id),
    constraint fk_ledger_account foreign key (account_id) references account (id)
);

create table if not exists outbox_event (
    id              char(36)     not null primary key,
    aggregate_type  varchar(40)  not null,
    aggregate_id    char(36)     not null,
    event_type      varchar(60)  not null,
    payload         text         not null,
    created_at      datetime(6)  not null,
    published_at    datetime(6),
    -- The relay polls for unpublished events in creation order.
    index ix_outbox_pending (published_at, created_at)
);

-- One settlement account per currency funds opening deposits, so every rupee in the system has a matching entry.
insert ignore into account (id, owner_name, currency, type, status, balance, version, created_at) values
    ('00000000-0000-0000-0000-000000000001', 'INR Settlement', 'INR', 'SETTLEMENT', 'ACTIVE', 0, 0, current_timestamp(6)),
    ('00000000-0000-0000-0000-000000000002', 'USD Settlement', 'USD', 'SETTLEMENT', 'ACTIVE', 0, 0, current_timestamp(6));
