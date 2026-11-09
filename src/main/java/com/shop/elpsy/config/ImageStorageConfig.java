package com.shop.elpsy.config;
import com.shop.elpsy.service.ImageObjectStore;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.core.sync.RequestBody;
import java.net.URI;
import java.time.Duration;
@Configuration public class ImageStorageConfig {
 @Bean @ConditionalOnProperty(name="shop.images.enabled",havingValue="false",matchIfMissing=true)
 ImageObjectStore disabled(){return new ImageObjectStore(){private RuntimeException off(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"圖片儲存未啟用");}public void put(String k,byte[] b,String t){throw off();}public byte[] get(String k){throw off();}public void delete(String k){throw off();}};}
 @Bean(destroyMethod="close") @ConditionalOnProperty(name="shop.images.enabled",havingValue="true")
 S3Client imageS3(@Value("${shop.images.endpoint:}") String endpoint,@Value("${shop.images.access-key:}") String access,@Value("${shop.images.secret-key:}") String secret){
  if(endpoint.isBlank()||access.isBlank()||secret.isBlank())throw new IllegalStateException("Private image storage configuration required");
  return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
   .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED).responseChecksumValidation(software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED)
   .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
   .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(7)).retryPolicy(r->r.numRetries(1))).build();
 }
 @Bean @ConditionalOnProperty(name="shop.images.enabled",havingValue="true")
 ImageObjectStore enabled(S3Client client,@Value("${shop.images.bucket:elpsy-shop-images-demo}") String bucket,@Value("${shop.images.init-bucket:false}") boolean init){
  if(!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))throw new IllegalStateException("Invalid image bucket");
  if(init){try{client.headBucket(b->b.bucket(bucket));}catch(S3Exception e){if(e.statusCode()!=404)throw e;client.createBucket(b->b.bucket(bucket));}}
  return new ImageObjectStore(){public void put(String key,byte[] data,String type){client.putObject(b->b.bucket(bucket).key(key).contentType(type),RequestBody.fromBytes(data));}public byte[] get(String key){return client.getObjectAsBytes(b->b.bucket(bucket).key(key)).asByteArray();}public void delete(String key){client.deleteObject(b->b.bucket(bucket).key(key));}};
 }
}
