# 城市社区配送机器人进楼门禁避障签收服务

外卖 / 药品 / 商超订单交给配送机器人后，本服务负责**进楼配送全链路协同**：记录订单类型、收货楼栋、
门禁授权、是否冷藏、预计送达、电量、载物仓编号与用户联系方式；机器人到小区门口申请物业门禁，
进入楼栋后与电梯系统协同选层，到达后通过短信 / 小程序通知用户取件。

每一次**门禁开通、路线调整、电梯请求、避障停留、签收、退回、客服介入**都写入**同一配送事件流**
（Kafka topic `delivery-events` → `delivery_event` 表），设备动作与业务结果在同一事件里，
支撑物业端（通行影响）、运营端（投放效率）、客服端（异常责任）三类视图。

**冷藏订单超时预警**：配送药品/生鲜途中，服务持续计算载物仓温度与剩余可配送时长；门禁、电梯、
避障、路线绕行造成的等待会推高仓温并按类归因。剩余时长不足或温度越限时分两级预警：
WARNING 提前通知客服与用户，CRITICAL 自动（或人工）退回站点更换商品，**原配送证据封存供赔付判断**；
退回站点后生成**重新配送 / 退款**选项——重新配送会通知门店重新备货并生成新配送单，退款则通知用户。
冷藏超时造成的损失在统一事件流与损失列表中**关联门禁等待、电梯等待和机器人路线**。

## 技术栈

Java 17 · Spring Boot 3.3 · Spring Data JPA · PostgreSQL 16 · Kafka（KRaft）· Flyway · springdoc-openapi（Swagger UI）

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动三个容器：`postgres`(5432)、`kafka`(9092)、`app`(8080)。应用启动时自动执行 Flyway 建表与种子数据。

- Swagger UI（OpenAPI 3.x 在线文档与调试）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

Compose 中为演示设置了较短超时（取件超时 120s、冷藏超时 180s、电梯超时 60s），生产可通过环境变量调大。

## 本地开发启动

依赖：JDK 17+、Maven 3.8+、本地 PostgreSQL（建库 `robot_delivery`，账号 `postgres/postgres`）、本地 Kafka（9092）。

```bash
# 1. 准备依赖（示例： Debian/Ubuntu）
#    PostgreSQL: apt install postgresql && createdb robot_delivery
#    Kafka: 解压官方 tgz，KRaft 模式 kafka-storage.sh format 后 kafka-server-start.sh

# 2. 构建并运行
mvn package -DskipTests
DB_HOST=localhost DB_PORT=5432 KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  java -jar target/robot-delivery-1.0.0.jar
```

常用环境变量（均有默认值，见 `src/main/resources/application.yml`）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | localhost/5432/robot_delivery/postgres/postgres | PostgreSQL 连接 |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka 地址 |
| `PICKUP_TIMEOUT_SECONDS` | 600 | 用户超时不取件时限 |
| `COLDCHAIN_TIMEOUT_SECONDS` | 2700 | 冷藏订单履约时限 |
| `ELEVATOR_TIMEOUT_SECONDS` | 180 | 电梯请求超时 |
| `LOW_BATTERY_THRESHOLD` | 20 | 低电量阈值（%） |
| `SCHEDULER_INTERVAL_MS` | 10000 | 超时巡检间隔 |
| `COLDCHAIN_WARN_LEAD_SECONDS` | 600 | 剩余可配送时长不足该值发出 WARNING 预警 |
| `COLDCHAIN_CRITICAL_LEAD_SECONDS` | 300 | 剩余时长不足该值（或温度临界）判 CRITICAL，自动退回 |
| `COLDCHAIN_TEMP_SAFE_C` / `COLDCHAIN_TEMP_WARN_C` / `COLDCHAIN_TEMP_CRITICAL_C` | 2 / 8 / 10 | 载物仓安全/预警/临界温度（℃） |
| `COLDCHAIN_TEMP_RISE_PER_WAIT_MINUTE` | 0.5 | 每等待 1 分钟的附加温升（℃），等待推高仓温 |
| `COLDCHAIN_AUTO_RETURN_ENABLED` | true | CRITICAL 风险是否自动退回站点 |

## 测试账号

| 账号 | 密码 | 角色 | 用途 |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | 全部权限 |
| `property` | `property123` | PROPERTY | 物业端：门禁审批、夜间规则、通行影响视图 |
| `ops` | `ops123` | OPS | 运营端：下单、分配机器人、模拟机器人/电梯上报、效率视图 |
| `cs` | `cs123` | CS | 客服端：投诉受理、工单结案、责任追踪 |

