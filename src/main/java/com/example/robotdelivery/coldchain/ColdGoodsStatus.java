package com.example.robotdelivery.coldchain;

/** 冷链超时导致的商品状况 */
public enum ColdGoodsStatus {
    SPOILED,   // 已变质/失效，需赔付
    AT_RISK,   // 温度越界，存在变质风险
    REPLACED,  // 已退回站点更换商品
    REFUNDED   // 已退款
}
