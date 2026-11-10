package dev.jackteng.figura.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.jackteng.figura.pojo.Customer;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

public final class CustomerPayloads {
    private CustomerPayloads() {}
    public record Registration(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}", message="帳號需為 3–32 個英數或底線") String username,
            @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min=12,max=72,message="密碼至少 12 字元") String password,
            @NotBlank @Size(max=80) String displayName) {
        @AssertTrue(message="密碼過長") public boolean isPasswordLengthValid() { return withinBcryptLimit(password); }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        @Override public String toString() { return "CustomerRegistration[credentials hidden]"; }
    }
    public record Login(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String username,
                        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max=72) String password,
                        @NotBlank @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String deviceId,
                        boolean rememberMe) {
        @AssertTrue(message="密碼過長") public boolean isPasswordLengthValid() { return withinBcryptLimit(password); }
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
        @Override public String toString() { return "CustomerLogin[credentials hidden]"; }
    }
    public record Profile(@NotBlank @Size(max=80) String displayName) {
        @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支援的欄位"); }
    }
    public record View(String id, String username, String displayName, LocalDateTime createdAt) {
        public static View from(Customer customer) { return new View(customer.getId(),customer.getUsername(),customer.getDisplayName(),customer.getCreatedAt()); }
    }
    private static boolean withinBcryptLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
