package com.example.robotdelivery.coldchain;

/** 冷链风险退回站点后的处置选项 */
public enum ColdResolutionOption {
    REDELIVER, // 重新配送（门店重新备货）
    REFUND     // 退款（无需备货）
}
