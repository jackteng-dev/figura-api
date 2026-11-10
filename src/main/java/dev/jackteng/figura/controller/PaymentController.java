package dev.jackteng.figura.controller;
import dev.jackteng.figura.dto.PaymentPayloads.*;
import dev.jackteng.figura.pojo.*;
import dev.jackteng.figura.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class PaymentController {
 private final PaymentService service;
 public PaymentController(PaymentService service){this.service=service;}
 @GetMapping({"/customer/orders/{id}/mock-payments","/mobile/customer/orders/{id}/mock-payments"}) public Result<Options> options(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id){return Result.success(service.options(user.id(),id));}
 @PostMapping({"/customer/orders/{id}/mock-payments","/mobile/customer/orders/{id}/mock-payments"}) public Result<Started> start(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id,@RequestBody @Valid Start input){return Result.success(service.start(user.id(),id,input));}
 @PostMapping({"/customer/mock-payments/{id}/simulate","/mobile/customer/mock-payments/{id}/simulate"}) public Result<Receipt> simulate(@AuthenticationPrincipal CustomerPrincipal user,@PathVariable String id,@RequestBody @Valid Simulate input){return Result.success(service.simulate(user.id(),id,input));}
 @PostMapping(value="/payments/mock/callback",consumes="application/json") public Result<CallbackAck> callback(@RequestHeader(value="X-Mock-Timestamp",required=false) String timestamp,@RequestHeader(value="X-Mock-Signature",required=false) String signature,@RequestBody String raw){var result=service.receive(timestamp,signature,raw);return Result.success(new CallbackAck(result.eventId(),result.disposition(),result.replayed(),result.payment().id(),result.order().id()));}
}
