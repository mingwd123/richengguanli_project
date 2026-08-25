package com.dayliane.admin;

import com.dayliane.auth.RegistrationSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AdminRegistrationSettingsService {
    private final AdminService adminService;
    private final RegistrationSettingsService registrationSettingsService;

    public AdminRegistrationSettingsService(AdminService adminService,
                                            RegistrationSettingsService registrationSettingsService) {
        this.adminService = adminService;
        this.registrationSettingsService = registrationSettingsService;
    }

    @Transactional
    public Map<String, Object> update(long adminId, boolean registrationEnabled,
                                      String ipAddress, String userAgent) {
        adminService.requireSuperAdmin(adminId);
        Map<String, Object> before = registrationSettingsService.current();
        if ("database".equals(before.get("source"))
                && registrationEnabled == Boolean.TRUE.equals(before.get("registrationEnabled"))) {
            return before;
        }

        Map<String, Object> after = registrationSettingsService.update(registrationEnabled, adminId);
        adminService.writeAdminOperationLog(adminId, "set_registration_enabled", "registration_settings", 1L,
                before, after, ipAddress, userAgent);
        return after;
    }
}
