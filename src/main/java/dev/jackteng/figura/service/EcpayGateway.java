package dev.jackteng.figura.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 綠界 AIO（全方位金流）信用卡付款，只對接綠界「測試環境」。
 * <ul>
 * <li>設定只讀私有環境變數，預設停用；HashKey／HashIV 不寫入 Git、不出現在回應或日誌。</li>
 * <li>CheckMacValue：參數依名稱不分大小寫排序 → 前後加 HashKey／HashIV → .NET 風格 URL encode → 小寫 → SHA-256 → 大寫 hex。</li>
 * <li>只有伺服器對伺服器的 ReturnURL 通知會改變付款狀態；瀏覽器返回頁只讀取狀態。</li>
 * </ul>
 */
@Component
public class EcpayGateway {
    public static final String PAYMENT_TYPE = "aio";
    private static final DateTimeFormatter TRADE_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final char[] TRADE_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled, acceptSimulated;
    private final String merchantId, hashKey, hashIv, checkoutUrl, returnUrl, clientBackUrl;

    public EcpayGateway(@Value("${ECPAY_ENABLED:false}") boolean enabled,
                        @Value("${ECPAY_MERCHANT_ID:}") String merchantId,
                        @Value("${ECPAY_HASH_KEY:}") String hashKey,
                        @Value("${ECPAY_HASH_IV:}") String hashIv,
                        @Value("${ECPAY_CHECKOUT_URL:https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5}") String checkoutUrl,
                        @Value("${ECPAY_RETURN_URL:}") String returnUrl,
                        @Value("${ECPAY_CLIENT_BACK_URL:}") String clientBackUrl,
                        @Value("${ECPAY_ACCEPT_SIMULATED_PAID:false}") boolean acceptSimulated) {
        this.enabled = enabled; this.merchantId = merchantId.trim(); this.hashKey = hashKey; this.hashIv = hashIv;
        this.checkoutUrl = checkoutUrl.trim(); this.returnUrl = returnUrl.trim(); this.clientBackUrl = clientBackUrl.trim();
        this.acceptSimulated = acceptSimulated;
    }

    @PostConstruct void validateConfiguration() {
        if (!enabled) return;
        if (!merchantId.matches("[0-9]{1,10}") || hashKey.isBlank() || hashIv.isBlank())
            throw new IllegalStateException("Enabled ECPay requires private ECPAY_MERCHANT_ID, ECPAY_HASH_KEY and ECPAY_HASH_IV");
        // 正式收款網址需另行申請及審核；這個里程碑只允許綠界測試環境。
        if (!checkoutUrl.equals("https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5"))
            throw new IllegalStateException("ECPAY_CHECKOUT_URL must be the ECPay stage AIO endpoint");
        requireHttps(returnUrl, "ECPAY_RETURN_URL"); requireHttps(clientBackUrl, "ECPAY_CLIENT_BACK_URL");
    }

    private static void requireHttps(String value, String name) {
        try {
            var uri = URI.create(value);
            boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            // 只有本機展示可用 http；公開網址一律 https。
            if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback)) || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " must be an absolute https URL (http only for localhost) without query or fragment");
        }
    }

    public boolean enabled() { return enabled; }
    public boolean acceptSimulated() { return acceptSimulated; }
    public String merchantId() { return merchantId; }
    public String checkoutUrl() { return checkoutUrl; }
    public void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "綠界付款未啟用"); }

    /** MerchantTradeNo：20 碼英數字，不含易混淆字元；唯一性另由資料庫 UNIQUE 保證。 */
    public String newTradeNo() {
        var out = new StringBuilder("FG");
        for (int i = 0; i < 18; i++) out.append(TRADE_ALPHABET[random.nextInt(TRADE_ALPHABET.length)]);
        return out.toString();
    }

    /** 送往綠界的表單欄位；金額、交易編號、訂單編號都取伺服器快照。 */
    public Map<String, String> checkoutFields(String tradeNo, long amountTwd, String orderId) {
        requireEnabled();
        if (amountTwd < 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "訂單金額不可使用綠界付款");
        var fields = new LinkedHashMap<String, String>();
        fields.put("MerchantID", merchantId);
        fields.put("MerchantTradeNo", tradeNo);
        fields.put("MerchantTradeDate", ZonedDateTime.now(TAIPEI).format(TRADE_DATE));
        fields.put("PaymentType", PAYMENT_TYPE);
        fields.put("TotalAmount", Long.toString(amountTwd));
        fields.put("TradeDesc", "Figura order");
        fields.put("ItemName", "Figura 公仔訂單 " + orderId.substring(0, 8));
        fields.put("ReturnURL", returnUrl);
        fields.put("ChoosePayment", "Credit");
        fields.put("ClientBackURL", clientBackUrl + "?ecpay=" + orderId);
        fields.put("EncryptType", "1");
        fields.put("CheckMacValue", checkMac(fields));
        return Collections.unmodifiableMap(fields);
    }

    public String checkMac(Map<String, String> params) {
        var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        params.forEach((k, v) -> { if (!"CheckMacValue".equalsIgnoreCase(k)) sorted.put(k, v == null ? "" : v); });
        var source = new StringBuilder("HashKey=").append(hashKey);
        sorted.forEach((k, v) -> source.append('&').append(k).append('=').append(v));
        source.append("&HashIV=").append(hashIv);
        // Java URLEncoder 已不編碼 * - . _；再還原 .NET HttpUtility.UrlEncode 保留的 ! ( )。
        String encoded = URLEncoder.encode(source.toString(), StandardCharsets.UTF_8)
                .replace("%21", "!").replace("%28", "(").replace("%29", ")")
                .toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("ECPay CheckMacValue unavailable");
        }
    }

    /** 常數時間比對，避免以回應時間猜測檢查碼。 */
    public boolean verify(Map<String, String> params) {
        String received = params.get("CheckMacValue");
        if (received == null || !received.matches("[0-9A-Fa-f]{64}")) return false;
        return MessageDigest.isEqual(checkMac(params).getBytes(StandardCharsets.US_ASCII), received.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
