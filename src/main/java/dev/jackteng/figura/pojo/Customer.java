package dev.jackteng.figura.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
public class Customer {
    private String id;
    private String username;
    @JsonIgnore private String passwordHash;
    private String displayName;
    /** V19：小寫正規化後的信箱；舊帳號可為 null。 */
    private String email;
    /** V19：台灣手機，正規化為 09xxxxxxxx；舊帳號可為 null。展示用途，不驗證。 */
    private String phone;
    private LocalDateTime emailVerifiedAt;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
