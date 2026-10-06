-- 冷链超时预警：持续温度监控、风险预警、退回换品、重新配送/退款、损失归因

-- 1) 配送单扩展冷链风控字段
alter table delivery_order add column cold_chain_warn_level   varchar(10);  -- NONE/WARN/CRITICAL
alter table delivery_order add column cold_chain_warned_at   timestamp;
alter table delivery_order add column cold_chain_critical_at timestamp;
alter table delivery_order add column last_temp_celsius      numeric(5, 2);
alter table delivery_order add column remaining_seconds      bigint;        -- 最近一次计算的剩余可配送时长
alter table delivery_order add column last_calc_at           timestamp;
alter table delivery_order add column cold_return_for_swap   boolean not null default false; -- 退回站点更换商品
alter table delivery_order add column replacement_of_id      bigint references delivery_order (id); -- 本单是哪张单的重新配送
alter table delivery_order add column store_name             varchar(64);   -- 备货门店（药品/生鲜）
alter table delivery_order add column restock_notified       boolean not null default false;

create index idx_order_cold_risk
    on delivery_order (cold_chain, cold_chain_warn_level, status);

-- 2) 载物仓温度快照（持续计算，每次等待门禁/电梯/避障/路线与周期巡检均会产生）
create table cold_chain_snapshot (
    id                 bigserial primary key,
    delivery_id        bigint      not null references delivery_order (id),
    robot_id           bigint,
    compartment_no     varchar(10),
    temp_celsius       numeric(5, 2) not null,
    remaining_seconds  bigint      not null,
    warn_level         varchar(10) not null,
    -- 累计等待（秒），用于损失关联门禁/电梯/机器人路线
    access_wait_seconds   bigint not null default 0,
    elevator_wait_seconds bigint not null default 0,
    obstacle_wait_seconds bigint not null default 0,
    moving_seconds        bigint not null default 0,
    risk_factor        varchar(30),  -- 主要风险因素 ACCESS_WAIT/ELEVATOR_WAIT/OBSTACLE_WAIT/COMPARTMENT_WARMING
    phase              varchar(24),  -- 配送阶段
    note               varchar(255),
    created_at         timestamp   not null
);
create index idx_cold_snapshot_delivery on cold_chain_snapshot (delivery_id, created_at);

-- 3) 退回站点后的处置选项（重新配送 / 退款）
create table cold_chain_resolution (
    id                bigserial primary key,
    delivery_id       bigint      not null references delivery_order (id),
    option_type       varchar(12) not null,   -- REDELIVER / REFUND
    status            varchar(16) not null,   -- PENDING / ACCEPTED / REJECTED
    -- REDELIVER 时生成的新配送单
    new_delivery_id   bigint references delivery_order (id),
    restock_required  boolean     not null default false,
    store_name        varchar(64),
    store_notified_at timestamp,
    decided_by        varchar(64),
    decided_at        timestamp,
    note              varchar(255),
    created_at        timestamp   not null,
    updated_at        timestamp   not null
);
create index idx_cold_resolution_delivery on cold_chain_resolution (delivery_id);

-- 4) 冷链超时损失（关联门禁等待、电梯等待与机器人路线，供赔付判断）
create table cold_chain_loss (
    id                     bigserial primary key,
    delivery_id            bigint      not null references delivery_order (id),
    loss_amount            numeric(10, 2) not null default 0,
    goods_status           varchar(20) not null, -- SPOILED / AT_RISK / REPLACED / REFUNDED
    access_wait_seconds    bigint not null default 0,
    elevator_wait_seconds  bigint not null default 0,
    obstacle_wait_seconds  bigint not null default 0,
    moving_seconds         bigint not null default 0,
    -- 机器人路线证据（路线调整次数、绕行说明、关键轨迹）
    route_adjustment_count int    not null default 0,
    route_evidence        text,
    suggested_responsibility varchar(20) not null default 'NONE',
    responsibility_detail   varchar(255),
    evidence_refs         text,   -- 原配送证据引用（事件/快照/门禁与电梯请求）
    recorded_by           varchar(64),
    created_at            timestamp   not null
);
create index idx_cold_loss_delivery on cold_chain_loss (delivery_id);
