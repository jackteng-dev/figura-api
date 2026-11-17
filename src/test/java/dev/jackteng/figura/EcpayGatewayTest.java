package dev.jackteng.figura;

import dev.jackteng.figura.service.EcpayGateway;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** CheckMacValue 對照綠界官方文件範例（測試環境公開 HashKey／HashIV），不連網。 */
class EcpayGatewayTest {
    private EcpayGateway gateway(boolean enabled,String checkout,String back){return new EcpayGateway(enabled,"3002607","pwFHCqoQZGmho4w6","EkRm7iFT261dpevs",checkout,"https://shop.example.test/figura/api/payments/ecpay/callback",back,false);}
    @Test void matchesOfficialDocumentationVector(){
        var p=new LinkedHashMap<String,String>();
        p.put("ChoosePayment","ALL");p.put("EncryptType","1");p.put("ItemName","Apple iphone 15");p.put("MerchantID","3002607");p.put("MerchantTradeDate","2023/03/12 15:30:23");
        p.put("MerchantTradeNo","ecpay20230312153023");p.put("PaymentType","aio");p.put("ReturnURL","https://www.ecpay.com.tw/receive.php");p.put("TotalAmount","30000");p.put("TradeDesc","促銷方案");
        var g=gateway(false,"https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","https://shop.example.test/shop/orders");
        assertEquals("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840",g.checkMac(p));
        p.put("CheckMacValue","6c51c9e6888de861fd62fb1dd17029fc742634498fd813dc43d4243b5685b840");assertTrue(g.verify(p));
        p.put("TotalAmount","1");assertFalse(g.verify(p));
    }
    @Test void enabledConfigurationRefusesProductionOrInsecureUrls(){
        assertThrows(IllegalStateException.class,()->invoke(gateway(true,"https://payment.ecpay.com.tw/Cashier/AioCheckOut/V5","https://shop.example.test/shop/orders")));
        assertThrows(IllegalStateException.class,()->invoke(gateway(true,"https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","http://shop.example.test/shop/orders")));
        assertThrows(IllegalStateException.class,()->invoke(gateway(true,"https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","https://shop.example.test/shop/orders?x=1")));
        assertDoesNotThrow(()->invoke(gateway(true,"https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","https://shop.example.test/shop/orders")));
        assertDoesNotThrow(()->invoke(gateway(true,"https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","http://localhost:5173/shop/orders")));
        var no=new EcpayGateway(true,"3002607","","EkRm7iFT261dpevs","https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","https://a.test/cb","https://a.test/back",false);assertThrows(IllegalStateException.class,()->invoke(no));
    }
    @Test void tradeNumbersFitEcpayLimits(){var g=gateway(false,"https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5","https://a.test/b");for(int i=0;i<200;i++)assertTrue(g.newTradeNo().matches("FG[0-9A-Z]{18}"));}
    private static void invoke(EcpayGateway g){
        try{var m=EcpayGateway.class.getDeclaredMethod("validateConfiguration");m.setAccessible(true);m.invoke(g);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new RuntimeException(e.getCause());}
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
    }
}
