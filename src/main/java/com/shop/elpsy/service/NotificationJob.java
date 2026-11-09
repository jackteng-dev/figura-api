package com.shop.elpsy.service;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @EnableScheduling
@ConditionalOnProperty(name="shop.notifications.worker-enabled",havingValue="true")
public class NotificationJob {
 private final NotificationService service;public NotificationJob(NotificationService service){this.service=service;}
 @Scheduled(fixedDelayString="${shop.notifications.fixed-delay-ms:30000}",initialDelayString="${shop.notifications.fixed-delay-ms:30000}")
 public void run(){for(int i=0;i<20;i++){try{if(service.runOne()==null)break;}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(NotificationJob.class).error("Mock notification worker failed ({})",e.getClass().getSimpleName());break;}}}
}
