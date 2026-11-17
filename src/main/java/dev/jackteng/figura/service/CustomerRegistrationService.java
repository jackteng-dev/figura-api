package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.CustomerPayloads;
import dev.jackteng.figura.pojo.Customer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 註冊與驗證信在同一交易：建立顧客、一次性驗證連結與模擬信件任一步失敗都整筆回滾，
 * 避免回 500 卻留下已占用 Email／手機的帳號（重試時變成「已被註冊」）。
 * 網站與 App 註冊共用。
 */
@Service
public class CustomerRegistrationService {
    private final CustomerSessionService sessions;
    private final CustomerEmailService emails;
    public CustomerRegistrationService(CustomerSessionService sessions, CustomerEmailService emails) { this.sessions = sessions; this.emails = emails; }

    @Transactional
    public Customer register(CustomerPayloads.Registration input) {
        Customer customer = sessions.register(input);
        emails.sendVerification(customer); // 舊版（無 Email）註冊不動作
        return customer;
    }
}
