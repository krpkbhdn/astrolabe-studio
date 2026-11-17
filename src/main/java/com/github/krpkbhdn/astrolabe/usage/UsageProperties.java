package com.github.krpkbhdn.astrolabe.usage;

import static com.github.krpkbhdn.astrolabe.config.PropertyRules.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Usage history tunables: raw sample retention and the default alert thresholds (0 turns an alert off), and how often an unchanged rate limit is sampled. */
@ConfigurationProperties("astrolabe.usage")
public record UsageProperties(
        @DefaultValue("90d") Duration rawRetention,
        @DefaultValue("6h") Duration retentionInterval,
        @DefaultValue("80") int fiveHourPercent,
        @DefaultValue("80") int sevenDayPercent,
        @DefaultValue("0") double dailyBudgetUsd,
        @DefaultValue("5m") Duration rateLimitSampleInterval) {

    public UsageProperties {
        positive("astrolabe.usage.raw-retention", rawRetention);
        positive("astrolabe.usage.retention-interval", retentionInterval);
        inRange("astrolabe.usage.five-hour-percent", fiveHourPercent, 0, 100);
        inRange("astrolabe.usage.seven-day-percent", sevenDayPercent, 0, 100);
        nonNegative("astrolabe.usage.daily-budget-usd", dailyBudgetUsd);
        positive("astrolabe.usage.rate-limit-sample-interval", rateLimitSampleInterval);
    }
}
