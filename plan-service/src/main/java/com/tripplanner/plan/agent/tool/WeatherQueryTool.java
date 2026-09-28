package com.tripplanner.plan.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具
 * 查询目的地天气、气候信息
 */
@Component
public class WeatherQueryTool {

    @Tool("查询指定城市未来几天的天气预报")
    public String queryWeather(String city, int days) {
        return """
            {
                "city": "%s",
                "forecast": [
                    {
                        "date": "2024-01-15",
                        "weather": "晴",
                        "temperature": {"min": -5, "max": 5},
                        "humidity": 45,
                        "wind": "北风3级"
                    },
                    {
                        "date": "2024-01-16",
                        "weather": "多云",
                        "temperature": {"min": -3, "max": 7},
                        "humidity": 50,
                        "wind": "南风2级"
                    },
                    {
                        "date": "2024-01-17",
                        "weather": "小雪",
                        "temperature": {"min": -8, "max": 2},
                        "humidity": 70,
                        "wind": "北风4级"
                    }
                ],
                "suggestion": "近期有降雪，建议携带保暖衣物和防滑鞋"
            }
            """.formatted(city);
    }

    @Tool("查询指定城市的气候特征和最佳旅游季节")
    public String queryClimate(String city) {
        return """
            {
                "city": "%s",
                "climate": "温带季风气候",
                "bestSeason": "秋季（9-11月）",
                "characteristics": [
                    "四季分明",
                    "夏季炎热多雨",
                    "冬季寒冷干燥"
                ],
                "suggestion": "秋季天气宜人，是最佳旅游时间"
            }
            """.formatted(city);
    }
}
