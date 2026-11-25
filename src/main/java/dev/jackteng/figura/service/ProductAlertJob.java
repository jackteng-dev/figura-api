package dev.jackteng.figura.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 商品提醒掃描排程；預設關閉（shop.product-alerts.scanner-enabled=true 才啟用）。每 tick 上限 batch 個商品。 */
@Component @EnableScheduling
@ConditionalOnProperty(name = "shop.product-alerts.scanner-enabled", havingValue = "true")
public class ProductAlertJob {
    private final ProductAlertScanner scanner; private final int batch;
    public ProductAlertJob(ProductAlertScanner scanner, @Value("${shop.product-alerts.batch:100}") int batch) { this.scanner = scanner; this.batch = batch; }
    @Scheduled(fixedDelayString = "${shop.product-alerts.fixed-delay-ms:300000}", initialDelayString = "${shop.product-alerts.fixed-delay-ms:300000}")
    public void run() {
        try { scanner.scan(batch); }
        catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(ProductAlertJob.class).error("Product alert scan failed ({})", e.getClass().getSimpleName()); }
    }
}
