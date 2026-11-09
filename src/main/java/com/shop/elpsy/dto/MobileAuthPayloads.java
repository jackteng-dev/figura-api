package com.shop.elpsy.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MobileAuthPayloads {
    private MobileAuthPayloads() {}
    public record Refresh(@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max=4096) String refreshToken) {
        @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("不支援的欄位");}
        @Override public String toString(){return "MobileRefresh[credentials hidden]";}
    }
    public record Tokens(String accessToken,String refreshToken,long expiresIn,CustomerPayloads.View customer) {
        @Override public String toString(){return "MobileTokens[credentials hidden]";}
    }
}
