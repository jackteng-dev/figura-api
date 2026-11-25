package dev.jackteng.figura.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class User {
    @NotNull
    private Integer id;
    @JsonIgnore private int authVersion;
    private String username;
    @JsonIgnore //讓springmvc將當前對象轉換成json時，忽略password
    private String password;
    private String firstName;
    private String lastName;
    @Email
    private String email;
    private String telephone;
    private String userPic;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdTime;
    private LocalDateTime modifiedTime;
    private String isStop;
    private List<Role> role;
    private List<Permission> permissions;
    /** 雙因素：密碼正確但尚未驗證時為 true，並回傳 challenge（此時不發 session）。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private Boolean mfaRequired;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private String mfaChallenge;
    /** 強制雙因素：尚未綁定的員工密碼正確後回傳綁定 token，完成綁定才發 session。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private Boolean mfaSetupRequired;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private String mfaSetupToken;
    /** 只在完成綁定的那一次回傳的一次性備援碼。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private java.util.List<String> backupCodes;
    /** 此員工是否必須保存備援碼（具 access.mfa.reset）；與 backupCodes 同次回傳。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private Boolean backupCodesRequired;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private java.time.OffsetDateTime backupCodesGeneratedAt;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private String backupBatchId;
    /** 尚未完成備援碼保存確認：業務 API 會回 403（MFA_BACKUP_ACK_REQUIRED）。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) private Boolean mfaBackupAckPending;
}
