package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.auth.CurrentUserHolder;
import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 冷藏订单超时预警接口：载物仓温度/剩余可配送时长监控、超时风险预警通知、
 * 退回站点更换商品、赔付证据封存、退回后重新配送/退款选项与门店备货通知、损失归因列表。
 */
@Tag(name = "冷藏超时预警", description = "药品/生鲜冷藏单的温度与剩余时长持续监控、超时风险预警、退回换品、赔付证据与重新配送/退款")
@RestController
@RequestMapping("/api/coldchain")
public class ColdChainController {

    private final ColdChainMonitorService monitorService;

    public ColdChainController(ColdChainMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Operation(summary = "冷藏单实时监控状态",
            description = "当前载物仓温度、剩余可配送时长、风险等级、门禁/电梯/避障/路线等待归因与处置选项")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN, Role.PROPERTY})
    @GetMapping("/orders/{id}/status")
    public Map<String, Object> status(@PathVariable Long id) {
        return monitorService.status(id);
    }

    public record TemperatureRequest(@NotNull Double temperatureC) {
    }

    @Operation(summary = "设备上报载物仓温度并立即重新计算风险",
            description = "持续计算以服务端模型为准，并取模型温度与设备上报温度的较高者")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/orders/{id}/temperature")
    public Map<String, Object> reportTemperature(@PathVariable Long id,
                                                 @Valid @RequestBody TemperatureRequest request) {
        return monitorService.reportTemperature(id, request.temperatureC());
    }

    @Operation(summary = "载物仓温度与剩余可配送时长采样曲线")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN, Role.PROPERTY})
    @GetMapping("/orders/{id}/snapshots")
    public List<ColdChainSnapshot> snapshots(@PathVariable Long id) {
        return monitorService.snapshots(id);
    }

    public record RiskReturnRequest(String reason) {
    }

    @Operation(summary = "人工判定冷藏超时风险并退回站点更换商品",
            description = "封存原配送证据并生成重新配送/退款选项（自动退回由巡检在 CRITICAL 时触发）")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @PostMapping("/orders/{id}/risk-return")
    public Map<String, Object> riskReturn(@PathVariable Long id,
                                          @RequestBody(required = false) RiskReturnRequest request) {
        return monitorService.manualRiskReturn(id, request == null ? null : request.reason());
    }

    @Operation(summary = "原配送赔付证据",
            description = "封存的事件时间线、温度曲线与门禁/电梯/避障/路线等待归因，供赔付判断")
    @RequireRole({Role.CS, Role.OPS, Role.ADMIN})
    @GetMapping("/orders/{id}/evidence")
    public Map<String, Object> evidence(@PathVariable Long id) {
        return monitorService.evidence(id);
    }

    @Operation(summary = "退回站点后生成的重新配送/退款选项")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @GetMapping("/orders/{id}/resolutions")
    public List<Map<String, Object>> resolutions(@PathVariable Long id) {
        return monitorService.resolutions(id);
    }

    public record ResolveRequest(@NotNull ResolutionOption option, String note) {
    }

    @Operation(summary = "选择重新配送或退款",
            description = "重新配送：生成新配送单并通知门店重新备货；退款：通知用户并封存证据")
    @RequireRole({Role.CS, Role.OPS, Role.ADMIN})
    @PostMapping("/orders/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable Long id, @Valid @RequestBody ResolveRequest request) {
        return monitorService.decideByOption(id, request.option(),
                CurrentUserHolder.get().username(), request.note());
    }

    @Operation(summary = "超时风险预警中的冷藏单列表")
    @RequireRole({Role.CS, Role.OPS, Role.ADMIN})
    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        return monitorService.alerts();
    }

    @Operation(summary = "冷藏超时损失列表",
            description = "损失关联门禁等待、电梯等待与机器人路线（避障/绕行），用于赔付判责与运营复盘")
    @RequireRole({Role.CS, Role.OPS, Role.ADMIN})
    @GetMapping("/losses")
    public List<Map<String, Object>> losses(@RequestParam(required = false) Long communityId) {
        return monitorService.losses(communityId);
    }
}
