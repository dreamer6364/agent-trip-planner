package com.tripplanner.common.util;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

/**
 * SpEL 表达式工具类
 * 用于解析 @RequirePermission 中的 resourceId 表达式
 */
public final class SpelUtils {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private SpelUtils() {}

    /**
     * 解析 SpEL 表达式获取值
     *
     * @param expression 表达式字符串，如 #tripId, #request.tripId, #id
     * @param rootObject 根对象（通常是方法参数或请求对象）
     * @param variables  额外变量映射
     * @return 解析结果
     */
    public static Object parse(String expression, Object rootObject, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        
        Expression exp = PARSER.parseExpression(expression);
        EvaluationContext context = new StandardEvaluationContext(rootObject);
        
        if (variables != null) {
            variables.forEach(context::setVariable);
        }
        
        return exp.getValue(context);
    }

    /**
     * 解析 SpEL 表达式获取字符串值
     */
    public static String parseString(String expression, Object rootObject, Map<String, Object> variables) {
        Object value = parse(expression, rootObject, variables);
        return value != null ? value.toString() : null;
    }
}