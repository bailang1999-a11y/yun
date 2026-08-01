package com.xiyiyun.shop;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理（批次5 / B4）。
 *
 * <p><b>原缺陷</b>：项目没有任何 {@code @RestControllerAdvice}。控制器只在部分方法里
 * try/catch {@code IllegalArgumentException | IllegalStateException}，其余异常直落 Spring
 * 默认 {@code /error}：响应体结构与 {@link ApiResponse} 完全不同（{@code timestamp/status/error/path}），
 * 且在 {@code server.error.include-*} 放开时会把异常消息甚至堆栈透给前端。
 *
 * <p><b>修法与信息分级</b>：
 * <ul>
 *   <li><b>业务异常</b>（{@link IllegalArgumentException} / {@link IllegalStateException}）：
 *       这两类在本项目里始终承载面向用户的中文提示（如「账号或密码不正确」），
 *       原样透出 message，HTTP 200 + {@code code=-1}，与既有控制器 try/catch 的行为完全一致；</li>
 *   <li><b>请求格式类异常</b>：返回固定中文文案，<b>不</b>透出框架异常 message
 *       （Jackson 的 message 会带类名、字段路径甚至反序列化片段）；</li>
 *   <li><b>未预期异常</b>：只回通用文案 + traceId，完整堆栈仅进服务端日志，
 *       并把 traceId 放在 {@code X-Trace-Id} 响应头便于工单关联。</li>
 * </ul>
 *
 * <p>响应体一律是 {@link ApiResponse}（{@code code/message/data}），
 * 三端前端（apps/admin、apps/h5、apps/web）都从 {@code data.message} 取错误文案，
 * 因此不需要改任何前端解析逻辑。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_HEADER = "X-Trace-Id";
    static final String GENERIC_ERROR_MESSAGE = "服务器处理请求时出现异常，请稍后重试";
    static final String BAD_REQUEST_MESSAGE = "请求参数格式不正确";

    /** 业务异常：文案本身就是给用户看的，原样透出，状态码沿用既有约定（200 + code=-1）。 */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleBusiness(RuntimeException ex, HttpServletRequest request) {
        log.warn("Business rejection at {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return body(HttpStatus.OK, ApiResponse.fail(safeBusinessMessage(ex)), null);
    }

    /** 请求体/参数格式类异常：统一固定文案，不回显框架内部描述。 */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("Malformed request at {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName());
        return body(HttpStatus.BAD_REQUEST, ApiResponse.fail(BAD_REQUEST_MESSAGE), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, ApiResponse.fail("上传文件超过大小限制"), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, ApiResponse.fail("请求方法不被支持"), null);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoHandlerFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ApiResponse.fail("接口不存在"), null);
    }

    /**
     * 兜底：任何未预期异常只回通用文案 + traceId。
     *
     * <p>绝不把 {@code ex.getMessage()} 或堆栈写进响应体 —— NPE 的 helpful message、
     * SQL 异常的建表语句片段、连接串等都会从这里泄漏。
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Throwable ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("Unhandled exception traceId={} at {} {}", traceId, request.getMethod(), request.getRequestURI(), ex);
        return body(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ApiResponse.fail(GENERIC_ERROR_MESSAGE + "（追踪号 " + traceId + "）"),
            traceId
        );
    }

    /**
     * 业务异常文案兜底：极少数业务异常可能是框架/JDK 抛出的同类型异常
     * （例如 {@code Integer.parseInt} 的 NumberFormatException 是 IllegalArgumentException 子类），
     * message 为空或明显是技术描述时退回通用文案。
     */
    private String safeBusinessMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return GENERIC_ERROR_MESSAGE;
        }
        if (message.contains("com.xiyiyun") || message.contains("java.")
            || message.contains("org.springframework") || message.contains("Exception")) {
            return GENERIC_ERROR_MESSAGE;
        }
        return message;
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private ResponseEntity<ApiResponse<Void>> body(HttpStatus status, ApiResponse<Void> payload, String traceId) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (traceId != null) {
            builder = builder.header(TRACE_HEADER, traceId);
        }
        return builder.body(payload);
    }
}
