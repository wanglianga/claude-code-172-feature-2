package com.example.robotdelivery.coldchain;

public enum ResolutionStatus {
    PENDING,       // 已生成，待客服/用户选择
    REDELIVERING,  // 已选择重新配送，等待重新备货与再投
    REFUNDED,      // 已退款
    CANCELLED      // 选项作废（如选择了另一选项）
}
