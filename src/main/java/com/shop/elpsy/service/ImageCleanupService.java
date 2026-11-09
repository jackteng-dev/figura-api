package com.shop.elpsy.service;
import com.shop.elpsy.mapper.ImageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
@Service public class ImageCleanupService {
 private final ImageMapper mapper;private final ImageObjectStore store;private final TransactionTemplate tx;private final boolean enabled;
 public ImageCleanupService(ImageMapper mapper,ImageObjectStore store,PlatformTransactionManager manager,@Value("${shop.images.enabled:false}") boolean enabled){this.mapper=mapper;this.store=store;this.enabled=enabled;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
 /** Durable orphan reservation, no FK or lock on the outer product/actor transaction. */
 public void reserve(String key){tx.executeWithoutResult(s->mapper.queue(key));}
 @Scheduled(fixedDelayString="${shop.images.cleanup-delay-ms:60000}") public void run(){if(!enabled)return;for(int i=0;i<25;i++){Boolean found=tx.execute(s->{String key=mapper.nextCleanup();if(key==null)return false;if(mapper.referenced(key)>0){mapper.unqueue(key);return true;}try{store.delete(key);mapper.unqueue(key);}catch(RuntimeException e){mapper.retry(key);}return true;});if(!Boolean.TRUE.equals(found))break;}}
}
