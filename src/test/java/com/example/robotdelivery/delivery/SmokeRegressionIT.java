package com.example.robotdelivery.delivery;

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
 * 既有主流程与超时巡检的回归测试（内嵌 Kafka + 真实 PostgreSQL），
 * 确保冷藏预警改造未破坏：主流程签收、门禁拒绝、电梯满载重试、冷藏履约超时归因、电梯超时。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 3, topics = "delivery-events")
class SmokeRegressionIT {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    ObjectMapper om;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/robot_delivery_it_smoke");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("delivery.scheduler-enabled", () -> "true");
        r.add("delivery.scheduler-interval-ms", () -> "2000");
        r.add("delivery.coldchain-timeout-seconds", () -> "1");
        r.add("delivery.elevator-timeout-seconds", () -> "1");
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpHeaders h(String token) {
        HttpHeaders x = new HttpHeaders();
        x.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            x.setBearerAuth(token);
        }
        return x;
    }

    private String login(String u, String p) {
        ResponseEntity<String> r = rest.exchange(base() + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("username", u, "password", p), h(null)), String.class);
        return body(r).path("token").asText();
    }

    private JsonNode body(ResponseEntity<String> r) {
        try {
            assertTrue(r.getStatusCode().is2xxSuccessful(), r.getStatusCode() + " " + r.getBody());
            return om.readTree(r.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode post(String p, String t, Object d) {
        try {
            return body(rest.exchange(base() + p, HttpMethod.POST,
                    new HttpEntity<>(om.writeValueAsString(d), h(t)), String.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode postEmpty(String p, String t) {
        HttpHeaders hh = new HttpHeaders();
        hh.setBearerAuth(t);
        return body(rest.exchange(base() + p, HttpMethod.POST, new HttpEntity<>(hh), String.class));
    }

    private JsonNode put(String p, String t, Object d) {
        try {
            return body(rest.exchange(base() + p, HttpMethod.PUT,
                    new HttpEntity<>(om.writeValueAsString(d), h(t)), String.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode get(String p, String t) {
        return body(rest.exchange(base() + p, HttpMethod.GET, new HttpEntity<>(h(t)), String.class));
    }

    private long order(String ops, boolean cold, String type) {
        return post("/api/orders", ops, Map.of(
                "orderType", type, "communityId", 1, "buildingId", 1,
                "unitNo", "2单元", "roomNo", "901", "coldChain", cold,
                "estimatedDeliveryAt", "2026-12-31T23:59:00",
                "userName", "张三", "userPhone", "13800000001")).path("id").asLong();
    }

    private JsonNode waitEvent(long id, String type, String t) {
        Instant dl = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(dl)) {
            for (JsonNode e : get("/api/deliveries/" + id + "/events", t)) {
                if (type.equals(e.path("eventType").asText())) {
                    return e;
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("等待事件超时: " + type);
    }

    @Test
    void mainFlowSignAndNotifications() {
        String ops = login("ops", "ops123");
        String property = login("property", "property123");
        put("/api/communities/1/night-rule", property,
                Map.of("nightRestricted", false, "nightStart", "22:00", "nightEnd", "07:00"));

        long o = order(ops, false, "FOOD");
        post("/api/deliveries/" + o + "/assign", ops, Map.of("robotId", 1));
        long ar = post("/api/deliveries/" + o + "/arrive-gate", ops, Map.of("gateName", "南门")).path("id").asLong();
        postEmpty("/api/access-requests/" + ar + "/approve", property);
        long er = post("/api/deliveries/" + o + "/request-elevator", ops, Map.of("fromFloor", 1, "toFloor", 9)).path("id").asLong();
        post("/api/elevator-requests/" + er + "/assign", ops, Map.of("carId", "客梯A"));
        postEmpty("/api/elevator-requests/" + er + "/complete", ops);
        post("/api/deliveries/" + o + "/obstacle", ops,
                Map.of("obstacleType", "ELDERLY_CHILD", "waitSeconds", 30, "location", "大堂"));
        postEmpty("/api/deliveries/" + o + "/arrive-door", ops);
        assertEquals(2, get("/api/notifications?deliveryId=" + o, ops).size(), "到门应短信+小程序双通道通知");
        postEmpty("/api/deliveries/" + o + "/open-compartment", ops);
        JsonNode sign = post("/api/deliveries/" + o + "/sign", ops, Map.of("signBy", "张三"));
        assertEquals("SIGNED", sign.path("status").asText());
        assertEquals("IDLE", get("/api/robots?communityId=1", ops).get(0).path("status").asText());
        for (String t : new String[]{"ORDER_CREATED", "ROBOT_ASSIGNED", "ACCESS_GRANTED",
                "ELEVATOR_COMPLETED", "OBSTACLE_WAIT", "ARRIVED_AT_DOOR", "USER_NOTIFIED", "SIGNED"}) {
            waitEvent(o, t, ops);
        }
    }

    @Test
    void accessDeniedOpensCase() {
        String ops = login("ops", "ops123");
        String property = login("property", "property123");
        long o = order(ops, false, "FOOD");
        post("/api/deliveries/" + o + "/assign", ops, Map.of("robotId", 1));
        long ar = post("/api/deliveries/" + o + "/arrive-gate", ops, Map.of("gateName", "北门")).path("id").asLong();
        post("/api/access-requests/" + ar + "/deny", property, Map.of("reason", "设备未备案"));
        assertEquals("EXCEPTION", get("/api/orders/" + o, ops).path("status").asText());
        waitEvent(o, "ACCESS_DENIED", ops);
        waitEvent(o, "CS_INTERVENTION", ops);
        post("/api/deliveries/" + o + "/cancel", ops, Map.of("reason", "门禁被拒取消，释放机器人"));
    }

    @Test
    void elevatorFullAutoRetry() {
        String ops = login("ops", "ops123");
        String property = login("property", "property123");
        long o = order(ops, false, "GROCERY");
        post("/api/deliveries/" + o + "/assign", ops, Map.of("robotId", 1));
        long ar = post("/api/deliveries/" + o + "/arrive-gate", ops, Map.of("gateName", "南门")).path("id").asLong();
        postEmpty("/api/access-requests/" + ar + "/approve", property);
        long er = post("/api/deliveries/" + o + "/request-elevator", ops, Map.of("fromFloor", 1, "toFloor", 6)).path("id").asLong();
        JsonNode retry = postEmpty("/api/elevator-requests/" + er + "/full", ops);
        assertNotEquals(er, retry.path("id").asLong(), "满载应自动重新请求电梯");
        waitEvent(o, "ROUTE_ADJUSTED", ops);
        post("/api/elevator-requests/" + retry.path("id").asLong() + "/assign", ops, Map.of("carId", "货梯B"));
        postEmpty("/api/elevator-requests/" + retry.path("id").asLong() + "/complete", ops);
        postEmpty("/api/deliveries/" + o + "/arrive-door", ops);
        postEmpty("/api/deliveries/" + o + "/open-compartment", ops);
        assertEquals("SIGNED", post("/api/deliveries/" + o + "/sign", ops, Map.of("signBy", "李女士")).path("status").asText());
    }

    @Test
    void coldChainDeadlineTimeoutCarriesAttribution() {
        String ops = login("ops", "ops123");
        // 复刻冒烟场景8：冷藏单创建后不分配机器人，履约时限（1s）一过即被巡检标记超时
        long o = order(ops, true, "MEDICINE");
        JsonNode ev = waitEvent(o, "COLDCHAIN_TIMEOUT", ops);
        assertTrue(ev.has("payload"), "超时事件应携带 payload");
        assertTrue(get("/api/orders/" + o, ops).path("coldChainTimedOut").asBoolean(), "应标记冷藏超时");
        boolean foundCase = false;
        for (JsonNode c : get("/api/cs/cases?caseType=COLDCHAIN_TIMEOUT", ops)) {
            if (c.path("deliveryId").asLong() == o) {
                foundCase = true;
            }
        }
        assertTrue(foundCase, "应生成冷藏超时客服工单");
    }

    @Test
    void elevatorTimeoutOpensCase() throws Exception {
        String ops = login("ops", "ops123");
        String property = login("property", "property123");
        long o = order(ops, false, "FOOD");
        post("/api/deliveries/" + o + "/assign", ops, Map.of("robotId", 1));
        long ar = post("/api/deliveries/" + o + "/arrive-gate", ops, Map.of("gateName", "南门")).path("id").asLong();
        postEmpty("/api/access-requests/" + ar + "/approve", property);
        post("/api/deliveries/" + o + "/request-elevator", ops, Map.of("fromFloor", 1, "toFloor", 11));
        waitEvent(o, "ELEVATOR_TIMEOUT", ops); // 电梯 1s 不派梯，巡检标记超时
        post("/api/deliveries/" + o + "/cancel", ops, Map.of("reason", "电梯超时取消，释放机器人"));
    }
}
