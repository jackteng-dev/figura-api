package com.shop.elpsy;
import com.shop.elpsy.service.FulfillmentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class FulfillmentDisabledTest {
 @Test void defaultOffRejectsMutationBeforeTouchingAnyDatabaseOrActor(){var service=new FulfillmentService(null,null,null,null,null,null,false);assertEquals(404,assertThrows(ResponseStatusException.class,()->service.change(java.util.UUID.randomUUID().toString(),"SHIP",null,null)).getStatusCode().value());}
}
