package com.alphaledger.auth.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alphaledger.auth.domain.Role;

/**
 * Creates the four demo users (prd.md §3 personas) with fixed IDs 1-4, so other services' demo data can
 * refer to them (e.g. Priya, ID 2, manages the demo portfolios). Local and demo environments only:
 * enabled by {@code alphaledger.seed.demo-users.enabled}. Existing users are left untouched.
 */
@Component
@ConditionalOnProperty(name = "alphaledger.seed.demo-users.enabled", havingValue = "true")
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser(1, "anita.admin@alphaledger.test", "Anita (Admin)", Role.ADMIN),
            new DemoUser(2, "priya.pm@alphaledger.test", "Priya (Portfolio Manager)", Role.PM),
            new DemoUser(3, "rohan.ops@alphaledger.test", "Rohan (Operations Analyst)", Role.OPS_ANALYST),
            new DemoUser(4, "vikram.auditor@alphaledger.test", "Vikram (Auditor)", Role.AUDITOR));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String password;

    public DemoUserSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                          @Value("${alphaledger.seed.demo-users.password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "alphaledger.seed.demo-users.password must be set when demo users are enabled");
        }
        int created = 0;
        for (DemoUser user : DEMO_USERS) {
            created += jdbc.update("""
                    INSERT INTO app_user (id, email, password_hash, full_name, role, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    ON CONFLICT DO NOTHING
                    """, user.id(), user.email(), passwordEncoder.encode(password), user.fullName(),
                    user.role().name());
        }
        log.info("Demo users: {} created, {} already present", created, DEMO_USERS.size() - created);
    }

    record DemoUser(long id, String email, String fullName, Role role) {
    }
}
