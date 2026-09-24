package io.github.yueyinxin.shortlink.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 接口文档配置。
 *
 * <p>声明 JWT 安全方案后，Swagger UI 会出现 "Authorize" 按钮，
 * 可以直接填入令牌调试受保护接口 —— 没有这一步，文档只能看不能试，
 * 联调时仍需借助 Postman，文档的价值大打折扣。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI shortlinkOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShortLink Service API")
                        .version("v1.0.0")
                        .description("""
                                短链接服务接口文档。

                                ## 认证方式
                                除注册、登录、刷新令牌与短链跳转外，其余接口均需在请求头中携带：
                                ```
                                Authorization: Bearer <accessToken>
                                ```

                                ## 统一响应格式
                                成功：`{"success": true, "data": {...}}`
                                失败：`{"success": false, "error": {"code": "...", "message": "...", "traceId": "..."}}`

                                失败响应中的 `traceId` 可与服务端日志关联，报障时提供它可快速定位。
                                """)
                        .contact(new Contact().name("yueyinxin"))
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 accessToken")))
                // 声明为全局安全要求，但公开接口不受影响 ——
                // Spring Security 的授权规则才是真正的准入判断，这里仅影响文档展示
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
