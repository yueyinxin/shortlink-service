package io.github.yueyinxin.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>{@code @ConfigurationPropertiesScan} 让所有 {@code @ConfigurationProperties} 记录类被自动注册为
 * Bean，无需为每个配置类单独写 {@code @EnableConfigurationProperties}。
 * {@code @EnableScheduling} 用于启用统计刷库的定时任务。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ShortlinkServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortlinkServiceApplication.class, args);
    }
}
