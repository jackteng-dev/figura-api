package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.jackteng.figura.pojo.Customer;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CustomerPayloads {
    private CustomerPayloads() {}
    /** 一般信箱格式（限 ASCII，存入前轉小寫）。 */
    public static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,62}[A-Za-z0-9])?)+");

    /** 台灣手機正規化為 09xxxxxxxx；接受 0912345678、0912-345-678、+886912345678、886 912 345 678、912345678。不合法回傳 null。 */
    public static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+886")) digits = digits.substring(4);
        else if (digits.startsWith("886") && digits.length() == 12) digits = digits.substring(3);
        if (digits.matches("9\\d{8}")) digits = "0" + digits;
        return digits.matches("09\\d{8}") ? digits : null;
    }
    public static String normalizeEmail(String raw) {
        if (raw == null) return null;
        String e = raw.strip();
        return e.length() <= 254 && EMAIL.matcher(e).matches() ? e.toLowerCase(Locale.ROOT) : null;
    }

    /** 註冊：手機＋Email＋密碼（不需暱稱）。舊版「帳號名稱＋暱稱」已停用，送出 username／displayName 會被拒絕。 */
    public record Registration(
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=12,max=72,message="密碼至少 12 字元") String password,
            @NotBlank(message="請填寫 Email") @Size(max=254) String email,
            @NotBlank(message="請填寫手機號碼") @Size(max=32) String phone) {
        @AssertTrue(message="密碼過長") public boolean isPasswordLengthValid() { return withinBcryptLimit(password); }
        @AssertTrue(message="Email 格式不正確") public boolean isEmailValid() { return email == null || normalizeEmail(email) != null; }
        @AssertTrue(message="手機號碼格式不正確，請輸入 09 開頭的 10 碼手機") public boolean isPhoneValid() { return phone == null || normalizePhone(phone) != null; }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        @Override public String toString() { return "CustomerRegistration[credentials hidden]"; }
    }
    /** 登入：username 欄位填手機或 Email（欄位名稱沿用以相容 App）；帳號名稱登入已停用。 */
    public record Login(@NotBlank @Size(max=254) String username,
                        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max=72) String password,
                        @NotBlank @jakarta.validation.constraints.Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String deviceId,
                        boolean rememberMe) {
        @AssertTrue(message="密碼過長") public boolean isPasswordLengthValid() { return withinBcryptLimit(password); }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        @Override public String toString() { return "CustomerLogin[credentials hidden]"; }
    }
    public record VerifyEmail(@NotBlank @Size(max=128) String token) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        @Override public String toString() { return "VerifyEmail[token hidden]"; }
    }
    public record ForgotPassword(@NotBlank @Size(max=254) String email) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record ResetPassword(@NotBlank @Size(max=128) String token,
                                @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=12,max=72,message="密碼至少 12 字元") String password) {
        @AssertTrue(message="密碼過長") public boolean isPasswordLengthValid() { return withinBcryptLimit(password); }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        @Override public String toString() { return "ResetPassword[credentials hidden]"; }
    }
    /** 本人資料：只回傳會員 ID、Email、手機與驗證狀態。系統內部的 username／display_name 欄位不對外。 */
    public record View(String id, LocalDateTime createdAt, String email, String phone, boolean emailVerified) {
        public static View from(Customer c) {
            return new View(c.getId(), c.getCreatedAt(), c.getEmail(), c.getPhone(), c.getEmailVerifiedAt() != null);
        }
    }
    private static boolean withinBcryptLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
