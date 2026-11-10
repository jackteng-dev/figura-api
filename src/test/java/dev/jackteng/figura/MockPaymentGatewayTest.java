package dev.jackteng.figura;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jackteng.figura.dto.PaymentPayloads.Notification;
import dev.jackteng.figura.service.MockPaymentGateway;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class MockPaymentGatewayTest {
 private MockPaymentGateway gateway(boolean enabled,String secret){return new MockPaymentGateway(enabled,secret,new ObjectMapper(),Validation.buildDefaultValidatorFactory().getValidator());}
 @Test void disabledGatewayCannotSignOrAcceptEvenAValidLookingWebhook(){var g=gateway(false,"unused-private-test-setting");assertFalse(g.enabled());assertEquals(404,assertThrows(ResponseStatusException.class,()->g.verify("1000000000","0".repeat(64),"{}")).getStatusCode().value());assertThrows(ResponseStatusException.class,()->g.sign("1000000000","{}"));}
 @Test void tamperedBodyAndUnknownFieldAreRejected(){var g=gateway(true,"synthetic-private-key-used-only-in-unit-test");var n=new Notification(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),"mock_"+java.util.UUID.randomUUID(),10L,"TWD","SUCCESS");var raw=g.raw(n);String ts=Long.toString(java.time.Instant.now().getEpochSecond());assertEquals(n,g.verify(ts,g.sign(ts,raw),raw));assertEquals(401,assertThrows(ResponseStatusException.class,()->g.verify(ts,g.sign(ts,raw),raw+" ")).getStatusCode().value());var extra=raw.substring(0,raw.length()-1)+",\"owner\":\"forged\"}";assertEquals(400,assertThrows(ResponseStatusException.class,()->g.verify(ts,g.sign(ts,extra),extra)).getStatusCode().value());}
}
