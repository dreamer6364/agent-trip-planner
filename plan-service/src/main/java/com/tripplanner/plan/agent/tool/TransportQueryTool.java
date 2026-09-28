package com.tripplanner.plan.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 交通查询工具
 * 查询航班、火车、公交等交通信息和价格
 */
@Component
public class TransportQueryTool {

    @Tool("查询两地之间的交通方式，返回航班、火车、大巴等选项及价格")
    public String queryTransport(String from, String to, String date) {
        return """
            {
                "from": "%s",
                "to": "%s",
                "date": "%s",
                "options": [
                    {
                        "type": "飞机",
                        "departure": "08:00",
                        "arrival": "10:30",
                        "duration": "2小时30分",
                        "price": 1200,
                        "airline": "中国国航"
                    },
                    {
                        "type": "高铁",
                        "departure": "09:00",
                        "arrival": "13:00",
                        "duration": "4小时",
                        "price": 553,
                        "train": "G123"
                    },
                    {
                        "type": "大巴",
                        "departure": "07:00",
                        "arrival": "15:00",
                        "duration": "8小时",
                        "price": 200,
                        "company": "长途客运"
                    }
                ]
            }
            """.formatted(from, to, date);
    }

    @Tool("查询城市内交通方式，返回地铁、公交、打车等选项及价格")
    public String queryLocalTransport(String city, String from, String to) {
        return """
            {
                "city": "%s",
                "from": "%s",
                "to": "%s",
                "options": [
                    {
                        "type": "地铁",
                        "duration": "30分钟",
                        "price": 5,
                        "route": "1号线 → 2号线"
                    },
                    {
                        "type": "公交",
                        "duration": "45分钟",
                        "price": 2,
                        "route": "101路"
                    },
                    {
                        "type": "打车",
                        "duration": "20分钟",
                        "price": 35,
                        "estimate": "约15公里"
                    }
                ]
            }
            """.formatted(city, from, to);
    }
}
