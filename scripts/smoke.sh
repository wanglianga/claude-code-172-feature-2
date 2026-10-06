#!/usr/bin/env bash
# 冒烟验证：主流程 + 全部异常场景 + 冷藏超时预警全链路（预警/退回/证据/重新配送/退款/门店备货）+ 三端视图
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
if wait_status "$O7" RETURNING 150; then ok "超时未取自动进入退回"; else bad "未进入退回流程"; fi
wait_event "$O7" PICKUP_TIMEOUT && ok "PICKUP_TIMEOUT 事件" || bad "缺少 PICKUP_TIMEOUT"
req POST "/api/deliveries/$O7/confirm-return" "$OPS" >/dev/null
check "退回完成" "$(req GET "/api/orders/$O7" "$OPS" | jq -r .status)" "RETURNED"
wait_event "$O7" RETURNED && ok "RETURNED 事件" || bad "缺少 RETURNED"

say "8. 异常：冷藏订单超时（等待巡检，COLDCHAIN_TIMEOUT_SECONDS 需较短）"
O8=$(new_order 1 true MEDICINE)
if wait_event "$O8" COLDCHAIN_TIMEOUT 200; then ok "COLDCHAIN_TIMEOUT 事件"; else bad "缺少 COLDCHAIN_TIMEOUT"; fi
check "冷藏超时标记" "$(req GET "/api/orders/$O8" "$OPS" | jq -r .coldChainTimedOut)" "true"
req POST "/api/deliveries/$O8/cancel" "$OPS" '{"reason":"冷藏超时，订单取消"}' >/dev/null

say "9. 异常：电梯请求超时（等待巡检，ELEVATOR_TIMEOUT_SECONDS 需较短）"
O9=$(new_order 1 false FOOD)
req POST "/api/deliveries/$O9/assign" "$OPS" '{"robotId":1}' >/dev/null
AR9=$(req POST "/api/deliveries/$O9/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR9/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O9/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":11}' >/dev/null
if wait_event "$O9" ELEVATOR_TIMEOUT 130; then ok "ELEVATOR_TIMEOUT 事件"; else bad "缺少 ELEVATOR_TIMEOUT"; fi
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

say "11. 冷藏超时预警：门禁等待致温升 -> 提前预警客服/用户 -> 严重风险退回站点 -> 证据封存 -> 重新配送(门店备货)"
O11=$(new_order 1 true MEDICINE)
req POST "/api/deliveries/$O11/assign" "$OPS" '{"robotId":1}' >/dev/null
req POST "/api/deliveries/$O11/arrive-gate" "$OPS" '{"gateName":"南门"}' >/dev/null  # 门禁不审批，持续等待
sleep 2
WARN1=$(req POST "/api/coldchain/orders/$O11/temperature" "$OPS" '{"temperatureC":8.5}')
check "上报8.5℃进入预警(WARNING)" "$(echo "$WARN1" | jq -r .riskLevel)" "WARNING"
check "预警主导原因为门禁等待" "$(echo "$WARN1" | jq -r .riskReason)" "ACCESS_WAIT"
check "预警归因门禁等待>0秒" "$(echo "$WARN1" | jq '.waits.accessWaitSeconds > 0')" "true"
wait_event "$O11" COLDCHAIN_WARNING && ok "COLDCHAIN_WARNING 预警事件" || bad "缺少 COLDCHAIN_WARNING"
req GET "/api/deliveries/$O11/events" "$OPS" | jq -e '.[] | select(.eventType=="COLDCHAIN_WARNING" and .reason=="ACCESS_WAIT")' >/dev/null \
  && ok "预警事件携带门禁等待原因" || bad "预警事件未关联门禁等待"
