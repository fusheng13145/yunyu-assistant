package com.leyon.backend.handler;

import com.leyon.backend.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * 全局异常处理器测试（对应实测缺陷 C-64）
 * 覆盖：请求形状类错误的 HTTP 状态与响应体 code 一致、405 必带 Allow 头、406 不被兜底成 500（v2.49），
 * 以及"每个 @ExceptionHandler 都必须显式声明 @ResponseStatus"这条防回退约束
 *
 * @author leyon
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 取处理器上声明的 HTTP 状态（走合并注解，value/code 互为别名时也能取到）
     */
    private static int declaredStatus(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = GlobalExceptionHandler.class.getMethod(methodName, paramTypes);
        ResponseStatus status = AnnotatedElementUtils.getMergedAnnotation(method, ResponseStatus.class);
        assertThat(status).as("处理器 %s 必须有 @ResponseStatus", methodName).isNotNull();
        HttpStatus code = status.code();
        return code.value();
    }

    @Test
    void everyHandlerDeclaresResponseStatus() {
        for (Method method : GlobalExceptionHandler.class.getMethods()) {
            if (method.isAnnotationPresent(ExceptionHandler.class)) {
                assertThat(method.isAnnotationPresent(ResponseStatus.class))
                        .as("处理器 %s 缺少 @ResponseStatus，HTTP 状态会退回 200", method.getName())
                        .isTrue();
            }
        }
    }

    @Test
    void methodNotSupported_returns405WithAllowHeader() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("DELETE", Set.of("GET", "PUT"));

        ApiResponse<Void> body = handler.handleMethodNotSupported(e, response);

        assertThat(body.getCode()).isEqualTo(405);
        assertThat(declaredStatus("handleMethodNotSupported",
                HttpRequestMethodNotSupportedException.class, HttpServletResponse.class)).isEqualTo(405);
        // Allow 头由 Set<HttpMethod> 拼出，顺序不保证，故按集合比较
        assertThat(response.getHeader("Allow").split(", ")).containsExactlyInAnyOrder("GET", "PUT");
    }

    @Test
    void unsupportedContentType_returns415() throws Exception {
        HttpMediaTypeNotSupportedException e =
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ApiResponse<Void> body = handler.handleMediaTypeNotSupported(e);

        assertThat(body.getCode()).isEqualTo(415);
        assertThat(declaredStatus("handleMediaTypeNotSupported",
                HttpMediaTypeNotSupportedException.class)).isEqualTo(415);
        assertThat(body.getMessage()).isEqualTo("不支持的请求内容类型");
    }

    @Test
    void missingUploadPart_returns400NamingTheField() throws Exception {
        ApiResponse<Void> body = handler.handleMissingPart(new MissingServletRequestPartException("file"));

        assertThat(body.getCode()).isEqualTo(400);
        assertThat(declaredStatus("handleMissingPart", MissingServletRequestPartException.class)).isEqualTo(400);
        assertThat(body.getMessage()).isEqualTo("缺少上传文件：file");
    }

    @Test
    void uploadTooLarge_returns413InsteadOf400() throws Exception {
        ApiResponse<Void> body = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(52_428_800L));

        assertThat(body.getCode()).isEqualTo(413);
        assertThat(declaredStatus("handleMaxUploadSize", MaxUploadSizeExceededException.class)).isEqualTo(413);
        assertThat(body.getMessage()).doesNotContain("52428800");
    }

    /**
     * v2.49 · C-108：`Accept` 与接口 `produces` 不匹配是客户端用错，此前无专用处理器 ⇒
     * 它（Spring 6.2 里是 ServletException，本身带着 406）落进 Exception 兜底被改写成 500 + 带栈 ERROR 日志（真机证据：smoke §7.9 只能登记一条 SKIP）。
     * 注意 406 的固有形状：请求体既然不接受该响应的媒体类型，Spring 写不出 `ApiResponse` 正文，
     * **状态码正确而正文可能为空**，本批只收"状态码不再撒谎"这一半。
     */
    @Test
    void acceptMismatch_returns406() throws Exception {
        HttpMediaTypeNotAcceptableException e =
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_EVENT_STREAM));

        ApiResponse<Void> body = handler.handleMediaTypeNotAcceptable(e);

        assertThat(body.getCode()).isEqualTo(406);
        assertThat(declaredStatus("handleMediaTypeNotAcceptable",
                HttpMediaTypeNotAcceptableException.class)).isEqualTo(406);
        assertThat(body.getMessage()).isEqualTo("不接受的请求媒体类型");
    }

    /**
     * 路由判据：新处理器必须**抢在** Exception 兜底之前命中。只测上面的方法调用测不出这条——
     * 处理器存在却写得让兜底优先，同样是被 500 服务掉。
     */
    @Test
    void acceptMismatch_notSwallowedByGenericFallback() {
        ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        Method resolved = resolver.resolveMethod(new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_EVENT_STREAM)));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getName()).isEqualTo("handleMediaTypeNotAcceptable");
    }

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "payload");
        bindingResult.addError(new FieldError("payload", "name", "不能为空"));
        MethodParameter parameter = new MethodParameter(Sample.class.getMethod("submit", String.class), 0);

        ApiResponse<?> body = handler.handleValidationException(
                new MethodArgumentNotValidException(parameter, bindingResult));

        assertThat(body.getCode()).isEqualTo(400);
        assertThat(declaredStatus("handleValidationException", MethodArgumentNotValidException.class)).isEqualTo(400);
        assertThat(body.getMessage()).isEqualTo("参数校验失败");
        assertThat(body.getData()).asInstanceOf(MAP)
                .containsEntry("name", "不能为空");
    }

    /** 仅为 MethodParameter 提供一个真实方法签名 */
    public static class Sample {
        public void submit(String arg) {
        }
    }
}
