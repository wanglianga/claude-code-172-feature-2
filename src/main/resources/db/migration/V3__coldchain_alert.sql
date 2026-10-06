-- 冷藏订单超时预警：载物仓温度/剩余可配送时长持续采样、门禁/电梯/路线等待归因、
-- 风险预警通知、超时风险退回站点、退回后重新配送/退款选项与门店备货通知。

-- 配送单补充冷藏预警状态
alter table delivery_order
    add column cold_chain_warned_at       timestamp,       -- 首次超时风险预警（WARNING）时间
    add column cold_chain_critical_at     timestamp,       -- 严重风险（CRITICAL）时间，自动退回判定点
    add column cold_chain_last_temp       numeric(5, 2),   -- 最新载物仓温度（℃，持续计算/设备上报）
    add column cold_chain_returned_risk   boolean not null default false, -- 因冷藏超时风险退回站点
    add column cold_chain_evidence_locked boolean not null default false; -- 原配送证据已封存（供赔付判断）

-- 载物仓温度与剩余可配送时长快照：服务持续计算并写入，等待按门禁/电梯/避障/路线归因
create table cold_chain_snapshot (
    id                    bigserial primary key,
    delivery_id           bigint       not null references delivery_order (id),
    temperature_c         numeric(5,2) not null,        -- 载物仓当前温度
    remaining_seconds     bigint       not null,        -- 剩余可配送时长（距冷藏履约截止）
    risk_level            varchar(10)  not null,        -- NORMAL / WARNING / CRITICAL
    phase                 varchar(20),                  -- 机器人当前配送阶段（订单状态）
    access_wait_seconds   bigint       not null default 0,  -- 累计门禁等待
    elevator_wait_seconds bigint       not null default 0,  -- 累计电梯等待
    obstacle_wait_seconds bigint       not null default 0,  -- 累计避障停留
    route_wait_seconds    bigint       not null default 0,  -- 累计路线调整损失（满载/施工等）
    total_wait_seconds    bigint       not null default 0,  -- 等待合计
    risk_reason           varchar(40),                  -- 主导风险原因
    created_at            timestamp    not null
);
create index idx_ccs_delivery on cold_chain_snapshot (delivery_id, created_at);
create index idx_ccs_risk on cold_chain_snapshot (risk_level);

-- 退回站点后的处置选项：重新配送（门店重新备货）或退款
create table cold_chain_resolution (
    id                  bigserial primary key,
    delivery_id         bigint      not null references delivery_order (id),
    option_type         varchar(20) not null,          -- REDELIVER / REFUND
    status              varchar(20) not null,          -- PENDING / REDELIVERING / REFUNDED / CANCELLED
    new_order_id        bigint references delivery_order (id), -- 重新配送生成的新配送单
    restock_required    boolean     not null default false,   -- 门店是否需要重新备货
    restock_notified_at timestamp,                            -- 已通知门店重新备货时间
    decided_by          varchar(64),
    decided_at          timestamp,
    note                varchar(255),
    created_at          timestamp   not null,
    updated_at          timestamp   not null
);
create index idx_ccr_delivery on cold_chain_resolution (delivery_id);
