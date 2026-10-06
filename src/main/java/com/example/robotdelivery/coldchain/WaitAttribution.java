package com.example.robotdelivery.coldchain;

/**
 * 冷藏配送等待归因：把配送途中已经发生的等待拆分为
 * 门禁等待 / 电梯等待 / 避障停留 / 路线调整损失，用于超时风险预警与赔付判责。
 */
public record WaitAttribution(long accessWaitSeconds,
                              long elevatorWaitSeconds,
                              long obstacleWaitSeconds,
                              long routeWaitSeconds,
                              long totalWaitSeconds,
                              String dominantReason) {

    /** 主导等待原因：ACCESS_WAIT / ELEVATOR_WAIT / OBSTACLE_WAIT / ROUTE_DETOUR / NONE */
    public static String dominant(long access, long elevator, long obstacle, long route) {
        long best = Math.max(Math.max(access, elevator), Math.max(obstacle, route));
        if (best <= 0) {
            return "NONE";
        }
        if (best == access) {
            return "ACCESS_WAIT";
        }
        if (best == elevator) {
            return "ELEVATOR_WAIT";
        }
        if (best == obstacle) {
            return "OBSTACLE_WAIT";
        }
        return "ROUTE_DETOUR";
    }
}