调用方式：`POST /api/auth/login` 获取 token，之后请求头携带 `Authorization: Bearer <token>`。
（演示环境密码明文存储、令牌内存保存，生产需替换为安全实现。）

种子数据：社区 `阳光社区`(id=1，夜间 22:00-07:00 限制运行) / `绿洲家园`(id=2)；
楼栋 `1栋`(id=1) / `2栋`(id=2) / `5栋`(id=3)；机器人 `RB-001`(id=1) / `RB-002`(id=2，低电量 15%) / `RB-003`(id=3)，
每台机器人配 A（冷藏）/B（普通）载物仓。

## 本题验证方式

### 方式一：自动化冒烟脚本（推荐）

应用启动后执行（覆盖主流程 + 全部异常场景 + 冷藏超时预警全链路 + 三端视图）：

```bash
./scripts/smoke.sh            # 默认打 http://localhost:8080
```

> 脚本中的超时类场景（超时未取 / 冷藏超时 / 电梯超时 / 冷藏风险预警退回）依赖较短超时配置。
> 用 compose 启动时无需调整（已内置演示参数：冷藏履约 180s、预警提前 45s、严重风险 30s）；本地运行时请显式指定：
> `PICKUP_TIMEOUT_SECONDS=30 COLDCHAIN_TIMEOUT_SECONDS=120 COLDCHAIN_WARN_LEAD_SECONDS=45 COLDCHAIN_CRITICAL_LEAD_SECONDS=30 ELEVATOR_TIMEOUT_SECONDS=20 SCHEDULER_INTERVAL_MS=5000 java -jar ...`

### 方式二：Swagger UI 手动验证

打开 http://localhost:8080/swagger-ui.html ，先调 `POST /api/auth/login`（如 `ops/ops123`），
点 `Authorize` 填入 `Bearer <token>`，再依次调用各接口。

### 方式三：curl 手动走一遍主流程

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"ops","password":"ops123"}' | jq -r .token)
AUTH="Authorization: Bearer $TOKEN"

# 下单（外卖、1栋、不需冷藏）
OID=$(curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "orderType":"FOOD","communityId":1,"buildingId":1,"unitNo":"2单元","roomNo":"901",
  "coldChain":false,"estimatedDeliveryAt":"2026-12-31T23:59:00",
  "userName":"张三","userPhone":"13800000001"}' | jq -r .id)

curl -s -X POST localhost:8080/api/deliveries/$OID/assign        -H "$AUTH" -H 'Content-Type: application/json' -d '{"robotId":1}'
AR=$(curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-gate -H "$AUTH" -H 'Content-Type: application/json' -d '{"gateName":"南门"}' | jq -r .id)
# 物业审批门禁（换 property 账号 token）
PTOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"property","password":"property123"}' | jq -r .token)
curl -s -X POST localhost:8080/api/access-requests/$AR/approve -H "Authorization: Bearer $PTOKEN"
ER=$(curl -s -X POST localhost:8080/api/deliveries/$OID/request-elevator -H "$AUTH" -H 'Content-Type: application/json' -d '{"fromFloor":1,"toFloor":9}' | jq -r .id)
curl -s -X POST localhost:8080/api/elevator-requests/$ER/assign  -H "$AUTH" -H 'Content-Type: application/json' -d '{"carId":"1栋-客梯A"}'
curl -s -X POST localhost:8080/api/elevator-requests/$ER/complete -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-door   -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/open-compartment -H "$AUTH"
curl -s -X POST localhost:8080/api/deliveries/$OID/sign          -H "$AUTH" -H 'Content-Type: application/json' -d '{"signBy":"张三"}'

