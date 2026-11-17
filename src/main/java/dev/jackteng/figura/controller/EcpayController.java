package dev.jackteng.figura.controller;

import dev.jackteng.figura.dto.PaymentPayloads.EcpayStarted;
import dev.jackteng.figura.dto.PaymentPayloads.Start;
import dev.jackteng.figura.pojo.CustomerPrincipal;
import dev.jackteng.figura.pojo.Result;
import dev.jackteng.figura.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * 綠界測試環境信用卡付款。
 * 建立付款需顧客 Cookie＋CSRF；ReturnURL 回呼是唯一免 Cookie／CSRF 的入口，只接受 CheckMacValue 正確的表單。
 * 瀏覽器從綠界返回（ClientBackURL）只是一般前端頁面，不會改變任何狀態。
 */
@RestController
public class EcpayController {
    private static final Logger log = LoggerFactory.getLogger(EcpayController.class);
    private static final MediaType TEXT = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);
    private final PaymentService service;

    public EcpayController(PaymentService service) { this.service = service; }

    @PostMapping("/customer/orders/{id}/ecpay-payments")
    public Result<EcpayStarted> start(@AuthenticationPrincipal CustomerPrincipal user, @PathVariable String id, @RequestBody @Valid Start input) {
        return Result.success(service.startEcpay(user.id(), id, input));
    }

    @PostMapping(value = "/payments/ecpay/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> callback(HttpServletRequest request) {
        if (request.getQueryString() != null || request.getParameterMap().size() > 64)
            return reply(400, "0|Parameter Error");
        Map<String, String> params = new HashMap<>();
        for (var entry : request.getParameterMap().entrySet()) {
            if (entry.getValue().length != 1 || entry.getKey().length() > 64 || entry.getValue()[0].length() > 512)
                return reply(400, "0|Parameter Error");
            params.put(entry.getKey(), entry.getValue()[0]);
        }
        String tradeNo = String.valueOf(params.get("MerchantTradeNo")).replaceAll("[^0-9A-Za-z]", "");
        try {
            var outcome = service.receiveEcpay(params);
            log.info("ECPay notice {} -> {}", tradeNo, outcome.disposition());
            return reply(200, "1|OK");
        } catch (ResponseStatusException e) {
            log.warn("ECPay notice {} rejected: {} {}", tradeNo, e.getStatusCode().value(), e.getReason());
            return reply(e.getStatusCode().value(), "0|" + (e.getStatusCode().value() == 401 ? e.getReason() : "Rejected"));
        } catch (RuntimeException e) {
            log.error("ECPay notice {} failed", tradeNo, e);
            return reply(500, "0|Error");
        }
    }

    private static ResponseEntity<String> reply(int status, String body) {
        return ResponseEntity.status(status).contentType(TEXT).body(body);
    }
}
