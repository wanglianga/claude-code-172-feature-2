package com.example.robotdelivery.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 冷藏订单超时预警全栈集成测试：内嵌 Kafka（KRaft）+ 真实 PostgreSQL（localhost:5433/robot_delivery_it），
 * 通过真实 HTTP 接口验证：温度/剩余时长计算、门禁/电梯等待归因、两级预警通知、自动退回、
 * 证据封存、重新配送/退款选项、门店备货与损失列表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 3, topics = "delivery-events")
class ColdChainAlertIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper om;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/robot_delivery_it");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("delivery.scheduler-enabled", () -> "false"); // 手动驱动，保证确定性
        r.add("delivery.coldchain-timeout-seconds", () -> "999999");
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String login(String user, String pwd) {
        ResponseEntity<String> resp = rest.exchange(base() + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", user, "password", pwd), jsonHeaders()), String.class);
        return body(resp).path("token").asText();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private JsonNode body(ResponseEntity<String> resp) {
        try {
            assertTrue(resp.getStatusCode().is2xxSuccessful(),
                    "请求失败: " + resp.getStatusCode() + " " + resp.getBody());
            return om.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode post(String path, String token, Object data) {
        try {
            return body(rest.exchange(base() + path, HttpMethod.POST,
                    new HttpEntity<>(om.writeValueAsString(data), auth(token)), String.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode put(String path, String token, Object data) {
        try {
            return body(rest.exchange(base() + path, HttpMethod.PUT,
                    new HttpEntity<>(om.writeValueAsString(data), auth(token)), String.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode postNoBody(String path, String token) {
        return body(rest.exchange(base() + path, HttpMethod.POST,
                new HttpEntity<>(auth(token)), String.class));
    }

    private JsonNode get(String path, String token) {
        return body(rest.exchange(base() + path, HttpMethod.GET,
                new HttpEntity<>(auth(token)), String.class));
    }

    private long createColdOrder(String ops, String orderType) {
        JsonNode o = post("/api/orders", ops, Map.of(
                "orderType", orderType, "communityId", 1, "buildingId", 1,
                "unitNo", "2单元", "roomNo", "901", "coldChain", true,
                "estimatedDeliveryAt", "2026-12-31T23:59:00",
                "userName", "张三", "userPhone", "13800000001"));
        return o.path("id").asLong();
    }

    private JsonNode waitEvent(long orderId, String type, String token) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            JsonNode events = get("/api/deliveries/" + orderId + "/events", token);
            for (JsonNode e : events) {
                if (type.equals(e.path("eventType").asText())) {
                    return e;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("等待事件超时: " + type);
    }

    private void waitStatus(long orderId, String status, String token) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            if (status.equals(get("/api/orders/" + orderId, token).path("status").asText())) {
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("等待状态超时: " + status);
    }

    @Test
    void coldChainRiskAlertReturnEvidenceAndResolution() throws Exception {
        String ops = login("ops", "ops123");
        String cs = login("cs", "cs123");
        String property = login("property", "property123");

        // 关闭夜间限制，避免门禁受影响
        put("/api/communities/1/night-rule", property,
                Map.of("nightRestricted", false, "nightStart", "22:00", "nightEnd", "07:00"));

        // 场景一：门禁持续等待 -> 温升预警 -> 严重风险自动退回 -> 重新配送（门店备货）
        long o1 = createColdOrder(ops, "MEDICINE");
        post("/api/deliveries/" + o1 + "/assign", ops, Map.of("robotId", 1));
        post("/api/deliveries/" + o1 + "/arrive-gate", ops, Map.of("gateName", "南门")); // 不审批
        Thread.sleep(2000);

        JsonNode warn = post("/api/coldchain/orders/" + o1 + "/temperature", ops, Map.of("temperatureC", 8.5));
        assertEquals("WARNING", warn.path("riskLevel").asText());
        assertEquals("ACCESS_WAIT", warn.path("riskReason").asText());
        assertTrue(warn.path("waits").path("accessWaitSeconds").asLong() > 0, "门禁等待应>0");

        JsonNode warnEvent = waitEvent(o1, "COLDCHAIN_WARNING", ops);
        assertEquals("ACCESS_WAIT", warnEvent.path("reason").asText());

        JsonNode notifications = get("/api/notifications?deliveryId=" + o1, ops);
        boolean csNotified = false, smsNotified = false;
        for (JsonNode n : notifications) {
            if ("CS".equals(n.path("channel").asText())) {
                csNotified = true;
            }
            if ("SMS".equals(n.path("channel").asText())) {
                smsNotified = true;
            }
        }
        assertTrue(csNotified, "预警应通知客服");
        assertTrue(smsNotified, "预警应短信通知用户");

        JsonNode critical = post("/api/coldchain/orders/" + o1 + "/temperature", ops, Map.of("temperatureC", 11));
        assertEquals("CRITICAL", critical.path("riskLevel").asText());
        waitStatus(o1, "RETURNED", ops);
        waitEvent(o1, "COLDCHAIN_RISK_RETURN", ops);
        waitEvent(o1, "COLDCHAIN_EVIDENCE_LOCKED", ops);
        waitEvent(o1, "COLDCHAIN_OPTION_CREATED", ops);

        JsonNode st = get("/api/coldchain/orders/" + o1 + "/status", ops);
        assertTrue(st.path("returnedForRisk").asBoolean());
        assertTrue(st.path("evidenceLocked").asBoolean());

        JsonNode evidence = get("/api/coldchain/orders/" + o1 + "/evidence", ops);
        assertTrue(evidence.path("waitAttribution").path("accessWaitSeconds").asLong() > 0);
        assertTrue(evidence.path("temperatureCurve").size() > 0);
        boolean timelineReturn = false, timelineAccess = false;
        for (JsonNode e : evidence.path("eventTimeline")) {
            if ("COLDCHAIN_RISK_RETURN".equals(e.path("eventType").asText())) {
                timelineReturn = true;
            }
            if ("ACCESS_REQUESTED".equals(e.path("eventType").asText())) {
                timelineAccess = true;
            }
        }
        assertTrue(timelineReturn, "证据时间线应含退回事件");
        assertTrue(timelineAccess, "证据时间线应含门禁申请");

        JsonNode resolutions = get("/api/coldchain/orders/" + o1 + "/resolutions", ops);
        assertEquals(2, resolutions.size());
        boolean redeliverPending = false, refundPending = false, restockFlag = false;
        for (JsonNode r : resolutions) {
            if ("REDELIVER".equals(r.path("option").asText())) {
                redeliverPending = "PENDING".equals(r.path("status").asText());
                restockFlag = r.path("restockRequired").asBoolean();
            }
            if ("REFUND".equals(r.path("option").asText())) {
                refundPending = "PENDING".equals(r.path("status").asText());
            }
        }
        assertTrue(redeliverPending && refundPending && restockFlag, "应生成重新配送/退款待处理选项且重新配送需备货");

        notifications = get("/api/notifications?deliveryId=" + o1, ops);
        boolean storeNotified = false;
        for (JsonNode n : notifications) {
            if ("STORE".equals(n.path("channel").asText())) {
                storeNotified = true;
            }
        }
        assertTrue(storeNotified, "退回应通知门店");

        JsonNode redeliver = post("/api/coldchain/orders/" + o1 + "/resolve", cs,
                Map.of("option", "REDELIVER", "note", "用户同意重新配送"));
        assertEquals("REDELIVERING", redeliver.path("status").asText());
        assertFalse(redeliver.path("newOrderNo").asText().isBlank(), "应生成新配送单号");
        String newOrderNo = redeliver.path("newOrderNo").asText();
        waitEvent(o1, "COLDCHAIN_REDELIVERY_CREATED", ops);
        waitEvent(o1, "COLDCHAIN_STOCK_REPREP", ops);

        JsonNode newOrder = get("/api/orders/" + redeliver.path("newOrderId").asLong(), ops);
        assertTrue(newOrder.path("coldChain").asBoolean(), "新单应为冷藏单");
        assertEquals(newOrderNo, newOrder.path("orderNo").asText());

        resolutions = get("/api/coldchain/orders/" + o1 + "/resolutions", ops);
        for (JsonNode r : resolutions) {
            if ("REFUND".equals(r.path("option").asText())) {
                assertEquals("CANCELLED", r.path("status").asText(), "另一选项应作废");
            }
        }

        // 场景二：电梯等待 -> 人工退回 -> 退款
        long o2 = createColdOrder(ops, "GROCERY");
        post("/api/deliveries/" + o2 + "/assign", ops, Map.of("robotId", 1));
        long ar = post("/api/deliveries/" + o2 + "/arrive-gate", ops, Map.of("gateName", "南门")).path("id").asLong();
        postNoBody("/api/access-requests/" + ar + "/approve", property);
        post("/api/deliveries/" + o2 + "/request-elevator", ops, Map.of("fromFloor", 1, "toFloor", 8)); // 不派梯
        Thread.sleep(2000);
        post("/api/coldchain/orders/" + o2 + "/risk-return", cs, Map.of("reason", "电梯持续等待，商品可能失温"));
        waitStatus(o2, "RETURNED", ops);
        waitEvent(o2, "COLDCHAIN_EVIDENCE_LOCKED", ops);
        JsonNode ev2 = get("/api/coldchain/orders/" + o2 + "/evidence", ops);
        assertTrue(ev2.path("waitAttribution").path("elevatorWaitSeconds").asLong() > 0, "电梯等待应>0");

        JsonNode refund = post("/api/coldchain/orders/" + o2 + "/resolve", cs,
                Map.of("option", "REFUND", "note", "全额退款"));
        assertEquals("REFUNDED", refund.path("status").asText());
        waitEvent(o2, "COLDCHAIN_REFUNDED", ops);
        JsonNode n2 = get("/api/notifications?deliveryId=" + o2, ops);
        boolean refundSms = false;
        for (JsonNode n : n2) {
            if ("SMS".equals(n.path("channel").asText()) && n.path("content").asText().contains("退款")) {
                refundSms = true;
            }
        }
        assertTrue(refundSms, "退款应短信通知用户");

        // 场景三：预警列表与损失归因
        long o3 = createColdOrder(ops, "MEDICINE");
        post("/api/deliveries/" + o3 + "/assign", ops, Map.of("robotId", 1));
        post("/api/coldchain/orders/" + o3 + "/temperature", ops, Map.of("temperatureC", 8.5));
        JsonNode alerts = get("/api/coldchain/alerts", cs);
        boolean alertFound = false;
        for (JsonNode a : alerts) {
            if (a.path("orderId").asLong() == o3 && "WARNING".equals(a.path("riskLevel").asText())) {
                alertFound = true;
            }
        }
        assertTrue(alertFound, "预警列表应含在途 WARNING 订单");

        JsonNode losses = get("/api/coldchain/losses?communityId=1", cs);
        boolean l1 = false, l2 = false;
        for (JsonNode l : losses) {
            if (l.path("orderId").asLong() == o1 && l.path("returnedForRisk").asBoolean()
                    && l.path("waitAttribution").path("accessWaitSeconds").asLong() > 0) {
                l1 = true;
            }
            if (l.path("orderId").asLong() == o2
                    && l.path("waitAttribution").path("elevatorWaitSeconds").asLong() > 0) {
                l2 = true;
            }
        }
        assertTrue(l1, "损失列表应关联门禁等待");
        assertTrue(l2, "损失列表应关联电梯等待与机器人路线");

        // 收尾：取消仍在途中的 o3 以释放机器人，保证测试可重复执行
        post("/api/deliveries/" + o3 + "/cancel", ops, Map.of("reason", "测试收尾，释放机器人"));
    }
}
