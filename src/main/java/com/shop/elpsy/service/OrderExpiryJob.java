package com.shop.elpsy.service;
import com.shop.elpsy.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
@Component @EnableScheduling
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="shop.orders.expiry-enabled",havingValue="true")
public class OrderExpiryJob {
 private static final Logger log=LoggerFactory.getLogger(OrderExpiryJob.class);
 private final OrderMapper orders;private final OrderService service;
 public OrderExpiryJob(OrderMapper orders,OrderService service){this.orders=orders;this.service=service;}
 @Scheduled(fixedDelayString="${shop.orders.expiry-delay-ms:30000}",initialDelayString="${shop.orders.expiry-delay-ms:30000}")
 public void run(){for(var o:orders.due()){try{service.expire(o.getCustomerId(),o.getId());}catch(RuntimeException e){log.error("Order expiry failed for {} ({})",o.getId(),e.getClass().getSimpleName());}}}
}
