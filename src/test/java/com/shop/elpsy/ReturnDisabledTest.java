package com.shop.elpsy;
import com.shop.elpsy.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class ReturnDisabledTest {
 @Test void defaultOffRejectsCustomerStaffAndReturnedRefundBeforeDatabase(){var s=new ReturnService(null,null,null,null,null,null,null,null,false,true);String id=java.util.UUID.randomUUID().toString();assertEquals(404,assertThrows(ResponseStatusException.class,()->s.request(null,id,null)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->s.change(id,"RECEIVE",null,null,null)).getStatusCode().value());var r=new RefundService(null,null,null,null,null,null,null,null,true,false);assertEquals(404,assertThrows(ResponseStatusException.class,()->r.changeReturned(id,null,null)).getStatusCode().value());}
}