# 查看统一配送事件时间线（设备动作 + 业务结果在同一事件流里）
curl -s localhost:8080/api/deliveries/$OID/events -H "$AUTH" | jq '.[].eventType'
```

### 异常场景验证入口

| 异常场景 | 触发方式 | 结果（均写入统一事件流） |
|---|---|---|
| 物业门禁拒绝 | `POST /api/access-requests/{id}/deny` | `ACCESS_DENIED` + 订单 EXCEPTION + 客服工单 |
| 老人儿童挡路 | `POST /api/deliveries/{id}/obstacle` `obstacleType=ELDERLY_CHILD` | `OBSTACLE_WAIT`（含停留秒数） |
| 电梯满载 | `POST /api/elevator-requests/{id}/full` | `ELEVATOR_FULL` + `ROUTE_ADJUSTED` + 自动重新请求 |
| 楼道施工 | `obstacleType=CONSTRUCTION` | `OBSTACLE_WAIT` + `ROUTE_ADJUSTED(reason=CONSTRUCTION)` |
| 机器人电量不足 | 分配时电量 < 阈值被拒(409)；配送中 `POST /api/robots/{id}/status` 低电量 | `LOW_BATTERY` + 客服工单，可 `manual-relay` 人工接力 |
| 用户超时不取 | 到门后超过 `PICKUP_TIMEOUT_SECONDS` 未签收 | `PICKUP_TIMEOUT` + 自动退回 `RETURNED` |
| 载物仓打不开 | `POST /api/deliveries/{id}/compartment-fault` | `COMPARTMENT_OPEN_FAILED` + 客服工单 |
| 冷藏订单超时 | 冷藏单超过 `COLDCHAIN_TIMEOUT_SECONDS` 未完成 | `COLDCHAIN_TIMEOUT`（含门禁/电梯/避障/路线等待归因）+ 客服工单 |
| 冷藏超时风险预警 | 见下方「冷藏订单超时预警」专节 | `COLDCHAIN_WARNING`→`COLDCHAIN_RISK_RETURN`→证据封存→重新配送/退款 |
| 投诉送错楼栋 | `POST /api/complaints` `complaintType=WRONG_BUILDING` | `COMPLAINT_FILED` + 客服工单；`redeliver` 改派楼栋产生 `ROUTE_ADJUSTED` |
| 物业限制夜间运行 | `PUT /api/communities/{id}/night-rule` 后夜间申请门禁 | `NIGHT_RESTRICTED` + `ACCESS_DENIED` 自动拒绝 |
| 电梯请求超时 | 请求后超过 `ELEVATOR_TIMEOUT_SECONDS` 未派梯 | `ELEVATOR_TIMEOUT` + 客服工单 |

### 冷藏订单超时预警（药品 / 生鲜）

服务在配送途中持续采样**载物仓温度**与**剩余可配送时长**（`cold_chain_snapshot` 表，巡检周期
`SCHEDULER_INTERVAL_MS`）。温度取「安全温度 + 等待附加温升」模型与设备上报温度的较高者；等待按
**门禁 / 电梯 / 避障 / 路线绕行**归因累计。风险两级：

| 等级 | 触发条件 | 系统动作 |
|---|---|---|
| WARNING | 剩余时长 ≤ `COLDCHAIN_WARN_LEAD_SECONDS` 或仓温 ≥ 预警温度 | `COLDCHAIN_WARNING`，短信+小程序通知用户、客服工作台通知 |
| CRITICAL | 剩余时长 ≤ `COLDCHAIN_CRITICAL_LEAD_SECONDS` 或仓温 ≥ 临界温度 | 再通知一次 + `COLDCHAIN_RISK` 工单；`COLDCHAIN_AUTO_RETURN_ENABLED=true` 时自动退回站点 |

退回站点（自动或 `POST /api/coldchain/orders/{id}/risk-return` 人工）后：

1. `COLDCHAIN_RISK_RETURN` 记录退回；`COLDCHAIN_EVIDENCE_LOCKED` **封存原配送证据**
   （事件时间线、温度曲线、门禁/电梯/避障/路线等待归因），供赔付判断；
2. `COLDCHAIN_OPTION_CREATED` 生成**重新配送 / 退款**两个待处理选项，并先通知门店商品已退回；
3. `POST /api/coldchain/orders/{id}/resolve`：
   - `REDELIVER`：生成新的冷藏配送单（`COLDCHAIN_REDELIVERY_CREATED`），`COLDCHAIN_STOCK_REPREP`
     通知门店立即重新备货；
   - `REFUND`：标记退款（`COLDCHAIN_REFUNDED`），短信/小程序通知用户。
4. 冷藏超时损失可在 `GET /api/coldchain/losses` 按社区查询，**损失与门禁等待、电梯等待、机器人路线关联**。

接口一览（Swagger 见「冷藏超时预警」分组）：

| 方法 路径 | 角色 | 说明 |
|---|---|---|
| `GET  /api/coldchain/orders/{id}/status` | OPS/CS/物业/管理员 | 实时温度、剩余时长、风险等级与等待归因 |
| `POST /api/coldchain/orders/{id}/temperature` | OPS/管理员 | 设备上报仓温并立即重算 `{"temperatureC":8.5}` |
| `GET  /api/coldchain/orders/{id}/snapshots` | OPS/CS/物业/管理员 | 温度/剩余时长采样曲线 |
| `POST /api/coldchain/orders/{id}/risk-return` | OPS/CS/管理员 | 必要时人工退回站点换品（自动退回由巡检触发） |
| `GET  /api/coldchain/orders/{id}/evidence` | OPS/CS/管理员 | 封存的赔付证据 |
| `GET  /api/coldchain/orders/{id}/resolutions` | OPS/CS/管理员 | 重新配送/退款选项 |
| `POST /api/coldchain/orders/{id}/resolve` | CS/OPS/管理员 | 选择 `REDELIVER` 或 `REFUND` |
| `GET  /api/coldchain/alerts` | CS/OPS/管理员 | 预警中的冷藏单 |
| `GET  /api/coldchain/losses?communityId=` | CS/OPS/管理员 | 冷藏超时损失与等待归因 |

curl 走一遍（冷藏单分配后卡在南门门禁不审批，等待推高仓温）：

```bash
# 创建冷藏药品单并分配机器人，到达南门后不审批门禁 -> 门禁持续等待
OID=$(curl -s -X POST localhost:8080/api/orders -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "orderType":"MEDICINE","communityId":1,"buildingId":1,"roomNo":"901","coldChain":true,
  "estimatedDeliveryAt":"2026-12-31T23:59:00","userName":"张三","userPhone":"13800000001"}' | jq -r .id)
