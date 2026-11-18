package dev.jackteng.figura.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 出貨單狀態推導（純邏輯，不需資料庫）。 */
class ShipmentStatusTest {
    private static ShipmentReader.Member m(String status, String fulfillment, boolean ready, String waiting, boolean flagged, int fee) {
        boolean closed = List.of("CANCELLED", "EXPIRED", "REFUNDED").contains(status);
        return new ShipmentReader.Member("STOCK", java.util.UUID.randomUUID().toString(), "x", status, fulfillment, closed, ready, waiting, 1, flagged, fee);
    }
    private static String[] s(ShipmentReader.Member... ms) { return ShipmentReader.status(List.of(ms)); }

    @Test void refundAfterShippingKeepsHistoryInsteadOfCancelled() {
        assertEquals("DELIVERED", s(m("REFUNDED", "DELIVERED", false, null, false, 120))[0]);
        assertEquals("SHIPPED", s(m("REFUNDED", "SHIPPED", false, null, false, 0), m("PAID", "SHIPPED", false, null, false, 0))[0]);
        assertEquals("CANCELLED", s(m("CANCELLED", "UNFULFILLED", false, null, false, 120))[0], "出貨前就結束才是取消");
        // 合併出貨後現貨退貨退款、其餘已送達：整張已送達（不會卡在已出貨）
        assertEquals("DELIVERED", s(m("REFUNDED", "SHIPPED", false, null, false, 120), m("PAID", "DELIVERED", false, null, false, 0))[0]);
        assertEquals("SHIPPED", s(m("REFUNDED", "SHIPPED", false, null, false, 0))[0], "只剩出貨後退款的訂單：保留已出貨歷史");
    }
    @Test void carrierClosedBeforeShippingWaitsForFeeDecision() {
        var carrier = m("CANCELLED", "UNFULFILLED", false, null, false, 120); var rest = m("PAID", "UNFULFILLED", true, null, false, 0);
        assertArrayEquals(new String[]{"WAITING", "FEE"}, s(carrier, rest));
        assertTrue(ShipmentReader.unpaidFee(List.of(carrier, rest))); assertTrue(ShipmentReader.allReady(List.of(carrier, rest)));
        // 免運（承運單運費 0）的訂單取消不影響
        assertEquals("READY", s(m("EXPIRED", "UNFULFILLED", false, null, false, 0), rest)[0]);
    }
    @Test void waitingReasonsFollowPriority() {
        assertArrayEquals(new String[]{"WAITING", "REVIEW"}, s(m("PAID", "UNFULFILLED", false, "REVIEW", true, 0), m("PENDING_PAYMENT", "UNFULFILLED", false, "PAYMENT", false, 0)));
        assertArrayEquals(new String[]{"WAITING", "PAYMENT"}, s(m("PAID", "UNFULFILLED", true, null, false, 0), m("PENDING_PAYMENT", "UNFULFILLED", false, "PAYMENT", false, 0)));
    }
}
