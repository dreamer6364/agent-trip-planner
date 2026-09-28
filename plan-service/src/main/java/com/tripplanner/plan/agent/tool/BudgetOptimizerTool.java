package com.tripplanner.plan.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 预算优化工具
 * 根据预算约束优化行程安排
 */
@Component
public class BudgetOptimizerTool {

    @Tool("根据总预算和行程天数，计算每日预算分配建议")
    public String optimizeBudget(double totalBudget, int days, String travelStyle) {
        double dailyBudget = totalBudget / days;
        double accommodationRatio = 0.35;
        double foodRatio = 0.25;
        double transportRatio = 0.20;
        double attractionRatio = 0.15;
        double otherRatio = 0.05;
        
        if ("豪华".equals(travelStyle)) {
            accommodationRatio = 0.45;
            foodRatio = 0.25;
            transportRatio = 0.15;
            attractionRatio = 0.10;
            otherRatio = 0.05;
        } else if ("经济".equals(travelStyle)) {
            accommodationRatio = 0.25;
            foodRatio = 0.30;
            transportRatio = 0.25;
            attractionRatio = 0.15;
            otherRatio = 0.05;
        }
        
        return """
            {
                "totalBudget": %.2f,
                "days": %d,
                "travelStyle": "%s",
                "dailyBudget": %.2f,
                "allocation": {
                    "accommodation": %.2f,
                    "food": %.2f,
                    "transport": %.2f,
                    "attractions": %.2f,
                    "other": %.2f
                },
                "suggestions": [
                    "提前预订酒店可享受折扣",
                    "选择当地特色小吃比餐厅更实惠",
                    "购买景点联票可节省费用"
                ]
            }
            """.formatted(totalBudget, days, travelStyle, dailyBudget,
                dailyBudget * accommodationRatio, dailyBudget * foodRatio,
                dailyBudget * transportRatio, dailyBudget * attractionRatio,
                dailyBudget * otherRatio);
    }

    @Tool("计算行程总费用，包括交通、住宿、餐饮、门票等")
    public String calculateTotalCost(
            double transportCost,
            double accommodationCost,
            double foodCost,
            double attractionCost,
            double otherCost) {
        double total = transportCost + accommodationCost + foodCost + attractionCost + otherCost;
        return """
            {
                "breakdown": {
                    "transport": %.2f,
                    "accommodation": %.2f,
                    "food": %.2f,
                    "attractions": %.2f,
                    "other": %.2f
                },
                "total": %.2f
            }
            """.formatted(transportCost, accommodationCost, foodCost, attractionCost, otherCost, total);
    }
}
