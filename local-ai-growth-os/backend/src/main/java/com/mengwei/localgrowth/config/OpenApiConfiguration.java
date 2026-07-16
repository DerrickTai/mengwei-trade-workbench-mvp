package com.mengwei.localgrowth.config;
import io.swagger.v3.oas.models.OpenAPI; import io.swagger.v3.oas.models.info.Info; import org.springframework.context.annotation.Bean; import org.springframework.context.annotation.Configuration;
@Configuration public class OpenApiConfiguration { @Bean OpenAPI localGrowthOpenApi(){return new OpenAPI().info(new Info().title("Local AI Growth OS API").version("v0.1").description("本地生活 GEO 诊断 API；Mock 结果仅供演示。"));} }

