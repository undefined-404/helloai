package com.helloai.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestParamContractTest {

    @Test
    void requestParamsWithDefaultValueDeclareExplicitNames() throws ClassNotFoundException {
        List<String> violations = new ArrayList<>();
        for (Class<?> controllerClass : findControllerClasses()) {
            for (Method method : controllerClass.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                    if (requestParam == null) {
                        continue;
                    }
                    if (ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue())) {
                        continue;
                    }
                    boolean hasExplicitName = StringUtils.hasText(requestParam.name()) || StringUtils.hasText(requestParam.value());
                    if (!hasExplicitName) {
                        violations.add(controllerClass.getSimpleName() + "#" + method.getName() + ":" + parameter.getType().getSimpleName());
                    }
                }
            }
        }

        assertThat(violations)
                .as("使用默认值的 @RequestParam 必须显式声明 value/name，避免缺少 -parameters 时运行时绑定失败")
                .isEmpty();
    }

    private List<Class<?>> findControllerClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.helloai.api.controller")) {
            classes.add(Class.forName(candidate.getBeanClassName()));
        }
        return classes;
    }
}
