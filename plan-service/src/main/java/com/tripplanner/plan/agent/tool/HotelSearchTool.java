package com.tripplanner.plan.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 酒店查询工具
 * 禁止把北京酒店当作其他城市结果返回
 */
@Component
public class HotelSearchTool {

    @Tool("查询指定城市的酒店信息，返回酒店名称、价格、位置、评分等")
    public String searchHotels(String city, double minPrice, double maxPrice, int stars) {
        if (city == null || city.isBlank()) {
            return "{\"success\":false,\"error\":\"城市不能为空\",\"hotels\":[],\"total\":0}";
        }
        String normalized = city.replace("市", "");
        return """
            {
                "success": true,
                "city": "%s",
                "priceRange": {"min": %.2f, "max": %.2f},
                "stars": %d,
                "hotels": [],
                "total": 0,
                "note": "禁止编造他城酒店；请结合行程城市查询"
            }
            """.formatted(normalized, minPrice, maxPrice, stars);
    }

    @Tool("查询酒店详细信息，包括设施、入住时间、取消政策等")
    public String getHotelDetail(String hotelName) {
        return """
            {
                "name": "%s",
                "checkInTime": "14:00",
                "checkOutTime": "12:00",
                "facilities": ["免费WiFi", "健身房", "游泳池", "停车场"],
                "cancellationPolicy": "入住前24小时可免费取消"
            }
            """.formatted(hotelName);
    }
}
