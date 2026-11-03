package io.github.dharaneeshsrinivasan.leapwire.spring;

import io.github.dharaneeshsrinivasan.leapwire.LeapwireSkip;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

final class LeapwireInterceptor implements HandlerInterceptor {

    private final Learner learner;

    LeapwireInterceptor(Learner learner) {
        this.learner = learner;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object exchange = request.getAttribute(Exchange.ATTRIBUTE);
        if (exchange instanceof Exchange current) {
            if (request.getDispatcherType() == DispatcherType.REQUEST && current.endpoint() == null) {
                current.matched(request, skipped(handler));
                if (current.accept() && current.endpoint() != null && !current.skip()
                        && learner.hasActiveRules(current.route(), current.endpoint())) {
                    current.capture();
                }
            }
            return true;
        }
        if (request.getAttribute(Exchange.SPECULATIVE) != null && skipped(handler)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return false;
        }
        return true;
    }

    static boolean skipped(Object handler) {
        return handler instanceof HandlerMethod method && (method.hasMethodAnnotation(LeapwireSkip.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), LeapwireSkip.class));
    }
}
