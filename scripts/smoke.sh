#!/usr/bin/env bash
# 冒烟验证：主流程 + 全部异常场景 + 三端视图
# 用法: BASE=http://localhost:8080 ./scripts/smoke.sh
# 依赖: curl, jq
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
FAILS=0

say()  { echo ""; echo "== $* =="; }
ok()   { echo "  [PASS] $1"; }
bad()  { echo "  [FAIL] $1"; FAILS=$((FAILS+1)); }
check(){ # desc actual expected
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1: 期望[$3] 实际[$2]"; fi
}

req() { # method path token [data]
  local method=$1 path=$2 token=${3:-} data=${4:-}
  local args=(-sS -X "$method" "$BASE$path" -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ] && args+=(-d "$data")
  curl "${args[@]}"
}

wait_event() { # deliveryId eventType tries
  local did=$1 type=$2 tries=${3:-40}
  for _ in $(seq "$tries"); do
    if req GET "/api/deliveries/$did/events" "$OPS" | jq -e ".[] | select(.eventType==\"$type\")" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_status() { # deliveryId status tries
  local did=$1 st=$2 tries=${3:-40}
  for _ in $(seq "$tries"); do
    local cur
    cur=$(req GET "/api/orders/$did" "$OPS" | jq -r '.status // empty')
    [ "$cur" = "$st" ] && return 0
    sleep 1
  done
  return 1
}

new_order() { # buildingId coldChain type -> order id
  req POST /api/orders "$OPS" "{
    \"orderType\":\"${3:-FOOD}\",\"communityId\":1,\"buildingId\":$1,
    \"unitNo\":\"2单元\",\"roomNo\":\"901\",\"coldChain\":$2,
    \"estimatedDeliveryAt\":\"2026-12-31T23:59:00\",
    \"userName\":\"张三\",\"userPhone\":\"13800000001\"}" | jq -r '.id'
}

say "0. 认证与权限"
UNAUTH=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/orders")
check "未登录访问受保护接口返回401" "$UNAUTH" "401"
ADMIN=$(req POST /api/auth/login "" '{"username":"admin","password":"admin123"}'   | jq -r .token)
OPS=$(req POST /api/auth/login "" '{"username":"ops","password":"ops123"}'         | jq -r .token)
PROPERTY=$(req POST /api/auth/login "" '{"username":"property","password":"property123"}' | jq -r .token)
CS=$(req POST /api/auth/login "" '{"username":"cs","password":"cs123"}'            | jq -r .token)
[ -n "$OPS" ] && [ "$OPS" != "null" ] && ok "四个测试账号登录成功" || { bad "登录失败"; exit 1; }
FORBID=$(req POST /api/orders "$PROPERTY" '{"orderType":"FOOD","communityId":1,"buildingId":1,"userName":"x","userPhone":"1"}' | jq -r .status)
check "物业账号创建订单被拒绝(403)" "$FORBID" "403"

say "准备：关闭社区1夜间限制（避免测试时段落入夜间窗口）"
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":false,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null

say "1. 主流程：创建->分配->门禁->电梯->避障->到门通知->开仓->签收"
O1=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O1/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE=$(req POST "/api/deliveries/$O1/arrive-gate" "$OPS" '{"gateName":"南门"}')
AR_ID=$(echo "$GATE" | jq -r .id)
check "门禁申请生成" "$(echo "$GATE" | jq -r .status)" "PENDING"
req POST "/api/access-requests/$AR_ID/approve" "$PROPERTY" >/dev/null
EV_REQ=$(req POST "/api/deliveries/$O1/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":9}')
ER_ID=$(echo "$EV_REQ" | jq -r .id)
req POST "/api/elevator-requests/$ER_ID/assign" "$OPS" '{"carId":"1栋-客梯A"}' >/dev/null
req POST "/api/elevator-requests/$ER_ID/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$O1/obstacle" "$OPS" '{"obstacleType":"ELDERLY_CHILD","waitSeconds":30,"location":"1栋大堂"}' >/dev/null
req POST "/api/deliveries/$O1/arrive-door" "$OPS" >/dev/null
NOTIFY=$(req GET "/api/notifications?deliveryId=$O1" "$OPS" | jq 'length')
check "短信+小程序双通道通知" "$NOTIFY" "2"
req POST "/api/deliveries/$O1/open-compartment" "$OPS" >/dev/null
SIGN=$(req POST "/api/deliveries/$O1/sign" "$OPS" '{"signBy":"张三"}')
check "签收后状态" "$(echo "$SIGN" | jq -r .status)" "SIGNED"
ROBOT1=$(req GET "/api/robots?communityId=1" "$OPS" | jq -r '.[] | select(.id==1) | .status')
check "签收后机器人释放为空闲" "$ROBOT1" "IDLE"
if wait_event "$O1" SIGNED; then ok "统一事件流含 SIGNED"; else bad "统一事件流缺少 SIGNED"; fi
EV_COUNT=$(req GET "/api/deliveries/$O1/events" "$OPS" | jq 'length')
[ "$EV_COUNT" -ge 12 ] && ok "订单1事件时间线完整($EV_COUNT条)" || bad "订单1事件过少: $EV_COUNT"
for T in ORDER_CREATED ROBOT_ASSIGNED ACCESS_REQUESTED ACCESS_GRANTED ELEVATOR_REQUESTED ELEVATOR_ASSIGNED ELEVATOR_COMPLETED OBSTACLE_WAIT ARRIVED_AT_DOOR USER_NOTIFIED COMPARTMENT_OPENED SIGNED; do
  if wait_event "$O1" "$T" 3; then ok "事件 $T 已写入统一事件流"; else bad "缺少事件 $T"; fi
done

say "2. 异常：物业门禁拒绝 -> 客服介入 -> 取消"
O2=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O2/assign" "$OPS" '{"robotId":1}' >/dev/null
AR2=$(req POST "/api/deliveries/$O2/arrive-gate" "$OPS" '{"gateName":"北门"}' | jq -r .id)
req POST "/api/access-requests/$AR2/deny" "$PROPERTY" '{"reason":"外来设备未备案"}' >/dev/null
check "门禁拒绝后订单状态" "$(req GET "/api/orders/$O2" "$OPS" | jq -r .status)" "EXCEPTION"
wait_event "$O2" ACCESS_DENIED && ok "ACCESS_DENIED 事件" || bad "缺少 ACCESS_DENIED"
wait_event "$O2" CS_INTERVENTION && ok "客服介入事件 CS_INTERVENTION" || bad "缺少 CS_INTERVENTION"
req POST "/api/deliveries/$O2/cancel" "$OPS" '{"reason":"门禁被拒，取消配送"}' >/dev/null

say "3. 异常：电梯满载 -> 自动等待下一趟(路线调整)"
O3=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O3/assign" "$OPS" '{"robotId":1}' >/dev/null
AR3=$(req POST "/api/deliveries/$O3/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR3/approve" "$PROPERTY" >/dev/null
ER3=$(req POST "/api/deliveries/$O3/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":6}' | jq -r .id)
RETRY=$(req POST "/api/elevator-requests/$ER3/full" "$OPS")
RETRY_ID=$(echo "$RETRY" | jq -r .id)
[ "$RETRY_ID" != "$ER3" ] && [ "$RETRY_ID" != "null" ] && ok "满载后自动重新请求电梯(新请求#$RETRY_ID)" || bad "满载后未自动重新请求"
wait_event "$O3" ELEVATOR_FULL && ok "ELEVATOR_FULL 事件" || bad "缺少 ELEVATOR_FULL"
wait_event "$O3" ROUTE_ADJUSTED && ok "ROUTE_ADJUSTED(电梯满载) 事件" || bad "缺少 ROUTE_ADJUSTED"
req POST "/api/elevator-requests/$RETRY_ID/assign" "$OPS" '{"carId":"1栋-货梯B"}' >/dev/null
req POST "/api/elevator-requests/$RETRY_ID/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O3/sign" "$OPS" '{"signBy":"李女士"}' >/dev/null
check "满载重试后签收" "$(req GET "/api/orders/$O3" "$OPS" | jq -r .status)" "SIGNED"

say "4. 异常：楼道施工 -> 避障停留+路线调整 -> 投诉送错楼栋 -> 客服结案"
O4=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O4/assign" "$OPS" '{"robotId":1}' >/dev/null
AR4=$(req POST "/api/deliveries/$O4/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR4/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O4/obstacle" "$OPS" '{"obstacleType":"CONSTRUCTION","waitSeconds":120,"location":"1栋3层楼道"}' >/dev/null
wait_event "$O4" OBSTACLE_WAIT && ok "OBSTACLE_WAIT(施工) 事件" || bad "缺少 OBSTACLE_WAIT"
req GET "/api/deliveries/$O4/events" "$OPS" | jq -e '.[] | select(.eventType=="ROUTE_ADJUSTED" and .reason=="CONSTRUCTION")' >/dev/null \
  && ok "ROUTE_ADJUSTED(reason=CONSTRUCTION) 事件" || bad "缺少施工路线调整事件"
req POST "/api/deliveries/$O4/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O4/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O4/sign" "$OPS" '{"signBy":"王先生"}' >/dev/null
CMP=$(req POST /api/complaints "$CS" "{\"deliveryId\":$O4,\"complaintType\":\"WRONG_BUILDING\",\"content\":\"用户称应送2栋而非1栋\"}")
CMP_CASE=$(req GET "/api/cs/cases?status=OPEN" "$CS" | jq -r ".[] | select(.deliveryId==$O4) | .id")
[ -n "$CMP_CASE" ] && [ "$CMP_CASE" != "null" ] && ok "投诉自动生成客服工单#$CMP_CASE" || bad "投诉未生成工单"
req POST "/api/cs/cases/$CMP_CASE/resolve" "$CS" '{"responsibility":"PLATFORM","note":"分拣贴错楼栋标签，已致歉"}' >/dev/null
check "结案后投诉状态" "$(req GET /api/complaints "$CS" | jq -r ".[] | select(.delivery.id==$O4) | .status")" "RESOLVED"
wait_event "$O4" CS_CASE_CLOSED && ok "CS_CASE_CLOSED 事件" || bad "缺少 CS_CASE_CLOSED"

say "5. 异常：低电量 -> 分配被拒 / 配送途中低电量 -> 人工接力"
O5=$(new_order 1 false MEDICINE)
LOW=$(req POST "/api/deliveries/$O5/assign" "$OPS" '{"robotId":2}')
check "低电量机器人(RB-002,15%)分配被拒(409)" "$(echo "$LOW" | jq -r .status)" "409"
req POST /api/robots/2/status "$OPS" '{"battery":90}' >/dev/null
req POST "/api/deliveries/$O5/assign" "$OPS" '{"robotId":2}' >/dev/null
check "充电后分配成功" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "ASSIGNED"
AR5=$(req POST "/api/deliveries/$O5/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR5/approve" "$PROPERTY" >/dev/null
req POST /api/robots/2/status "$OPS" '{"battery":8}' >/dev/null
wait_event "$O5" LOW_BATTERY && ok "配送途中 LOW_BATTERY 事件" || bad "缺少 LOW_BATTERY"
req POST "/api/deliveries/$O5/manual-relay" "$OPS" '{"courierName":"骑手小王"}' >/dev/null
req POST "/api/deliveries/$O5/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O5/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O5/sign" "$OPS" '{"signBy":"赵女士"}' >/dev/null
check "人工接力后签收" "$(req GET "/api/orders/$O5" "$OPS" | jq -r .status)" "SIGNED"
wait_event "$O5" MANUAL_RELAY && ok "MANUAL_RELAY 事件" || bad "缺少 MANUAL_RELAY"

say "6. 异常：载物仓打不开 -> 客服介入 -> 改派楼栋(送错纠正) -> 签收"
O6=$(new_order 1 false GROCERY)
req POST "/api/deliveries/$O6/assign" "$OPS" '{"robotId":1}' >/dev/null
AR6=$(req POST "/api/deliveries/$O6/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR6/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O6/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/compartment-fault" "$OPS" >/dev/null
check "仓门故障后状态" "$(req GET "/api/orders/$O6" "$OPS" | jq -r .status)" "EXCEPTION"
wait_event "$O6" COMPARTMENT_OPEN_FAILED && ok "COMPARTMENT_OPEN_FAILED 事件" || bad "缺少 COMPARTMENT_OPEN_FAILED"
req POST "/api/deliveries/$O6/redeliver" "$CS" '{"buildingId":2}' >/dev/null
wait_event "$O6" ROUTE_ADJUSTED && ok "改派楼栋 ROUTE_ADJUSTED(WRONG_BUILDING) 事件" || bad "缺少改派事件"
req POST "/api/deliveries/$O6/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$O6/sign" "$OPS" '{"signBy":"周先生"}' >/dev/null
check "改派2栋后签收" "$(req GET "/api/orders/$O6" "$OPS" | jq -r '".building=" + (.building.name|tostring) + " " + .status')" ".building=2栋 SIGNED"

say "7. 异常：用户超时不取 -> 自动退回（等待巡检，PICKUP_TIMEOUT_SECONDS 需较短）"
O7=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O7/assign" "$OPS" '{"robotId":1}' >/dev/null
AR7=$(req POST "/api/deliveries/$O7/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR7/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O7/arrive-door" "$OPS" >/dev/null
if wait_status "$O7" RETURNING 60; then ok "超时未取自动进入退回"; else bad "未进入退回流程"; fi
wait_event "$O7" PICKUP_TIMEOUT && ok "PICKUP_TIMEOUT 事件" || bad "缺少 PICKUP_TIMEOUT"
req POST "/api/deliveries/$O7/confirm-return" "$OPS" >/dev/null
check "退回完成" "$(req GET "/api/orders/$O7" "$OPS" | jq -r .status)" "RETURNED"
wait_event "$O7" RETURNED && ok "RETURNED 事件" || bad "缺少 RETURNED"

say "8. 异常：冷藏订单超时（等待巡检，COLDCHAIN_TIMEOUT_SECONDS 需较短）"
O8=$(new_order 1 true MEDICINE)
if wait_event "$O8" COLDCHAIN_TIMEOUT 90; then ok "COLDCHAIN_TIMEOUT 事件"; else bad "缺少 COLDCHAIN_TIMEOUT"; fi
check "冷藏超时标记" "$(req GET "/api/orders/$O8" "$OPS" | jq -r .coldChainTimedOut)" "true"
req POST "/api/deliveries/$O8/cancel" "$OPS" '{"reason":"冷藏超时，订单取消"}' >/dev/null

say "9. 异常：电梯请求超时（等待巡检，ELEVATOR_TIMEOUT_SECONDS 需较短）"
O9=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O9/assign" "$OPS" '{"robotId":1}' >/dev/null
AR9=$(req POST "/api/deliveries/$O9/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR9/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O9/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":11}' >/dev/null
if wait_event "$O9" ELEVATOR_TIMEOUT 60; then ok "ELEVATOR_TIMEOUT 事件"; else bad "缺少 ELEVATOR_TIMEOUT"; fi
req POST "/api/deliveries/$O9/cancel" "$OPS" '{"reason":"电梯长时间不可用，取消"}' >/dev/null

say "10. 异常：物业夜间限制运行 -> 门禁自动拒绝"
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"00:00","nightEnd":"23:59"}' >/dev/null
O10=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O10/assign" "$OPS" '{"robotId":1}' >/dev/null
GATE10=$(req POST "/api/deliveries/$O10/arrive-gate" "$OPS" '{"gateName":"南门"}')
check "夜间门禁申请自动拒绝" "$(echo "$GATE10" | jq -r .status)" "DENIED"
wait_event "$O10" NIGHT_RESTRICTED && ok "NIGHT_RESTRICTED 事件" || bad "缺少 NIGHT_RESTRICTED"
wait_event "$O10" ACCESS_DENIED && ok "夜间 ACCESS_DENIED 事件" || bad "缺少夜间 ACCESS_DENIED"
req POST "/api/deliveries/$O10/cancel" "$OPS" '{"reason":"夜间限制，改期配送"}' >/dev/null
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":true,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null
ok "已恢复社区1夜间规则(22:00-07:00 限制)"

say "12. 冷链预警：温度持续计算->超时风险预警通知客服/用户->CRITICAL工单->退回换品->重新配送->门店备货"
req PUT /api/communities/1/night-rule "$PROPERTY" '{"nightRestricted":false,"nightStart":"22:00","nightEnd":"07:00"}' >/dev/null
O12=$(new_order 1 true MEDICINE)
req POST "/api/deliveries/$O12/assign" "$OPS" '{"robotId":1}' >/dev/null
AR12=$(req POST "/api/deliveries/$O12/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
# 门禁等待中持续计算：首次计算温度为基准温度
M0=$(req GET "/api/deliveries/$O12/coldchain/monitor" "$OPS")
check "冷链监控视图有温度与剩余时长" "$(echo "$M0" | jq '.lastTempCelsius != null and .remainingSeconds != null')" "true"
SNAP0=$(req GET "/api/deliveries/$O12/coldchain/snapshots" "$OPS" | jq 'length')
[ "$SNAP0" -ge 1 ] && ok "已产生温度快照($SNAP0条)" || bad "缺少温度快照"
# 设备实测温度 6.3℃（>=预警温度6℃）-> WARN，通知客服+用户
req POST "/api/deliveries/$O12/coldchain/temperature" "$OPS" '{"tempCelsius":6.3,"note":"仓温升高"}' >/dev/null
if wait_event "$O12" COLDCHAIN_WARNING 10; then ok "COLDCHAIN_WARNING 预警事件"; else bad "缺少 COLDCHAIN_WARNING"; fi
check "预警级别 WARN" "$(req GET "/api/deliveries/$O12/coldchain/monitor" "$OPS" | jq -r .warnLevel)" "WARN"
CSW=$(req GET "/api/notifications?deliveryId=$O12&channel=CS_CONSOLE" "$OPS" | jq 'length')
[ "$CSW" -ge 1 ] && ok "已提前通知客服(CS_CONSOLE $CSW条)" || bad "未通知客服"
UWARN=$(req GET "/api/notifications?deliveryId=$O12&channel=SMS" "$OPS" | jq 'length')
[ "$UWARN" -ge 1 ] && ok "已通知用户冷链预警(SMS)" || bad "未通知用户"
# 温度升至 8.6℃（>=严重温度8℃）-> CRITICAL 自动开客服工单
req POST "/api/deliveries/$O12/coldchain/temperature" "$OPS" '{"tempCelsius":8.6}' >/dev/null
if wait_event "$O12" COLDCHAIN_CRITICAL 10; then ok "COLDCHAIN_CRITICAL 严重风险事件"; else bad "缺少 COLDCHAIN_CRITICAL"; fi
RISK_CASE=$(req GET "/api/cs/cases?caseType=COLDCHAIN_RISK&status=OPEN" "$CS" | jq -r ".[] | select(.deliveryId==$O12) | .id")
[ -n "$RISK_CASE" ] && [ "$RISK_CASE" != "null" ] && ok "严重风险自动生成客服工单#$RISK_CASE" || bad "未生成 COLDCHAIN_RISK 工单"
# 退回站点更换商品（原配送证据保留）
RET=$(req POST "/api/deliveries/$O12/coldchain/return-for-swap" "$OPS" '{"storeName":"阳光大药房"}')
check "冷链退回换品后状态" "$(echo "$RET" | jq -r .status)" "RETURNING"
check "标记冷链换品退回" "$(echo "$RET" | jq -r .coldReturnForSwap)" "true"
wait_event "$O12" RETURN_FOR_SWAP && ok "RETURN_FOR_SWAP 事件(证据保留)" || bad "缺少 RETURN_FOR_SWAP"
RES=$(req POST "/api/deliveries/$O12/coldchain/confirm-swap-return" "$OPS")
check "确认退回后状态" "$(req GET "/api/orders/$O12" "$OPS" | jq -r .status)" "RETURNED"
check "生成重新配送/退款两个选项" "$(echo "$RES" | jq 'length')" "2"
wait_event "$O12" GOODS_SWAPPED && ok "GOODS_SWAPPED 商品已更换事件" || bad "缺少 GOODS_SWAPPED"
# 客服选择重新配送 -> 新配送单 + 门店备货通知
R_ID=$(req GET "/api/deliveries/$O12/coldchain/resolutions" "$OPS" | jq -r '.[] | select(.optionType=="REDELIVER") | .id')
DEC=$(req POST "/api/deliveries/$O12/coldchain/resolutions/$R_ID/decide" "$CS" '{"option":"REDELIVER","note":"用户同意重配"}')
check "重配选项已受理" "$(echo "$DEC" | jq -r .status)" "ACCEPTED"
NEW_ID=$(echo "$DEC" | jq -r .newDeliveryId)
[ "$NEW_ID" != "null" ] && [ -n "$NEW_ID" ] && ok "生成新配送单#$NEW_ID" || bad "未生成新配送单"
check "新单关联原退回单" "$(req GET "/api/orders/$NEW_ID" "$OPS" | jq -r .replacementOfId)" "$O12"
check "新单为冷链单并重新计时" "$(req GET "/api/orders/$NEW_ID" "$OPS" | jq -r .coldChain)" "true"
STORE_N=$(req GET "/api/notifications?deliveryId=$O12&channel=STORE" "$OPS" | jq 'length')
[ "$STORE_N" -ge 1 ] && ok "已通知门店重新备货(STORE $STORE_N条)" || bad "未通知门店备货"
wait_event "$O12" RESTOCK_NOTIFY && ok "RESTOCK_NOTIFY 门店备货事件" || bad "缺少 RESTOCK_NOTIFY"
# 新配送单重新走一遍配送直至签收
req POST "/api/deliveries/$NEW_ID/assign" "$OPS" '{"robotId":1}' >/dev/null
NAR=$(req POST "/api/deliveries/$NEW_ID/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$NAR/approve" "$PROPERTY" >/dev/null
NER=$(req POST "/api/deliveries/$NEW_ID/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":9}' | jq -r .id)
req POST "/api/elevator-requests/$NER/assign" "$OPS" '{"carId":"1栋-客梯A"}' >/dev/null
req POST "/api/elevator-requests/$NER/complete" "$OPS" >/dev/null
req POST "/api/deliveries/$NEW_ID/arrive-door" "$OPS" >/dev/null
req POST "/api/deliveries/$NEW_ID/open-compartment" "$OPS" >/dev/null
req POST "/api/deliveries/$NEW_ID/sign" "$OPS" '{"signBy":"张三"}' >/dev/null
check "重新配送单签收完成" "$(req GET "/api/orders/$NEW_ID" "$OPS" | jq -r .status)" "SIGNED"

say "13. 冷链超时损失归因：电梯等待致超时->损失关联门禁/电梯/路线证据->退款(门店无需备货)"
O13=$(new_order 1 true GROCERY)
req POST "/api/deliveries/$O13/assign" "$OPS" '{"robotId":1}' >/dev/null
AR13=$(req POST "/api/deliveries/$O13/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR13/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O13/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":10}' >/dev/null
if wait_event "$O13" ELEVATOR_TIMEOUT 90; then ok "电梯长时间未派梯 ELEVATOR_TIMEOUT"; else bad "缺少 ELEVATOR_TIMEOUT"; fi
if wait_event "$O13" COLDCHAIN_TIMEOUT 200; then ok "冷藏超时 COLDCHAIN_TIMEOUT(含归因)"; else bad "缺少 COLDCHAIN_TIMEOUT"; fi
TOUT=$(req GET "/api/deliveries/$O13/coldchain/monitor" "$OPS")
check "超时损失关联电梯等待秒数" "$(echo "$TOUT" | jq '.elevatorWaitSeconds >= 20')" "true"
# 客服核定损失金额并登记赔付证据
LOSS=$(req POST "/api/deliveries/$O13/coldchain/loss" "$CS" '{"lossAmount":88.50,"goodsStatus":"SPOILED","note":"生鲜失效，按售价赔付"}')
check "损失金额登记" "$(echo "$LOSS" | jq '.lossAmount == 88.50')" "true"
check "损失建议责任=电梯系统" "$(echo "$LOSS" | jq -r .suggestedResponsibility)" "ELEVATOR_SYSTEM"
[ -n "$(echo "$LOSS" | jq -r .evidenceRefs)" ] && [ "$(echo "$LOSS" | jq -r .evidenceRefs)" != "null" ] \
  && ok "原配送证据已保留(事件/快照引用)" || bad "缺少证据引用"
wait_event "$O13" COLDCHAIN_LOSS_ASSESSED && ok "COLDCHAIN_LOSS_ASSESSED 损失评估事件" || bad "缺少损失评估事件"
# 退回站点 -> 选择退款 -> 门店无需备货 + 用户退款通知
req POST "/api/deliveries/$O13/coldchain/return-for-swap" "$OPS" '{"storeName":"绿洲生鲜店"}' >/dev/null
req POST "/api/deliveries/$O13/coldchain/confirm-swap-return" "$OPS" >/dev/null
F_ID=$(req GET "/api/deliveries/$O13/coldchain/resolutions" "$OPS" | jq -r '.[] | select(.optionType=="REFUND") | .id')
FDEC=$(req POST "/api/deliveries/$O13/coldchain/resolutions/$F_ID/decide" "$CS" '{"option":"REFUND","note":"用户选择退款"}')
check "退款选项已受理" "$(echo "$FDEC" | jq -r .status)" "ACCEPTED"
check "退款无需门店备货" "$(echo "$FDEC" | jq -r .restockRequired)" "false"
REFUND_SMS=$(req GET "/api/notifications?deliveryId=$O13&channel=SMS" "$OPS" | jq 'length')
[ "$REFUND_SMS" -ge 1 ] && ok "退款结果已通知用户(SMS)" || bad "未通知用户退款"
wait_event "$O13" COLDCHAIN_REFUND_OPTION && ok "COLDCHAIN_REFUND_OPTION 退款选项事件" || bad "缺少退款选项事件"
req GET "/api/deliveries/$O13/events" "$OPS" | jq -e '.[] | select(.eventType=="RESTOCK_NOTIFY" and .reason=="REFUND")' >/dev/null \
  && ok "RESTOCK_NOTIFY(reason=REFUND) 通知门店无需备货" || bad "缺少退款门店通知事件"

say "11. 三端视图"
IMPACT=$(req GET "/api/analytics/property/passage-impact?communityId=1" "$PROPERTY")
check "物业端-避障停留次数>=2" "$(echo "$IMPACT" | jq '.obstacleStopCount >= 2')" "true"
check "物业端-施工绕行>=1" "$(echo "$IMPACT" | jq '.constructionDetours >= 1')" "true"
check "物业端-电梯满载>=1" "$(echo "$IMPACT" | jq '.elevatorFullCount >= 1')" "true"
check "物业端-夜间限制>=1" "$(echo "$IMPACT" | jq '.nightRestrictedCount >= 1')" "true"
check "物业端-投诉数>=1" "$(echo "$IMPACT" | jq '.complaintCount >= 1')" "true"
check "物业端-冷链严重预警>=1" "$(echo "$IMPACT" | jq '.coldChainCriticalCount >= 1')" "true"
check "物业端-冷链超时损失登记>=1" "$(echo "$IMPACT" | jq '.coldChainLossCount >= 1')" "true"
echo "  物业端冷链损失: $(echo "$IMPACT" | jq -c '{coldChainWarningCount,coldChainCriticalCount,coldChainLossCount,coldChainLossAmount,coldChainLossByAccessWait,coldChainLossByElevatorWait}')"
echo "  物业端通行影响: $(echo "$IMPACT" | jq -c '{impactLevel,affectsResidents,suggestion}')"
EFF=$(req GET "/api/analytics/ops/efficiency?communityId=1" "$OPS")
check "运营端-效率报表有数据" "$(echo "$EFF" | jq 'length > 0')" "true"
echo "  运营端效率(1栋FOOD): $(echo "$EFF" | jq -c '.[] | select(.buildingId==1 and .orderType=="FOOD") | {total,signed,returned,successRate}')"
EFF_MED=$(req GET "/api/analytics/ops/efficiency?communityId=1&orderType=MEDICINE" "$OPS")
check "运营端-按订单类型过滤(药品)" "$(echo "$EFF_MED" | jq 'all(.orderType=="MEDICINE")')" "true"
SUM=$(req GET "/api/cs/cases/summary" "$CS")
check "客服端-工单总数>=8" "$(echo "$SUM" | jq '.total >= 8')" "true"
echo "  客服端责任分布: $(echo "$SUM" | jq -c '.byResponsibility')"
EV_ALL=$(req GET "/api/events?communityId=1&eventType=ROUTE_ADJUSTED" "$OPS" | jq 'length')
check "统一事件流可按社区+类型检索" "$([ "$EV_ALL" -ge 2 ] && echo true || echo false)" "true"

say "结果"
if [ "$FAILS" -eq 0 ]; then
  echo "全部冒烟用例通过 ✅"
  exit 0
else
  echo "存在 $FAILS 个失败用例 ❌"
  exit 1
fi
