package com.dayliane.auth;

import com.dayliane.auth.email.EmailProperties;
import com.dayliane.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RegistrationSettingsService {
    private static final int SETTINGS_ID = 1;

    private final JdbcTemplate jdbc;
    private final EmailProperties emailProperties;

    public RegistrationSettingsService(JdbcTemplate jdbc, EmailProperties emailProperties) {
        this.jdbc = jdbc;
        this.emailProperties = emailProperties;
    }

    public boolean isRegistrationEnabled() {
        List<Boolean> configured = jdbc.query(
                "select registration_enabled from registration_setting where id=?",
                (rs, index) -> rs.getBoolean("registration_enabled"), SETTINGS_ID);
        return configured.isEmpty() ? emailProperties.isRegistrationEnabled() : configured.get(0);
    }

    public void requireRegistrationEnabled() {
        if (!isRegistrationEnabled()) {
            throw new BusinessException(503, "registration unavailable");
        }
    }

    public Map<String, Object> current() {
        List<Map<String, Object>> configured = jdbc.query(
                "select registration_enabled,updated_at from registration_setting where id=?",
                (rs, index) -> settingsView(
                        rs.getBoolean("registration_enabled"),
                        "database",
                        rs.getTimestamp("updated_at")),
                SETTINGS_ID);
        if (!configured.isEmpty()) return configured.get(0);
        return settingsView(emailProperties.isRegistrationEnabled(), "environment", null);
    }

    public Map<String, Object> update(boolean registrationEnabled, long updatedBy) {
        int updated = jdbc.update(
                "update registration_setting set registration_enabled=?,updated_by=?,updated_at=utc_timestamp() where id=?",
                registrationEnabled, updatedBy, SETTINGS_ID);
        if (updated == 0) {
            try {
                jdbc.update("insert into registration_setting (id,registration_enabled,updated_by) values (?,?,?)",
                        SETTINGS_ID, registrationEnabled, updatedBy);
            } catch (DuplicateKeyException ex) {
                jdbc.update(
                        "update registration_setting set registration_enabled=?,updated_by=?,updated_at=utc_timestamp() where id=?",
                        registrationEnabled, updatedBy, SETTINGS_ID);
            }
        }
        if (!registrationEnabled) {
            jdbc.update("update auth_email_otp set status='invalidated',invalidated_at=utc_timestamp(),updated_at=utc_timestamp() "
                    + "where purpose='register' and status='issued'");
        }
        return current();
    }

    private static Map<String, Object> settingsView(boolean enabled, String source, Timestamp updatedAt) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("registrationEnabled", enabled);
        view.put("source", source);
        view.put("updatedAt", updatedAt == null
                ? ""
                : OffsetDateTime.ofInstant(updatedAt.toInstant(), ZoneOffset.UTC).toString());
        return view;
    }
}
