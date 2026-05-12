package com.xiyiyun.shop.mvp;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/upstream")
public class UpstreamCallbackController {
    private final InMemoryShopRepository repository;

    public UpstreamCallbackController(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @PostMapping(value = "/fulu/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String fuluCallback(
        @RequestParam(required = false) Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleFuluOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/fulu/callback/{supplierId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String fuluCallbackBySupplier(
        @PathVariable Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleFuluOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/fengzhushou/callback")
    public Map<String, String> fengzhushouCallback(
        @RequestParam(required = false) Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleFengzhushouOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/fengzhushou/callback/{supplierId}")
    public Map<String, String> fengzhushouCallbackBySupplier(
        @PathVariable Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleFengzhushouOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/chengquan/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String chengquanCallback(
        @RequestParam(required = false) Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleChengquanOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/chengquan/callback/{supplierId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String chengquanCallbackBySupplier(
        @PathVariable Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleChengquanOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/fanchen/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String fanchenCallback(
        @RequestParam(required = false) Long supplierId,
        @RequestParam Map<String, Object> body
    ) {
        return repository.handleFanchenOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/fanchen/callback/{supplierId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String fanchenCallbackBySupplier(
        @PathVariable Long supplierId,
        @RequestParam Map<String, Object> body
    ) {
        return repository.handleFanchenOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/jingzhao/callback", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String jingzhaoCallback(
        @RequestParam(required = false) Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleJingzhaoOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/jingzhao/callback/{supplierId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String jingzhaoCallbackBySupplier(
        @PathVariable Long supplierId,
        @RequestBody Map<String, Object> body
    ) {
        return repository.handleJingzhaoOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/jingzhao/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String jingzhaoFormCallback(
        @RequestParam(required = false) Long supplierId,
        @RequestParam Map<String, Object> body
    ) {
        return repository.handleJingzhaoOrderCallback(supplierId, body);
    }

    @PostMapping(value = "/jingzhao/callback/{supplierId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public String jingzhaoFormCallbackBySupplier(
        @PathVariable Long supplierId,
        @RequestParam Map<String, Object> body
    ) {
        return repository.handleJingzhaoOrderCallback(supplierId, body);
    }
}
