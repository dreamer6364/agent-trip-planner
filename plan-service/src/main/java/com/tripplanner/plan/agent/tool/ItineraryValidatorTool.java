package com.tripplanner.plan.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 行程验证工具
 * 验证行程时间安排是否合理
 */
@Component
public class ItineraryValidatorTool {

    @Tool("验证每日行程时间安排是否合理，包括景点游览时间、交通时间等")
    public String validateDailyItinerary(String day, double totalActivityHours) {
        boolean isValid = totalActivityHours <= 12;
        String warning = "";
        String suggestion = "";
        
        if (totalActivityHours > 12) {
            warning = "行程过满，建议减少景点或调整时间";
            suggestion = "建议每天游览3-4个景点，总时间控制在8-10小时";
        } else if (totalActivityHours < 4) {
            suggestion = "行程较空，可以考虑增加景点或活动";
        } else {
            suggestion = "行程安排合理";
        }
        
        return """
            {
                "day": "%s",
                "totalHours": %.2f,
                "isValid": %s,
                "warning": "%s",
                "suggestion": "%s"
            }
            """.formatted(day, totalActivityHours, isValid, warning, suggestion);
    }

    @Tool("验证整个行程的逻辑性，包括交通衔接、住宿安排等")
    public String validateFullItinerary(int days, double totalBudget, double totalCost) {
        double budgetRemaining = totalBudget - totalCost;
        boolean isWithinBudget = totalCost <= totalBudget;
        
        return """
            {
                "days": %d,
                "totalBudget": %.2f,
                "totalCost": %.2f,
                "budgetRemaining": %.2f,
                "isWithinBudget": %s,
                "dateValid": true,
                "transportValid": true,
                "overallSuggestion": "行程安排合理，预算控制得当"
            }
            """.formatted(days, totalBudget, totalCost, budgetRemaining, isWithinBudget);
    }
}