curl -s -X POST localhost:8080/api/deliveries/$OID/assign -H "$AUTH" -d '{"robotId":1}'
curl -s -X POST localhost:8080/api/deliveries/$OID/arrive-gate -H "$AUTH" -d '{"gateName":"南门"}'
curl -s -X POST localhost:8080/api/coldchain/orders/$OID/temperature -H "$AUTH" -d '{"temperatureC":8.5}'  # WARNING
curl -s -X POST localhost:8080/api/coldchain/orders/$OID/temperature -H "$AUTH" -d '{"temperatureC":11}'   # CRITICAL -> 自动退回
curl -s localhost:8080/api/coldchain/orders/$OID/evidence -H "$AUTH" | jq .waitAttribution      # 门禁等待归因
curl -s -X POST localhost:8080/api/coldchain/orders/$OID/resolve -H "$AUTH" -d '{"option":"REDELIVER"}'  # 重新配送+门店备货
```

### 三端视图

```bash
# 物业端：机器人是否影响居民通行（避障次数/停留时长/施工绕行/夜间限制/投诉/影响等级）
curl -s "localhost:8080/api/analytics/property/passage-impact?communityId=1" -H "$AUTH"

# 运营端：按小区/楼栋/订单类型评估投放效率（单量/签收率/平均时长/超时数）
curl -s "localhost:8080/api/analytics/ops/efficiency?communityId=1&orderType=FOOD" -H "$AUTH"

