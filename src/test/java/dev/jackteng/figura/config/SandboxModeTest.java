package dev.jackteng.figura.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 沙盒模式設定：預設關閉；正式環境誤設沙盒變數或與正式金流同時開啟都必須拒絕啟動。 */
class SandboxModeTest {
    private static SandboxMode m(String mode, String exempt, String demo, String ttl, String max, String hour, String live) {
        return new SandboxMode(mode, exempt, demo, ttl, max, hour, live);
    }

    @Test void defaultsAreOff() {
        var s = m("", "", "", "", "", "", "false");
        assertFalse(s.enabled()); assertFalse(s.demoEnabled()); assertFalse(s.quickDemoEnabled());
        assertFalse(s.isExempt("shop_admin")); assertEquals(0, s.exemptCount());
        assertFalse(m("false", "", "false", "", "", "", "false").enabled(), "明確 false 也是關閉");
    }

    @Test void anySandboxVariableWithoutModeRefusesToStart() {
        assertThrows(IllegalStateException.class, () -> m("", "shop_admin", "", "", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("false", "shop_admin", "", "", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("", "", "true", "", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("", "", "", "30", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("", "", "", "", "10", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("", "", "", "", "", "20", "false"));
        assertThrows(IllegalStateException.class, () -> m("", "", "TRUE", "", "", "", "false"), "無效值一律拒絕");
        // 與預設值相同的設定不改變任何行為
        assertFalse(m("", "", "false", "60", "50", "200", "false").enabled());
    }

    @Test void sandboxWithLivePaymentsRefusesToStart() {
        assertThrows(IllegalStateException.class, () -> m("true", "", "", "", "", "", "true"));
        assertThrows(IllegalStateException.class, () -> m("true", "shop_admin", "true", "", "", "", "true"));
    }

    @Test void onlyLiteralTrueOrFalse() {
        for (String bad : new String[]{"TRUE", "yes", "1", "on"}) {
            assertThrows(IllegalStateException.class, () -> m(bad, "", "", "", "", "", "false"), bad);
            assertThrows(IllegalStateException.class, () -> m("true", "", bad, "", "", "", "false"), bad);
        }
    }

    @Test void exemptUsernamesMatchExactly() {
        var s = m("true", "shop_admin, shop_staff", "", "", "", "", "false");
        assertTrue(s.enabled()); assertTrue(s.quickDemoEnabled()); assertEquals(2, s.exemptCount());
        assertTrue(s.isExempt("shop_admin")); assertTrue(s.isExempt("shop_staff"));
        for (String variant : new String[]{"Shop_admin", "SHOP_ADMIN", " shop_admin", "shop_admin ", "shop_admin\t", "shop_admin​", "shop", null})
            assertFalse(s.isExempt(variant), "變形不豁免：" + variant);
        assertFalse(s.demoEnabled(), "MFA 展示需另外開啟");
    }

    @Test void rejectsInvalidValues() {
        assertThrows(IllegalStateException.class, () -> m("true", "mfa-demo-abc", "", "", "", "", "false"), "共用帳號不可使用臨時帳號前綴");
        assertThrows(IllegalStateException.class, () -> m("true", "bad name", "", "", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("true", "", "true", "0", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("true", "", "true", "abc", "", "", "false"));
        assertThrows(IllegalStateException.class, () -> m("true", "", "true", "", "0", "", "false"));
        var s = m("true", "", "true", "30", "5", "10", "false");
        assertTrue(s.demoEnabled()); assertEquals(30, s.demoTtlMinutes()); assertEquals(5, s.demoMaxActive()); assertEquals(10, s.demoCreatePerHour());
        var d = m("true", "", "true", "", "", "", "false");
        assertEquals(60, d.demoTtlMinutes()); assertEquals(50, d.demoMaxActive()); assertEquals(200, d.demoCreatePerHour());
    }
}
