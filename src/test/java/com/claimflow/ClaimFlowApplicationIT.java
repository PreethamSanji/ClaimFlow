package com.claimflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClaimFlowApplicationIT {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndFlywayCreatesTables() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('customer', 'policy', 'claim', 'claim_event', 'claim_fraud_flag')
                """, Integer.class);

        assertThat(tables).isEqualTo(5);
    }
}
