package com.dayliane.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@DependsOnDatabaseInitialization
public class DemoAccountInitializer implements InitializingBean {
    private static final String DISABLED_HASH = "$2a$12$xNRMbC1E8lvXJwYYUNjLSeNaoSHRKb/mvvOCo8hF/82OR.u1wfc/K";
    private static final String DEMO_USER_HASH = "$2a$12$/GpQmozMPlF4jTAT9QsOc.et6aUGJvLLNSemiLZMBHiQ5Eym28.Xu";
    private static final String DEMO_ADMIN_HASH = "$2a$12$pTFfqO4OID.McS/OoNIxGuIQtCjITNggs4xn1EB9S2d9HRliS9lne";

    private final JdbcTemplate jdbc;
    private final boolean enableDemoAccounts;
    private final TransactionTemplate transactionTemplate;

    public DemoAccountInitializer(JdbcTemplate jdbc, Environment environment,
                                  @Value("${app.demo-data.enabled:false}") boolean demoDataEnabled,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        boolean development = environment.acceptsProfiles(Profiles.of("dev"));
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        this.enableDemoAccounts = demoDataEnabled && development && !production;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void afterPropertiesSet() {
        transactionTemplate.executeWithoutResult(status -> reconcileDemoAccounts());
    }

    private void reconcileDemoAccounts() {
        if (enableDemoAccounts) {
            activateDemoAccounts();
        } else {
            hardenDemoAccounts();
        }
    }

    private void activateDemoAccounts() {
        jdbc.update("delete from auth_refresh_token where user_id in (select id from `user` " +
                        "where phone in ('13800138000','13900139000','13900139001','13900139002','13900139003') " +
                        "and password_hash in (?,?))",
                DISABLED_HASH, "Abc12345");
        jdbc.update("update `user` set password_hash=?,status='active',token_version=token_version+1 " +
                        "where phone in ('13800138000','13900139000','13900139001','13900139002','13900139003') " +
                        "and password_hash in (?,?)",
                DEMO_USER_HASH, DISABLED_HASH, "Abc12345");
        jdbc.update("update admin_user set password_hash=?,status='active' " +
                        "where username='admin' and password_hash in (?,?)",
                DEMO_ADMIN_HASH, DISABLED_HASH, "Admin12345");
    }

    private void hardenDemoAccounts() {
        jdbc.update("delete from auth_refresh_token where user_id in (select id from `user` " +
                        "where phone in ('13800138000','13900139000','13900139001','13900139002','13900139003') " +
                        "and password_hash in (?,?,?))",
                "Abc12345", DEMO_USER_HASH, DISABLED_HASH);
        jdbc.update("update `user` set password_hash=?,status='disabled',token_version=token_version+1 " +
                        "where phone in ('13800138000','13900139000','13900139001','13900139002','13900139003') " +
                        "and password_hash in (?,?,?) and (password_hash<>? or status<>'disabled')",
                DISABLED_HASH, "Abc12345", DEMO_USER_HASH, DISABLED_HASH, DISABLED_HASH);
        jdbc.update("update admin_user set password_hash=?,status='disabled' " +
                        "where username='admin' and password_hash in (?,?,?) " +
                        "and (password_hash<>? or status<>'disabled')",
                DISABLED_HASH, "Admin12345", DEMO_ADMIN_HASH, DISABLED_HASH, DISABLED_HASH);
    }
}
