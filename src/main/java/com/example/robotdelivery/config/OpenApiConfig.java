package com.example.robotdelivery.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI robotDeliveryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("城市社区配送机器人进楼门禁避障签收服务")
                        .version("1.0.0")
                        .description("外卖/药品/商超订单由配送机器人进楼配送：门禁协同、电梯协同、避障停留、"
                                + "签收与退回、异常处理（门禁拒绝/老人儿童挡路/电梯满载/楼道施工/低电量/超时未取/"
                                + "载物仓故障/冷藏超时/送错楼栋投诉/夜间限制），所有动作写入统一配送事件流；"
                                + "冷链订单持续温控预警、退回换品、重新配送/退款与损失归因。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("POST /api/auth/login 获取 token，测试账号见 README")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
