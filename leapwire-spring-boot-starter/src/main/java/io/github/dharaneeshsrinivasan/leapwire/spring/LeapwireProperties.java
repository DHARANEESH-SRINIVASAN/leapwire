package io.github.dharaneeshsrinivasan.leapwire.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("leapwire")
public record LeapwireProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("10") int minPages,
                                 @DefaultValue("3") int minUsers,
                                 @DefaultValue("0.9") double minConfidence,
                                 @DefaultValue("8") int maxRequests,
                                 @DefaultValue("4") int maxDepth,
                                 @DefaultValue("2s") Duration deadline,
                                 @DefaultValue("30s") Duration pageTtl,
                                 @DefaultValue("64") int maxPageRequests,
                                 @DefaultValue("10000") int maxPages,
                                 @DefaultValue("1000000") long maxIndexedValues,
                                 @DefaultValue("262144") int maxBodyBytes) {

    public LeapwireProperties {
        require(minPages >= 1, "leapwire.min-pages must be at least 1");
        require(minUsers >= 1, "leapwire.min-users must be at least 1");
        require(minConfidence > 0 && minConfidence <= 1, "leapwire.min-confidence must be above 0 and at most 1");
        require(maxRequests >= 0, "leapwire.max-requests must not be negative");
        require(maxDepth >= 1, "leapwire.max-depth must be at least 1");
        require(deadline != null && !deadline.isNegative() && !deadline.isZero(), "leapwire.deadline must be positive");
        require(pageTtl != null && !pageTtl.isNegative() && !pageTtl.isZero(), "leapwire.page-ttl must be positive");
        require(maxPageRequests >= 1, "leapwire.max-page-requests must be at least 1");
        require(maxPages >= 1, "leapwire.max-pages must be at least 1");
        require(maxIndexedValues >= 1, "leapwire.max-indexed-values must be at least 1");
        require(maxBodyBytes >= 1 && maxBodyBytes < Integer.MAX_VALUE, "leapwire.max-body-bytes must be positive");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