# 客服端：异常责任追踪
curl -s "localhost:8080/api/cs/cases?status=OPEN" -H "$AUTH"
curl -s "localhost:8080/api/cs/cases/summary" -H "$AUTH"
```

## 实际验证结论（本仓库交付前已执行）

以下均在本环境真实执行通过（本地 PostgreSQL 15（独立实例端口 5433）+ 内嵌 KRaft Kafka（spring-kafka-test 3.7.1，等效 Kafka 3.7）+ 本应用 jar 监听 8080）：

1. `mvn package` 构建通过，生成 `target/robot-delivery-1.0.0.jar`。
2. 干净数据库上应用启动成功：Flyway 顺序执行 `V1__init`、`V2__seed`、`V3__coldchain_alert`，JPA `ddl-auto=validate` 校验通过，Kafka 连接与 `delivery-events` topic 正常。
3. `./scripts/smoke.sh` 在干净库全量运行 **100 项断言全部通过（0 失败）**：覆盖主流程事件时间线、原有 10 类异常、三端视图与鉴权，以及本次新增的冷藏预警全链路（场景 11/12/13）：
   - 门禁等待 → 8.5℃ WARNING（事件 `reason=ACCESS_WAIT`）→ 短信/小程序/客服三通道通知；
   - 11℃ CRITICAL → 自动退回站点 `RETURNED` → `COLDCHAIN_EVIDENCE_LOCKED` 封存证据 → 生成重新配送/退款两选项 → 门店通知；
   - 选择重新配送生成新冷藏单并 `COLDCHAIN_STOCK_REPREP` 通知门店备货；另一单电梯等待人工退回后选择退款并通知用户；
   - 赔付证据含温度曲线、事件时间线与门禁/电梯等待归因；`/api/coldchain/losses` 损失关联门禁、电梯与机器人路线。
4. 集成测试 `ColdChainAlertIT`（冷藏全链路 1 项）与 `SmokeRegressionIT`（主流程/门禁拒绝/电梯满载重试/冷藏履约超时归因/电梯超时 5 项）**共 6 项全部通过**，确认改造对既有能力无回归。
5. Swagger UI（`/swagger-ui.html`，302 跳转后可访问）与 OpenAPI JSON 正常，共 **49 个路径**，其中「冷藏超时预警」分组 9 个接口。
6. 本轮冒烟后数据库实测：`delivery_event` 176 条（含 `COLDCHAIN_WARNING` 5、`COLDCHAIN_RISK_RETURN` 3、`COLDCHAIN_EVIDENCE_LOCKED` 3、`COLDCHAIN_OPTION_CREATED` 3、`COLDCHAIN_REDELIVERY_CREATED` 1、`COLDCHAIN_REFUNDED` 1、`COLDCHAIN_STOCK_REPREP` 1、`COLDCHAIN_TIMEOUT` 2、温度采样 35），`cold_chain_snapshot` 35 条、`cold_chain_resolution` 6 条、`notification` 36 条、`cs_case` 13 条，消费者落库后均可经事件时间线与各查询接口复核。

> 一键启动仍推荐 `docker compose up --build`（内含 bitnami/kafka:3.7，无需本机装 Kafka）。上面的内嵌 Kafka 仅用于本环境的自动化验证。

## 架构说明

```
机器人/电梯系统(模拟)        物业端            运营端            客服端
      │  上报/回调             │ 审批/规则         │ 下单/调度         │ 结案
      └──────────┬─────────────┴─────────────────┴─────────────────┘
                 ▼
        Spring Boot REST API（/api/**，Bearer 鉴权 + 角色控制）
                 │  每一次设备动作与业务结果
                 ▼
        Kafka topic: delivery-events（key=配送单ID，保证同单有序）
                 ▼
        消费者落库 delivery_event（统一配送事件流）
                 ▼
        PostgreSQL（订单/机器人/门禁/电梯/投诉/工单/通知/事件/冷藏温度快照/处置选项）
```

- **统一配送事件**：`DeliveryEventService.emit()` 是唯一入口，事件类型见 `EventType`，
  事件携带 `actor`（机器人/物业/电梯/用户/系统/运营/客服）、`reason`、`waitSeconds` 与业务 JSON。
- **超时巡检**：`TimeoutScheduler` 周期检查取件超时、冷藏履约超时、电梯超时，自动标记并触发客服介入；
  `ColdChainScheduler` 周期持续计算冷藏单仓温与剩余可配送时长，驱动预警/退回/选项。
- **冷藏超时预警**：`coldchain` 包负责温度/剩余时长计算与门禁/电梯/避障/路线等待归因，
  WARNING/CRITICAL 两级预警通知客服与用户，CRITICAL 退回站点、封存证据并生成重新配送/退款选项。
- **状态机**：`CREATED→ASSIGNED→ACCESS_PENDING→ENTERING→(ELEVATOR_WAIT→ON_FLOOR)→WAITING_PICKUP→COMPARTMENT_OPEN→SIGNED`，
  异常分支 `EXCEPTION / RETURNING→RETURNED / CANCELLED`，非法迁移返回 409。

## 目录结构

```
├── Dockerfile / docker-compose.yml   # 一键启动
├── scripts/smoke.sh                  # 100 项断言的冒烟验证（含冷藏预警全链路）
├── src/main/resources/application.yml
├── src/main/resources/db/migration/  # V1 建表 / V2 种子数据 / V3 冷藏预警
└── src/main/java/com/example/robotdelivery/
    ├── event/        # 统一配送事件：Kafka 生产者/消费者/事件查询
    ├── order/        # 配送订单（类型/楼栋/冷藏/预计送达/联系方式）
    ├── delivery/     # 主流程编排：分配/避障/到门/签收/退回/人工接力/改派
    ├── coldchain/    # 冷藏超时预警：温度/剩余时长计算、等待归因、退回、证据、重新配送/退款
    ├── access/       # 物业门禁协同（含夜间限制自动拒绝）
    ├── elevator/     # 电梯系统协同（派梯/满载/超时）
    ├── robot/        # 机器人与载物仓（低电量上报）
    ├── complaint/    # 用户投诉（送错楼栋等）
    ├── cs/           # 客服工单与责任认定
    ├── analytics/    # 物业通行影响 / 运营投放效率
    ├── notification/ # 短信/小程序/客服/门店通知（模拟）
    ├── scheduler/    # 超时巡检 + 冷藏监控巡检
    └── auth/ config/ # 登录鉴权 / 全局配置（含 Hibernate5 Jackson 模块）
```
