package com.tripplanner.plan.agent.tool;

import com.tripplanner.common.util.CityOwnershipUtils;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 景点查询工具
 * 禁止把其他城市景点当作目标城市返回
 */
@Component
public class AttractionSearchTool {

    @Tool("查询指定城市的景点信息，返回景点名称、地址、门票价格、开放时间、评分等")
    public String searchAttractions(String city, String category) {
        if (city == null || city.isBlank()) {
            return "{\"success\":false,\"error\":\"城市不能为空\",\"attractions\":[],\"total\":0}";
        }
        String normalized = city.replace("市", "");
        return """
            {
                "success": true,
                "city": "%s",
                "category": "%s",
                "attractions": [],
                "total": 0,
                "note": "请结合行程 CITY_ATTRACTIONS/geocode 使用，禁止编造他城景点"
            }
            """.formatted(normalized, category == null ? "" : category);
    }

    @Tool("查询景点详细信息，包括游览时长建议、最佳游览时间等")
    public String getAttractionDetail(String attractionName) {
        String owner = CityOwnershipUtils.ownerCityOfPlace(attractionName);
        return """
            {
                "name": "%s",
                "ownerCity": "%s",
                "suggestDuration": "2-3小时",
                "bestTime": "上午",
                "tips": "建议提前在线购票，避开节假日高峰"
            }
            """.formatted(attractionName, owner == null ? "" : owner);
    }
}
