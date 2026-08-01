package com.xiyiyun.shop.mvp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付宝异步通知入口。
 *
 * <p>与既有 {@link PaymentMvpController} 的区别：
 * <ul>
 *   <li>那个接口收 <b>JSON</b> 报文、用<b>自定义 HMAC</b> 密钥验签（我方与上游约定的规格）；</li>
 *   <li>支付宝发的是 <b>x-www-form-urlencoded</b> 表单、<b>RSA2</b> 签名。</li>
 * </ul>
 * 两者报文格式与验签算法都不同，因此必须单独一个入口，不能复用。
 *
 * <p>应答体必须是纯文本 {@code success}。返回 JSON 或其它内容支付宝会判定为失败，
 * 并在 24 小时内按 2m/10m/... 的策略反复重投同一条通知。
 *
 * <p>该接口<b>公网匿名可访问</b>（SecurityConfig 中 {@code anyRequest().permitAll()}），
 * 这是支付宝服务端直连的必要条件；报文可信性完全由 RSA2 验签保证，
 * 见 {@link AlipayGatewayService#verifyNotify}。
 */
@RestController
public class AlipayNotifyController {
    private final AlipayPaymentFacade alipayPaymentFacade;

    public AlipayNotifyController(AlipayPaymentFacade alipayPaymentFacade) {
        this.alipayPaymentFacade = alipayPaymentFacade;
    }

    @PostMapping(
        path = AlipayGatewayService.NOTIFY_PATH,
        produces = MediaType.TEXT_PLAIN_VALUE
    )
    public String notify(@RequestParam Map<String, String> params) {
        // 必须拿到含 sign / sign_type 的全量参数，验签时缺一个字段就会失败。
        return alipayPaymentFacade.handleNotify(new LinkedHashMap<>(params));
    }
}
