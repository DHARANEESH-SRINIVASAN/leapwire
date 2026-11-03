package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({WebMvcConfigurer.class, JsonMapper.class, WebServerInitializedEvent.class})
@ConditionalOnBooleanProperty(name = "leapwire.enabled", matchIfMissing = true)
@EnableConfigurationProperties(LeapwireProperties.class)
public class LeapwireAutoConfiguration {

    static final int DEFAULT_SECURITY_FILTER_ORDER = -100;

    @Bean
    Learner leapwireLearner(LeapwireProperties properties) {
        return new Learner(properties, System::currentTimeMillis);
    }

    @Bean(destroyMethod = "close")
    Speculator leapwireSpeculator(Environment environment, LeapwireProperties properties) {
        return new Speculator(environment, properties);
    }

    @Bean
    FilterRegistrationBean<LeapwireFilter> leapwireFilter(Learner learner, Speculator speculator,
                                                          LeapwireProperties properties, Environment environment) {
        Bundler bundler = new Bundler(learner, speculator, properties);
        FilterRegistrationBean<LeapwireFilter> registration =
                new FilterRegistrationBean<>(new LeapwireFilter(learner, speculator, bundler, properties));
        registration.setName("leapwireFilter");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        int security = environment.getProperty("spring.security.filter.order", Integer.class,
                DEFAULT_SECURITY_FILTER_ORDER);
        registration.setOrder((int) Math.min(Integer.MAX_VALUE, Math.max(0L, security + 1L)));
        return registration;
    }

    @Bean
    WebMvcConfigurer leapwireWebMvcConfigurer(Learner learner) {
        LeapwireInterceptor interceptor = new LeapwireInterceptor(learner);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
