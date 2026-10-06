package com.example.robotdelivery.config;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.datatype.hibernate5.jakarta.Hibernate5JakartaModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置：注册 Hibernate5 模块，序列化实体时透明处理懒加载代理
 * （例如订单列表里的 community/building 在部分查询路径下是 Hibernate 代理对象），
 * 避免 ByteBuddyInterceptor 无序列化器导致 500。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module hibernate5JakartaModule() {
        return new Hibernate5JakartaModule();
    }
}
