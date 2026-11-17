package dev.jackteng.figura;

import java.security.SecureRandom;

/** 測試用台灣手機號碼（09＋8 碼隨機）；註冊只接受手機＋Email。 */
final class TestPhones {
    private static final SecureRandom RANDOM = new SecureRandom();
    private TestPhones() {}
    static String next() { return "09" + String.format("%08d", RANDOM.nextInt(100_000_000)); }
}
