package com.shop.elpsy.service;
import org.slf4j.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
@Component @EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="shop.orders.expiry-enabled",havingValue="true")
public class PreorderExpiryJob {
 private static final Logger log=LoggerFactory.getLogger(PreorderExpiryJob.class);
 private final PreorderOrderService service;
 public PreorderExpiryJob(PreorderOrderService service){this.service=service;}
 @Scheduled(fixedDelayString="${shop.orders.expiry-delay-ms:30000}",initialDelayString="${shop.orders.expiry-delay-ms:30000}")
 public void run(){for(var o:service.due())try{service.expire(o.customerId(),o.id());}catch(RuntimeException e){log.error("Preorder expiry failed for {} ({})",o.id(),e.getClass().getSimpleName());}}
}