CS_NOTIFY=$(req GET "/api/notifications?deliveryId=$O11" "$OPS" | jq '[.[] | select(.channel=="CS")] | length')
check "预警已通知客服(CS渠道)" "$([ "$CS_NOTIFY" -ge 1 ] && echo true || echo false)" "true"
USER_NOTIFY=$(req GET "/api/notifications?deliveryId=$O11" "$OPS" | jq '[.[] | select(.channel=="SMS")] | length')
check "预警已短信通知用户" "$([ "$USER_NOTIFY" -ge 1 ] && echo true || echo false)" "true"
CRIT=$(req POST "/api/coldchain/orders/$O11/temperature" "$OPS" '{"temperatureC":11}')
check "上报11℃进入严重风险(CRITICAL)" "$(echo "$CRIT" | jq -r .riskLevel)" "CRITICAL"
if wait_status "$O11" RETURNED 30; then ok "严重风险自动退回站点(RETURNED)"; else bad "未自动退回站点"; fi
check "标记为冷藏风险退回" "$(req GET "/api/coldchain/orders/$O11/status" "$OPS" | jq -r .returnedForRisk)" "true"
check "原配送证据已封存" "$(req GET "/api/coldchain/orders/$O11/status" "$OPS" | jq -r .evidenceLocked)" "true"
wait_event "$O11" COLDCHAIN_RISK_RETURN && ok "COLDCHAIN_RISK_RETURN 退回事件" || bad "缺少 COLDCHAIN_RISK_RETURN"
wait_event "$O11" COLDCHAIN_EVIDENCE_LOCKED && ok "COLDCHAIN_EVIDENCE_LOCKED 证据封存事件" || bad "缺少证据封存事件"
wait_event "$O11" COLDCHAIN_OPTION_CREATED && ok "COLDCHAIN_OPTION_CREATED 选项事件" || bad "缺少选项生成事件"
STORE_NOTIFY=$(req GET "/api/notifications?deliveryId=$O11" "$OPS" | jq '[.[] | select(.channel=="STORE")] | length')
check "退回已通知门店" "$([ "$STORE_NOTIFY" -ge 1 ] && echo true || echo false)" "true"
RES=$(req GET "/api/coldchain/orders/$O11/resolutions" "$OPS")
check "生成两个待处理选项" "$(echo "$RES" | jq 'length')" "2"
check "含重新配送选项" "$(echo "$RES" | jq -r '.[] | select(.option=="REDELIVER") | .status')" "PENDING"
check "含退款选项" "$(echo "$RES" | jq -r '.[] | select(.option=="REFUND") | .status')" "PENDING"
check "重新配送需门店备货" "$(echo "$RES" | jq -r '.[] | select(.option=="REDELIVER") | .restockRequired')" "true"
EV11=$(req GET "/api/coldchain/orders/$O11/evidence" "$OPS")
check "赔付证据-门禁等待归因>0" "$(echo "$EV11" | jq '.waitAttribution.accessWaitSeconds > 0')" "true"
check "赔付证据-温度曲线非空" "$(echo "$EV11" | jq '.temperatureCurve | length > 0')" "true"
check "赔付证据-时间线含退回" "$(echo "$EV11" | jq '[.eventTimeline[].eventType] | index("COLDCHAIN_RISK_RETURN") != null')" "true"
check "赔付证据-时间线含门禁申请" "$(echo "$EV11" | jq '[.eventTimeline[].eventType] | index("ACCESS_REQUESTED") != null')" "true"
REDELIVER=$(req POST "/api/coldchain/orders/$O11/resolve" "$CS" '{"option":"REDELIVER","note":"用户同意重新配送"}')
check "选择重新配送后选项状态" "$(echo "$REDELIVER" | jq -r .status)" "REDELIVERING"
NEW_NO=$(echo "$REDELIVER" | jq -r .newOrderNo)
[ -n "$NEW_NO" ] && [ "$NEW_NO" != "null" ] && ok "已生成重新配送新单 $NEW_NO" || bad "未生成新配送单"
check "新单为冷藏单" "$(req GET "/api/orders" "$OPS" | jq -r ".[] | select(.orderNo==\"$NEW_NO\") | .coldChain")" "true"
wait_event "$O11" COLDCHAIN_REDELIVERY_CREATED && ok "COLDCHAIN_REDELIVERY_CREATED 事件" || bad "缺少重新配送新单事件"
wait_event "$O11" COLDCHAIN_STOCK_REPREP && ok "COLDCHAIN_STOCK_REPREP 门店备货事件" || bad "缺少门店备货事件"
check "另一选项(退款)已作废" "$(req GET "/api/coldchain/orders/$O11/resolutions" "$OPS" | jq -r '.[] | select(.option=="REFUND") | .status')" "CANCELLED"

