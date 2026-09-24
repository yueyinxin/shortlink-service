package io.github.yueyinxin.shortlink.common.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yueyinxin.shortlink.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 在 Spring MVC 之外写出一致格式的错误响应。
 *
 * <p><b>为什么需要这个类：</b> {@code @RestControllerAdvice} 只能拦截
 * DispatcherServlet 内部抛出的异常。Servlet Filter 在 DispatcherServlet <b>之前</b>执行，
 * 它抛出的异常或直接写出的响应完全不经过 {@code @ControllerAdvice}。
 *
 * <p>限流过滤器与 Spring Security 的鉴权入口都属于这一类。如果它们各自手写响应，
 * 就会出现"同一个接口，参数错误返回一种 JSON 结构，被限流却返回另一种"的不一致 ——
 * 客户端不得不写两套解析逻辑。而这类不一致往往在联调阶段才被发现。
 *
 * <p>因此把"写出错误 JSON"这件事收敛到一个地方，Filter 层也复用同一套信封格式。
 */
@Component
public class ApiErrorWriter {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorWriter.class);

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出错误响应。
     *
     * <p>响应一旦开始写出就无法回退，因此任何写入失败都只记录日志而不抛出 ——
     * 在错误处理路径上再抛异常，只会把一个可解释的失败变成一个不可解释的 500。
     */
    public void write(HttpServletResponse response, ErrorCode errorCode, String message) {
        if (response.isCommitted()) {
            log.warn("响应已提交，无法写入错误体: code={}", errorCode);
            return;
        }

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ApiResponse<Void> body = ApiResponse.error(errorCode, message, null, null);
        try {
            objectMapper.writeValue(response.getOutputStream(), body);
        } catch (IOException e) {
            log.error("写出错误响应失败: code={}", errorCode, e);
        }
    }
}
