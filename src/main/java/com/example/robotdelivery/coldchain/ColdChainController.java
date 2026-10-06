package com.example.robotdelivery.coldchain;

import com.example.robotdelivery.auth.CurrentUserHolder;
import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 冷链超时预警接口：持续温度监控、风险退回换品、重新配送/退款选项、门店备货与损失归因。
 */
@Tag(name = "冷链温控预警",
        description = "持续计算载物仓温度与剩余可配送时长，超时风险提前通知客服与用户；"
                + "必要时退回站点更换商品，生成重新配送/退款选项并通知门店备货，损失关联门禁/电梯/路线证据")
@RestController
@RequestMapping("/api/deliveries/{id}/coldchain")
public class ColdChainController {

    private final ColdChainMonitorService monitorService;
    private final ColdChainResolutionService resolutionService;

    public ColdChainController(ColdChainMonitorService monitorService,
                               ColdChainResolutionService resolutionService) {
        this.monitorService = monitorService;
        this.resolutionService = resolutionService;
    }

    public record TemperatureRequest(@NotNull Double tempCelsius, String note) {
    }

    @Operation(summary = "载物仓实测温度上报", description = "温度传感器上报后立即重算剩余可配送时长与预警级别")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/temperature")
    public ColdChainSnapshot reportTemperature(@PathVariable Long id,
                                               @Valid @RequestBody TemperatureRequest request) {
        return monitorService.reportTemperature(id, request.tempCelsius(), request.note());
    }

    @Operation(summary = "立即计算一次冷链风险（温度/剩余时长/门禁电梯路线等待归因）")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @PostMapping("/evaluate")
    public ColdChainSnapshot evaluate(@PathVariable Long id,
                                      @RequestParam(required = false) String note) {
        return monitorService.evaluateOrder(id, note);
    }

    @Operation(summary = "冷链监控视图", description = "最新温度、剩余可配送时长、预警级别与门禁/电梯/避障等待证据")
    @GetMapping("/monitor")
    public Map<String, Object> monitor(@PathVariable Long id) {
        return monitorService.monitoringView(id);
    }

    @Operation(summary = "载物仓温度快照时间线")
    @GetMapping("/snapshots")
    public List<ColdChainSnapshot> snapshots(@PathVariable Long id) {
        return monitorService.snapshots(id);
    }

    public record ReturnSwapRequest(String storeName) {
    }

    @Operation(summary = "冷链风险退回站点更换商品",
            description = "WARN/CRITICAL 或已超时时退回，原配送证据（事件/快照/门禁/电梯）保留给赔付判断")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @PostMapping("/return-for-swap")
    public com.example.robotdelivery.order.DeliveryOrder returnForSwap(
            @PathVariable Long id, @RequestBody(required = false) ReturnSwapRequest request) {
        String store = request == null ? null : request.storeName();
        return resolutionService.returnForSwap(id, store);
    }

    @Operation(summary = "确认退回站点并完成换品", description = "退回后生成重新配送/退款两个待选处置项")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @PostMapping("/confirm-swap-return")
    public List<ColdChainResolution> confirmSwapReturn(@PathVariable Long id) {
        return resolutionService.confirmSwapReturn(id);
    }

    @Operation(summary = "查询重新配送/退款处置选项")
    @RequireRole({Role.OPS, Role.CS, Role.PROPERTY, Role.ADMIN})
    @GetMapping("/resolutions")
    public List<ColdChainResolution> resolutions(@PathVariable Long id) {
        return resolutionService.listResolutions(id);
    }

    public record DecideRequest(String note) {
    }

    @Operation(summary = "确认处置：选择重新配送行=门店重新备货+新配送单；选择退款行=门店无需备货+通知用户退款")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @PostMapping("/resolutions/{resolutionId}/decide")
    public ColdChainResolution decide(@PathVariable Long id,
                                      @PathVariable Long resolutionId,
                                      @RequestBody(required = false) DecideRequest request) {
        String note = request == null ? null : request.note();
        return resolutionService.decide(resolutionId, CurrentUserHolder.get().username(), note);
    }

    public record LossRequest(@NotNull BigDecimal lossAmount,
                              @NotNull ColdGoodsStatus goodsStatus,
                              String note) {
    }

    @Operation(summary = "登记冷链超时损失（供赔付判断）",
            description = "损失自动关联门禁等待、电梯等待与机器人路线调整证据并给出建议责任")
    @RequireRole({Role.CS, Role.ADMIN})
    @PostMapping("/loss")
    public ColdChainLoss recordLoss(@PathVariable Long id, @Valid @RequestBody LossRequest request) {
        return resolutionService.recordLoss(id, request.lossAmount(), request.goodsStatus(),
                CurrentUserHolder.get().username(), request.note());
    }

    @Operation(summary = "查询冷链超时损失记录")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @GetMapping("/losses")
    public List<ColdChainLoss> losses(@PathVariable Long id) {
        return resolutionService.listLosses(id);
    }
}
