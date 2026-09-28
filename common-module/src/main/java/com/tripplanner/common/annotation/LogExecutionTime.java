package com.tripplanner.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法执行时间日志注解
 * 用于性能监控，记录方法执行耗时
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExecutionTime {

    /**
     * 自定义标识，用于区分同一方法的不同调用场景
     */
    String value() default "";

    /**
     * 是否记录入参（注意敏感数据脱敏）
     */
    boolean logArgs() default false;

    /**
     * 是否记录返回值
     */
    boolean logResult() default false;

    /**
     * 慢查询阈值（毫秒），超过该值记录 WARN 级别日志
     */
    long slowThresholdMs() default 1000;
}