package com.xiyiyun.shop.mvp;

/**
 * 上游「结果未知」类故障：连接失败、读取超时、5xx、响应无法解析。
 *
 * <p>缺陷 A4 的核心语义载体：抛出本异常表示我方<b>无法确定</b>上游是否已受理该笔采购，
 * 因此调用方必须把订单转入中间态 {@code PROCURING} 等待对账，
 * 而不能按失败处理（上游可能已扣我方预付款）。</p>
 *
 * <p>与之相对，{@link SupplierBusinessException} 表示上游明确拒单，可以安全置 FAILED。</p>
 */
public class SupplierTransportException extends RuntimeException {
    private final String supplierCode;
    private final String action;
    private final Integer httpStatus;

    public SupplierTransportException(String supplierCode, String action, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.supplierCode = supplierCode;
        this.action = action;
        this.httpStatus = httpStatus;
    }

    public SupplierTransportException(String supplierCode, String action, String message) {
        this(supplierCode, action, message, null, null);
    }

    public String supplierCode() {
        return supplierCode;
    }

    public String action() {
        return action;
    }

    /** 上游返回的 HTTP 状态码，纯网络异常时为 null。 */
    public Integer httpStatus() {
        return httpStatus;
    }
}
