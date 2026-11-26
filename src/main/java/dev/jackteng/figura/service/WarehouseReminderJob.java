package dev.jackteng.figura.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 私人倉庫暫存到期提醒；預設關閉（shop.warehouse.reminder-enabled=true 才啟用）。到期前 reminder-days 天寫一則站內通知（outbox）。 */
@Component @EnableScheduling
@ConditionalOnProperty(name = "shop.warehouse.reminder-enabled", havingValue = "true")
public class WarehouseReminderJob {
    private final WarehouseService service; private final int days;
    public WarehouseReminderJob(WarehouseService service, @Value("${shop.warehouse.reminder-days:7}") int days) { this.service = service; this.days = days; }
    @Scheduled(fixedDelayString = "${shop.warehouse.reminder-delay-ms:3600000}", initialDelayString = "${shop.warehouse.reminder-delay-ms:3600000}")
    public void run() {
        try { service.remind(100, days); }
        catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(WarehouseReminderJob.class).error("Warehouse reminder failed ({})", e.getClass().getSimpleName()); }
    }
}
