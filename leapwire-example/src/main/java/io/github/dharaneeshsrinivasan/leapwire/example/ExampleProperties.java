package io.github.dharaneeshsrinivasan.leapwire.example;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("example")
public record ExampleProperties(@DefaultValue("0ms") Duration delay) {
}