say "12. 冷藏超时预警：电梯等待 -> 人工退回 -> 选择退款 -> 通知用户"
O12=$(new_order 1 true GROCERY)
req POST "/api/deliveries/$O12/assign" "$OPS" '{"robotId":1}' >/dev/null
AR12=$(req POST "/api/deliveries/$O12/arrive-gate" "$OPS" '{"gateName":"南门"}' | jq -r .id)
req POST "/api/access-requests/$AR12/approve" "$PROPERTY" >/dev/null
req POST "/api/deliveries/$O12/request-elevator" "$OPS" '{"fromFloor":1,"toFloor":8}' >/dev/null  # 电梯不派梯
sleep 2
req POST "/api/coldchain/orders/$O12/risk-return" "$CS" '{"reason":"电梯持续等待，商品可能失温"}' >/dev/null
if wait_status "$O12" RETURNED 20; then ok "人工退回后状态RETURNED"; else bad "人工退回失败"; fi
wait_event "$O12" COLDCHAIN_EVIDENCE_LOCKED && ok "O12证据封存" || bad "O12缺少证据封存"
EV12=$(req GET "/api/coldchain/orders/$O12/evidence" "$OPS")
check "赔付证据-电梯等待归因>0" "$(echo "$EV12" | jq '.waitAttribution.elevatorWaitSeconds > 0')" "true"
REFUND=$(req POST "/api/coldchain/orders/$O12/resolve" "$CS" '{"option":"REFUND","note":"全额退款"}')
check "选择退款后选项状态" "$(echo "$REFUND" | jq -r .status)" "REFUNDED"
wait_event "$O12" COLDCHAIN_REFUNDED && ok "COLDCHAIN_REFUNDED 退款事件" || bad "缺少退款事件"
req GET "/api/notifications?deliveryId=$O12" "$OPS" | jq -e '[.[] | select(.channel=="SMS" and (.content|test("退款")))] | length > 0' >/dev/null \
  && ok "退款已短信通知用户" || bad "退款未通知用户"

say "13. 冷藏超时预警：在途风险预警列表与损失归因"
O13=$(new_order 1 true MEDICINE)
req POST "/api/deliveries/$O13/assign" "$OPS" '{"robotId":1}' >/dev/null
req POST "/api/coldchain/orders/$O13/temperature" "$OPS" '{"temperatureC":8.5}' >/dev/null
ALERTS=$(req GET "/api/coldchain/alerts" "$CS")
echo "$ALERTS" | jq -e ".[] | select(.orderId==$O13 and .riskLevel==\"WARNING\")" >/dev/null \
  && ok "预警列表含在途WARNING订单" || bad "预警列表缺少在途WARNING订单"
LOSSES=$(req GET "/api/coldchain/losses?communityId=1" "$CS")
echo "$LOSSES" | jq -e ".[] | select(.orderId==$O11 and .returnedForRisk==true)" >/dev/null \
  && ok "损失列表关联O11门禁等待" || bad "损失列表缺少O11"
echo "$LOSSES" | jq -e ".[] | select(.orderId==$O12 and .waitAttribution.elevatorWaitSeconds > 0)" >/dev/null \
  && ok "损失列表关联O12电梯等待与机器人路线" || bad "损失列表缺少O12电梯归因"
echo "  冷藏损失数: $(echo "$LOSSES" | jq 'length') 笔"

say "14. 三端视图"
IMPACT=$(req GET "/api/analytics/property/passage-impact?communityId=1" "$PROPERTY")
check "物业端-避障停留次数>=2" "$(echo "$IMPACT" | jq '.obstacleStopCount >= 2')" "true"
check "物业端-施工绕行>=1" "$(echo "$IMPACT" | jq '.constructionDetours >= 1')" "true"
check "物业端-电梯满载>=1" "$(echo "$IMPACT" | jq '.elevatorFullCount >= 1')" "true"
check "物业端-夜间限制>=1" "$(echo "$IMPACT" | jq '.nightRestrictedCount >= 1')" "true"
check "物业端-投诉数>=1" "$(echo "$IMPACT" | jq '.complaintCount >= 1')" "true"
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
