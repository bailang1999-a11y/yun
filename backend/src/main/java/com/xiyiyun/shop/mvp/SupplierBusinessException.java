package com.xiyiyun.shop.mvp;

/**
 * 上游<b>明确</b>返回业务失败：HTTP 200 且响应体里带明确错误码（如 kasushou code!=200、
 * fanchen resultno 不在成功集合、fengzhushou 公共错误码等）。
 *
 * <p>缺陷 A4 语义：只有这一类才允许把订单置 {@code FAILED}，
 * 因为上游已明确表示未受理，不存在「已扣款但我方判失败」的资金风险。</p>
 */
public class SupplierBusinessException extends RuntimeException {
    private final String supplierCode;
    private final String action;
    private final String upstreamCode;

    public SupplierBusinessException(String supplierCode, String action, String upstreamCode, String message) {
        super(message);
        this.supplierCode = supplierCode;
        this.action = action;
        this.upstreamCode = upstreamCode;
    }

    public String supplierCode() {
        return supplierCode;
    }

    public String action() {
        return action;
    }

    public String upstreamCode() {
        return upstreamCode;
    }
}
