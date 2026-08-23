package com.dayliane.auth.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {
    private boolean registrationEnabled = true;
    private String otpSecret = "";
    private Duration otpTtl = Duration.ofMinutes(5);
    private Duration resendCooldown = Duration.ofSeconds(60);
    private int maxAttempts = 5;
    private int maxPerEmailPerDay = 10;
    private int maxPerIpPerHour = 30;
    private TencentSes tencentSes = new TencentSes();

    public boolean isRegistrationEnabled() { return registrationEnabled; }
    public void setRegistrationEnabled(boolean registrationEnabled) { this.registrationEnabled = registrationEnabled; }
    public String getOtpSecret() { return otpSecret; }
    public void setOtpSecret(String otpSecret) { this.otpSecret = otpSecret; }
    public Duration getOtpTtl() { return otpTtl; }
    public void setOtpTtl(Duration otpTtl) { this.otpTtl = otpTtl; }
    public Duration getResendCooldown() { return resendCooldown; }
    public void setResendCooldown(Duration resendCooldown) { this.resendCooldown = resendCooldown; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getMaxPerEmailPerDay() { return maxPerEmailPerDay; }
    public void setMaxPerEmailPerDay(int maxPerEmailPerDay) { this.maxPerEmailPerDay = maxPerEmailPerDay; }
    public int getMaxPerIpPerHour() { return maxPerIpPerHour; }
    public void setMaxPerIpPerHour(int maxPerIpPerHour) { this.maxPerIpPerHour = maxPerIpPerHour; }
    public TencentSes getTencentSes() { return tencentSes; }
    public void setTencentSes(TencentSes tencentSes) { this.tencentSes = tencentSes; }

    public static class TencentSes {
        private boolean enabled;
        private String region = "";
        private String secretId = "";
        private String secretKey = "";
        private String fromEmail = "";
        private String templateId = "";
        private String replyTo = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getSecretId() { return secretId; }
        public void setSecretId(String secretId) { this.secretId = secretId; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getFromEmail() { return fromEmail; }
        public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getReplyTo() { return replyTo; }
        public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    }
}
