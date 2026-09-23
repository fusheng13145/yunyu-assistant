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
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * 全局异常处理器测试（对应实测缺陷 C-64）
 * 覆盖：请求形状类错误的 HTTP 状态与响应体 code 一致、405 必带 Allow 头，
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
