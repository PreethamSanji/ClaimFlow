package com.claimflow.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Checks that YAML keys bind to the record and that bad values fail at startup. */
class FraudPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FraudProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void bindsKebabCaseProperties() {
        runner.withPropertyValues(
                        "claimflow.fraud.early-claim-days=10",
                        "claimflow.fraud.frequency-max-claims=5",
                        "claimflow.fraud.frequency-window-days=60",
                        "claimflow.fraud.late-reporting-days=45")
                .run(context -> {
                    FraudProperties properties = context.getBean(FraudProperties.class);
                    assertThat(properties.earlyClaimDays()).isEqualTo(10);
                    assertThat(properties.frequencyMaxClaims()).isEqualTo(5);
                    assertThat(properties.frequencyWindowDays()).isEqualTo(60);
                    assertThat(properties.lateReportingDays()).isEqualTo(45);
                });
    }

    @Test
    void zeroThresholdFailsStartup() {
        runner.withPropertyValues(
                        "claimflow.fraud.early-claim-days=0",
                        "claimflow.fraud.frequency-max-claims=3",
                        "claimflow.fraud.frequency-window-days=30",
                        "claimflow.fraud.late-reporting-days=30")
                .run(context -> assertThat(context).hasFailed());
    }
}
