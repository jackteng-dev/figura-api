package com.shop.elpsy;
import com.shop.elpsy.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class RefundDisabledTest {
 @Test void defaultOffRejectsBeforeDatabaseOrActor(){var service=new RefundService(null,null,null,null,null,null,null,null,false,false);assertEquals(404,assertThrows(ResponseStatusException.class,()->service.change(java.util.UUID.randomUUID().toString(),null,null,null)).getStatusCode().value());}
}
