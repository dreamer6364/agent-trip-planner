package com.tripplanner.trip.service;

import com.tripplanner.common.exception.BizException;
import com.tripplanner.common.model.ActivityAlternative;
import com.tripplanner.common.response.ApiResponse;
import com.tripplanner.common.util.GeoUtils;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.client.PlanMapClient;
import com.tripplanner.trip.dto.request.GetAlternativesRequest;
import com.tripplanner.trip.dto.request.ReplaceActivityRequest;
import com.tripplanner.trip.dto.response.AlternativesResponse;
import com.tripplanner.trip.entity.Activity;
import com.tripplanner.trip.entity.Trip;
import com.tripplanner.trip.entity.TripVersion;
import com.tripplanner.trip.repository.ActivityRepository;
import com.tripplanner.trip.repository.TripRepository;
import com.tripplanner.trip.repository.TripVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 景点备选项服务
 * 提供景点推荐和替换功能；替换后按真实坐标/路网重算邻接段距离与时长
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlternativeService {

    private final TripRepository tripRepository;
    private final TripVersionRepository versionRepository;
    private final ActivityRepository activityRepository;
    private final JsonUtils jsonUtils;
    private final SloganService sloganService;
    private final PlanMapClient planMapClient;

    /** 热门景点静态坐标 [lat, lng]，地理编码失败时的兜底 */
    private static final Map<String, double[]> PLACE_COORDS = new HashMap<>();
    static {
        put("西湖", 30.2480, 120.1480);
        put("灵隐寺", 30.2410, 120.1010);
        put("河坊街", 30.2420, 120.1690);
        put("雷峰塔", 30.2320, 120.1490);
        put("龙井村", 30.2290, 120.1180);
        put("断桥", 30.2600, 120.1520);
        put("西溪湿地", 30.2680, 120.0700);
        put("宋城", 30.1990, 120.1050);
        put("九溪烟树", 30.2100, 120.1200);
        put("故宫", 39.9163, 116.3972);
        put("天安门广场", 39.9087, 116.3975);
        put("颐和园", 39.9990, 116.2755);
        put("天坛", 39.8822, 116.4066);
        put("南锣鼓巷", 39.9370, 116.4030);
        put("什刹海", 39.9400, 116.3830);
        put("圆明园", 40.0080, 116.3000);
        put("北海公园", 39.9250, 116.3890);
        put("雍和宫", 39.9470, 116.4170);
        put("外滩", 31.2400, 121.4900);
        put("东方明珠", 31.2397, 121.4998);
        put("豫园", 31.2270, 121.4920);
        put("南京路", 31.2340, 121.4740);
        put("宽窄巷子", 30.6690, 104.0550);
        put("锦里", 30.6420, 104.0440);
        put("武侯祠", 30.6440, 104.0470);
        put("大熊猫基地", 30.7350, 104.1460);
        put("杜甫草堂", 30.6590, 104.0300);
        put("春熙路", 30.6530, 104.0810);
        put("兵马俑", 34.3840, 109.2780);
        put("大雁塔", 34.2180, 108.9640);
        put("回民街", 34.2610, 108.9400);
        put("城墙", 34.2600, 108.9420);
        put("钟楼", 34.2610, 108.9420);
        put("鼓浪屿", 24.4450, 118.0650);
        put("中山陵", 32.0580, 118.8520);
        put("夫子庙", 32.0210, 118.7880);
        put("洪崖洞", 29.5660, 106.5790);
        put("解放碑", 29.5570, 106.5770);
        put("岳麓山", 28.1870, 112.9430);
        put("橘子洲", 28.1930, 112.9580);
        put("楼外楼", 30.2485, 120.1425);
        put("知味观", 30.2470, 120.1640);
        put("新白鹿", 30.2560, 120.1620);
        put("外婆家", 30.2430, 120.1570);
        put("全聚德", 39.9090, 116.4050);
        put("南京大牌档", 32.0400, 118.7900);
        put("弄堂里", 30.2450, 120.1700);
        put("杭州酒家", 30.2455, 120.1665);
        put("奎元馆", 30.2458, 120.1668);
        put("知味观·味庄", 30.2470, 120.1640);
    }

    private static void put(String name, double lat, double lng) {
        PLACE_COORDS.put(name, new double[]{lat, lng});
    }

    // 景点类型相似度映射
    private static final Map<String, List<String>> TYPE_SIMILARITY = Map.of(
            "scenic", List.of("scenic", "park", "temple"),
            "museum", List.of("museum", "exhibition"),
            "park", List.of("park", "scenic", "nature"),
            "temple", List.of("temple", "scenic", "culture"),
            "shopping", List.of("shopping", "market", "street"),
            "restaurant", List.of("restaurant", "food", "cafe")
    );

    // 城市热门景点数据库（用于推荐）
    private static final Map<String, List<Map<String, Object>>> CITY_ATTRACTIONS;
    static {
        CITY_ATTRACTIONS = new HashMap<>();
        CITY_ATTRACTIONS.put("北京", List.of(
                Map.of("name", "故宫", "type", "scenic", "duration", 240, "rating", 4.9),
                Map.of("name", "天安门广场", "type", "scenic", "duration", 60, "rating", 4.8),
                Map.of("name", "颐和园", "type", "scenic", "duration", 180, "rating", 4.8),
                Map.of("name", "天坛", "type", "temple", "duration", 120, "rating", 4.7),
                Map.of("name", "圆明园", "type", "scenic", "duration", 150, "rating", 4.6),
                Map.of("name", "南锣鼓巷", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "王府井", "type", "shopping", "duration", 120, "rating", 4.4),
                Map.of("name", "什刹海", "type", "scenic", "duration", 90, "rating", 4.6),
                Map.of("name", "鸟巢", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "水立方", "type", "scenic", "duration", 60, "rating", 4.4),
                Map.of("name", "北海公园", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "景山公园", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "雍和宫", "type", "temple", "duration", 90, "rating", 4.7),
                Map.of("name", "恭王府", "type", "scenic", "duration", 90, "rating", 4.6),
                Map.of("name", "798艺术区", "type", "museum", "duration", 120, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("杭州", List.of(
                Map.of("name", "西湖", "type", "scenic", "duration", 180, "rating", 4.9),
                Map.of("name", "灵隐寺", "type", "temple", "duration", 120, "rating", 4.8),
                Map.of("name", "雷峰塔", "type", "scenic", "duration", 90, "rating", 4.7),
                Map.of("name", "河坊街", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "龙井村", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "千岛湖", "type", "scenic", "duration", 300, "rating", 4.8),
                Map.of("name", "断桥", "type", "scenic", "duration", 30, "rating", 4.6),
                Map.of("name", "西溪湿地", "type", "scenic", "duration", 180, "rating", 4.7),
                Map.of("name", "宋城", "type", "scenic", "duration", 240, "rating", 4.6),
                Map.of("name", "九溪烟树", "type", "scenic", "duration", 90, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("上海", List.of(
                Map.of("name", "外滩", "type", "scenic", "duration", 90, "rating", 4.8),
                Map.of("name", "东方明珠", "type", "scenic", "duration", 120, "rating", 4.7),
                Map.of("name", "豫园", "type", "scenic", "duration", 90, "rating", 4.6),
                Map.of("name", "城隍庙", "type", "temple", "duration", 60, "rating", 4.5),
                Map.of("name", "南京路", "type", "shopping", "duration", 120, "rating", 4.6),
                Map.of("name", "田子坊", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "迪士尼", "type", "scenic", "duration", 480, "rating", 4.8),
                Map.of("name", "新天地", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "陆家嘴", "type", "scenic", "duration", 60, "rating", 4.6),
                Map.of("name", "中华艺术宫", "type", "museum", "duration", 120, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("成都", List.of(
                Map.of("name", "宽窄巷子", "type", "shopping", "duration", 90, "rating", 4.7),
                Map.of("name", "锦里", "type", "shopping", "duration", 90, "rating", 4.6),
                Map.of("name", "武侯祠", "type", "temple", "duration", 120, "rating", 4.7),
                Map.of("name", "大熊猫基地", "type", "scenic", "duration", 180, "rating", 4.9),
                Map.of("name", "杜甫草堂", "type", "temple", "duration", 90, "rating", 4.6),
                Map.of("name", "春熙路", "type", "shopping", "duration", 120, "rating", 4.5),
                Map.of("name", "青城山", "type", "scenic", "duration", 240, "rating", 4.8),
                Map.of("name", "都江堰", "type", "scenic", "duration", 180, "rating", 4.8),
                Map.of("name", "金沙遗址", "type", "museum", "duration", 120, "rating", 4.6),
                Map.of("name", "人民公园", "type", "park", "duration", 60, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("西安", List.of(
                Map.of("name", "兵马俑", "type", "museum", "duration", 180, "rating", 4.9),
                Map.of("name", "大雁塔", "type", "temple", "duration", 90, "rating", 4.7),
                Map.of("name", "华清宫", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "回民街", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "城墙", "type", "scenic", "duration", 120, "rating", 4.7),
                Map.of("name", "陕西历史博物馆", "type", "museum", "duration", 180, "rating", 4.8),
                Map.of("name", "钟楼", "type", "scenic", "duration", 60, "rating", 4.6),
                Map.of("name", "鼓楼", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "大唐不夜城", "type", "shopping", "duration", 120, "rating", 4.7),
                Map.of("name", "碑林博物馆", "type", "museum", "duration", 90, "rating", 4.6)
        ));
        CITY_ATTRACTIONS.put("苏州", List.of(
                Map.of("name", "拙政园", "type", "scenic", "duration", 120, "rating", 4.8),
                Map.of("name", "虎丘", "type", "scenic", "duration", 90, "rating", 4.7),
                Map.of("name", "留园", "type", "scenic", "duration", 90, "rating", 4.7),
                Map.of("name", "平江路", "type", "shopping", "duration", 90, "rating", 4.6),
                Map.of("name", "寒山寺", "type", "temple", "duration", 60, "rating", 4.5),
                Map.of("name", "山塘街", "type", "shopping", "duration", 60, "rating", 4.6),
                Map.of("name", "周庄", "type", "scenic", "duration", 180, "rating", 4.7),
                Map.of("name", "同里", "type", "scenic", "duration", 180, "rating", 4.6),
                Map.of("name", "苏州博物馆", "type", "museum", "duration", 120, "rating", 4.7),
                Map.of("name", "观前街", "type", "shopping", "duration", 90, "rating", 4.4)
        ));
        CITY_ATTRACTIONS.put("南京", List.of(
                Map.of("name", "中山陵", "type", "scenic", "duration", 120, "rating", 4.8),
                Map.of("name", "夫子庙", "type", "shopping", "duration", 90, "rating", 4.6),
                Map.of("name", "明孝陵", "type", "scenic", "duration", 120, "rating", 4.7),
                Map.of("name", "总统府", "type", "museum", "duration", 90, "rating", 4.7),
                Map.of("name", "玄武湖", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "秦淮河", "type", "scenic", "duration", 90, "rating", 4.5),
                Map.of("name", "侵华日军南京大屠杀遇难同胞纪念馆", "type", "museum", "duration", 90, "rating", 4.8),
                Map.of("name", "灵谷寺", "type", "temple", "duration", 60, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("重庆", List.of(
                Map.of("name", "洪崖洞", "type", "scenic", "duration", 60, "rating", 4.7),
                Map.of("name", "解放碑", "type", "shopping", "duration", 90, "rating", 4.6),
                Map.of("name", "磁器口", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "长江索道", "type", "scenic", "duration", 30, "rating", 4.6),
                Map.of("name", "武隆天坑", "type", "scenic", "duration", 300, "rating", 4.8),
                Map.of("name", "大足石刻", "type", "scenic", "duration", 240, "rating", 4.8),
                Map.of("name", "南山一棵树", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "朝天门", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "三峡博物馆", "type", "museum", "duration", 120, "rating", 4.6),
                Map.of("name", "观音桥步行街", "type", "shopping", "duration", 90, "rating", 4.4)
        ));
        CITY_ATTRACTIONS.put("长沙", List.of(
                Map.of("name", "岳麓山", "type", "scenic", "duration", 180, "rating", 4.7),
                Map.of("name", "橘子洲", "type", "scenic", "duration", 120, "rating", 4.7),
                Map.of("name", "太平街", "type", "shopping", "duration", 90, "rating", 4.6),
                Map.of("name", "湖南省博物馆", "type", "museum", "duration", 120, "rating", 4.8),
                Map.of("name", "天心阁", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "世界之窗", "type", "scenic", "duration", 240, "rating", 4.5),
                Map.of("name", "火宫殿", "type", "shopping", "duration", 60, "rating", 4.6)
        ));
        CITY_ATTRACTIONS.put("厦门", List.of(
                Map.of("name", "鼓浪屿", "type", "scenic", "duration", 240, "rating", 4.8),
                Map.of("name", "南普陀寺", "type", "temple", "duration", 90, "rating", 4.7),
                Map.of("name", "厦门大学", "type", "scenic", "duration", 90, "rating", 4.6),
                Map.of("name", "曾厝垵", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "环岛路", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "集美学村", "type", "scenic", "duration", 120, "rating", 4.5),
                Map.of("name", "中山路", "type", "shopping", "duration", 90, "rating", 4.5),
                Map.of("name", "厦门博物馆", "type", "museum", "duration", 120, "rating", 4.5),
                Map.of("name", "沙坡尾艺术西区", "type", "shopping", "duration", 90, "rating", 4.4)
        ));
        CITY_ATTRACTIONS.put("三亚", List.of(
                Map.of("name", "亚龙湾", "type", "scenic", "duration", 180, "rating", 4.8),
                Map.of("name", "天涯海角", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "南山寺", "type", "temple", "duration", 180, "rating", 4.7),
                Map.of("name", "蜈支洲岛", "type", "scenic", "duration", 300, "rating", 4.8),
                Map.of("name", "大小洞天", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "热带天堂森林公园", "type", "scenic", "duration", 120, "rating", 4.5),
                Map.of("name", "鹿回头", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "三亚千古情", "type", "museum", "duration", 120, "rating", 4.5),
                Map.of("name", "第一市场", "type", "shopping", "duration", 90, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("大理", List.of(
                Map.of("name", "洱海", "type", "scenic", "duration", 240, "rating", 4.8),
                Map.of("name", "大理古城", "type", "shopping", "duration", 120, "rating", 4.6),
                Map.of("name", "崇圣寺三塔", "type", "temple", "duration", 90, "rating", 4.7),
                Map.of("name", "苍山", "type", "scenic", "duration", 240, "rating", 4.7),
                Map.of("name", "双廊", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "喜洲古镇", "type", "scenic", "duration", 90, "rating", 4.5),
                Map.of("name", "蝴蝶泉", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "大理白族自治州博物馆", "type", "museum", "duration", 120, "rating", 4.5),
                Map.of("name", "洋人街", "type", "shopping", "duration", 60, "rating", 4.3)
        ));
        CITY_ATTRACTIONS.put("丽江", List.of(
                Map.of("name", "丽江古城", "type", "shopping", "duration", 180, "rating", 4.7),
                Map.of("name", "玉龙雪山", "type", "scenic", "duration", 300, "rating", 4.8),
                Map.of("name", "束河古镇", "type", "scenic", "duration", 120, "rating", 4.6),
                Map.of("name", "泸沽湖", "type", "scenic", "duration", 360, "rating", 4.8),
                Map.of("name", "蓝月谷", "type", "scenic", "duration", 90, "rating", 4.7),
                Map.of("name", "拉市海", "type", "scenic", "duration", 120, "rating", 4.5),
                Map.of("name", "木府", "type", "scenic", "duration", 60, "rating", 4.6),
                Map.of("name", "东巴文化博物馆", "type", "museum", "duration", 120, "rating", 4.5),
                Map.of("name", "四方街", "type", "shopping", "duration", 60, "rating", 4.5)
        ));
        CITY_ATTRACTIONS.put("广州", List.of(
                Map.of("name", "广州塔", "type", "scenic", "duration", 120, "rating", 4.7),
                Map.of("name", "陈家祠", "type", "museum", "duration", 90, "rating", 4.6),
                Map.of("name", "沙面", "type", "scenic", "duration", 90, "rating", 4.5),
                Map.of("name", "北京路", "type", "shopping", "duration", 120, "rating", 4.5),
                Map.of("name", "白云山", "type", "scenic", "duration", 180, "rating", 4.6),
                Map.of("name", "长隆欢乐世界", "type", "scenic", "duration", 300, "rating", 4.7),
                Map.of("name", "珠江夜游", "type", "scenic", "duration", 90, "rating", 4.6)
        ));
        CITY_ATTRACTIONS.put("武汉", List.of(
                Map.of("name", "黄鹤楼", "type", "scenic", "duration", 90, "rating", 4.7),
                Map.of("name", "东湖", "type", "scenic", "duration", 180, "rating", 4.6),
                Map.of("name", "户部巷", "type", "shopping", "duration", 60, "rating", 4.5),
                Map.of("name", "武汉大学", "type", "scenic", "duration", 90, "rating", 4.6),
                Map.of("name", "归元禅寺", "type", "temple", "duration", 60, "rating", 4.5),
                Map.of("name", "长江大桥", "type", "scenic", "duration", 60, "rating", 4.6),
                Map.of("name", "汉口江滩", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "湖北省博物馆", "type", "museum", "duration", 180, "rating", 4.8),
                Map.of("name", "江汉路步行街", "type", "shopping", "duration", 90, "rating", 4.4)
        ));
        CITY_ATTRACTIONS.put("哈尔滨", List.of(
                Map.of("name", "冰雪大世界", "type", "scenic", "duration", 180, "rating", 4.7),
                Map.of("name", "中央大街", "type", "shopping", "duration", 90, "rating", 4.6),
                Map.of("name", "圣索菲亚大教堂", "type", "scenic", "duration", 60, "rating", 4.7),
                Map.of("name", "太阳岛", "type", "scenic", "duration", 120, "rating", 4.5),
                Map.of("name", "东北虎林园", "type", "scenic", "duration", 90, "rating", 4.6),
                Map.of("name", "松花江", "type", "scenic", "duration", 60, "rating", 4.5),
                Map.of("name", "哈尔滨极地馆", "type", "museum", "duration", 120, "rating", 4.5),
                Map.of("name", "老道外中华巴洛克", "type", "scenic", "duration", 90, "rating", 4.5)
        ));
    }

    /**
     * 从 TripVersion JSON 中获取当前活动列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getActivitiesFromVersion(TripVersion version) {
        if (version == null || version.getActivities() == null || version.getActivities().isBlank()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> activities = jsonUtils.fromJson(version.getActivities(), List.class);
        return activities != null ? new ArrayList<>(activities) : new ArrayList<>();
    }

    /**
     * 在 TripVersion JSON 活动列表中查找活动
     * 匹配策略: 1) id 精确匹配  2) seq + poiName 匹配  3) 纯 poiName 匹配
     */
    private Map<String, Object> findActivityInVersion(List<Map<String, Object>> activities, 
                                                       String activityId, String activityName, Integer seq) {
        if (activities == null) return null;
        log.info("查找活动: id={}, name={}, seq={}, json活动数={}", activityId, activityName, seq, activities.size());

        // 策略1: 按 id 精确匹配
        if (activityId != null && !activityId.isEmpty()) {
            for (Map<String, Object> act : activities) {
                String id = (String) act.get("id");
                if (activityId.equals(id)) {
                    return act;
                }
            }
        }

        // 策略2: 按 seq + 名称匹配（最可靠，兼容 poi_name/poiName/name）
        if (seq != null && activityName != null && !activityName.isEmpty()) {
            for (Map<String, Object> act : activities) {
                Object seqObj = act.get("seq");
                int actSeq = seqObj != null ? ((Number) seqObj).intValue() : -1;
                String poiName = nameOf(act);
                if (actSeq == seq && activityName.equals(poiName)) {
                    return act;
                }
            }
        }

        // 策略3: 按名称匹配
        if (activityName != null && !activityName.isEmpty()) {
            for (Map<String, Object> act : activities) {
                if (activityName.equals(nameOf(act))) {
                    return act;
                }
            }
        }

        // 策略4: 按 seq 匹配（兜底）
        if (seq != null) {
            for (Map<String, Object> act : activities) {
                Object seqObj = act.get("seq");
                int actSeq = seqObj != null ? ((Number) seqObj).intValue() : -1;
                if (actSeq == seq) {
                    log.info("仅按seq匹配成功: seq={}", seq);
                    return act;
                }
            }
        }

        return null;
    }

    /**
     * 根据名称在 TripVersion JSON 活动列表中查找活动
     */
    private Map<String, Object> findActivityByNameInVersion(List<Map<String, Object>> activities, String name) {
        if (name == null || name.isEmpty() || activities == null) {
            return null;
        }
        for (Map<String, Object> act : activities) {
            if (name.equals(nameOf(act))) {
                return act;
            }
        }
        return null;
    }

    /**
     * 将 TripVersion JSON 活动转为 Activity 实体（用于兼容 findAlternatives 等方法）
     */
    private Activity toActivityEntity(Map<String, Object> actMap) {
        Activity act = new Activity();
        if (actMap == null) {
            act.setPoiName("未知景点");
            act.setActivityType("scenic");
            return act;
        }
        act.setId((String) actMap.get("id"));
        act.setPoiName(nameOf(actMap));
        act.setActivityType(String.valueOf(actMap.getOrDefault("activity_type",
                actMap.getOrDefault("activityType", actMap.getOrDefault("type", "visit")))));
        act.setPoiAddress((String) actMap.get("poi_address"));
        act.setPoiCategory((String) actMap.getOrDefault("poi_category", actMap.get("poiCategory")));
        act.setPriority((String) actMap.get("priority"));
        act.setDurationMin(actMap.get("durationMin") != null ? ((Number) actMap.get("durationMin")).intValue() : null);
        act.setNotes((String) actMap.get("notes"));
        act.setSlogan((String) actMap.get("slogan"));
        return act;
    }

    /**
     * 获取景点备选项
     */
    public AlternativesResponse getAlternatives(GetAlternativesRequest request) {
        log.info("获取景点备选项: tripId={}, activityId={}, activityName={}", 
                request.getTripId(), request.getActivityId(), request.getActivityName());

        // 1. 验证行程存在
        Trip trip = tripRepository.selectById(request.getTripId());
        if (trip == null) {
            log.warn("行程不存在: {}", request.getTripId());
            throw BizException.notFound("行程", request.getTripId());
        }

        // 2. 获取当前版本和活动列表（从 TripVersion JSON）
        TripVersion currentVersion = trip.getCurrentVersionId() != null 
                ? versionRepository.findById(trip.getCurrentVersionId()) : null;
        List<Map<String, Object>> versionActivities = getActivitiesFromVersion(currentVersion);
        log.info("当前版本活动数: {}, versionId={}", versionActivities.size(), 
                currentVersion != null ? currentVersion.getId() : "null");

        // 3. 在 JSON 活动列表中查找原活动（按 ID，再按 seq+name，最后按名称）
        Map<String, Object> originalActMap = findActivityInVersion(versionActivities, 
                request.getActivityId(), request.getActivityName(), request.getSeq());
        if (originalActMap == null) {
            originalActMap = findActivityByNameInVersion(versionActivities, request.getActivityName());
        }
        
        // 4. 如果还是找不到，创建临时活动信息用于推荐
        Activity originalActivity;
        if (originalActMap != null) {
            originalActivity = toActivityEntity(originalActMap);
            log.info("找到原活动: id={}, name={}, type={}", 
                    originalActivity.getId(), originalActivity.getPoiName(), originalActivity.getActivityType());
        } else {
            log.warn("活动未在版本中找到，使用请求参数: activityId={}, activityName={}", 
                    request.getActivityId(), request.getActivityName());
            originalActivity = new Activity();
            originalActivity.setPoiName(request.getActivityName() != null ? request.getActivityName() : "未知景点");
            originalActivity.setActivityType(request.getActivityType() != null ? request.getActivityType() : "scenic");
        }

        // 5. 获取城市信息
        String city = request.getCity() != null && !request.getCity().isEmpty() 
                ? request.getCity() 
                : extractCityFromTrip(trip);
        log.info("使用城市: {}", city != null ? city : "未识别");

        // 6. 获取备选项
        int limit = request.getLimit() != null ? request.getLimit() : 5;
        double radiusKm = request.getRadiusKm() != null ? request.getRadiusKm() : 5.0;

        List<ActivityAlternative> alternatives = findAlternatives(
                originalActivity, city, limit, radiusKm, versionActivities);

        log.info("找到 {} 个备选项", alternatives.size());

        return AlternativesResponse.builder()
                .tripId(request.getTripId())
                .activityId(request.getActivityId())
                .originalName(originalActivity.getPoiName())
                .originalType(originalActivity.getActivityType())
                .alternatives(alternatives)
                .count(alternatives.size())
                .responseTime(LocalDateTime.now())
                .build();
    }

    /**
     * 替换景点
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> replaceActivity(ReplaceActivityRequest request) {
        log.info("替换景点: tripId={}, activityId={}, activityName={}, seq={}", 
                request.getTripId(), request.getActivityId(), request.getActivityName(), request.getSeq());

        // 1. 验证行程存在
        Trip trip = tripRepository.selectById(request.getTripId());
        if (trip == null) {
            throw BizException.notFound("行程", request.getTripId());
        }

        // 2. 获取当前版本
        TripVersion currentVersion = versionRepository.findById(trip.getCurrentVersionId());
        if (currentVersion == null) {
            throw BizException.notFound("版本", trip.getCurrentVersionId());
        }

        // 3. 从 TripVersion JSON 获取活动列表（关键修复：不再依赖 activityRepository）
        List<Map<String, Object>> activities = getActivitiesFromVersion(currentVersion);
        log.info("当前版本活动数: {}", activities.size());

        // 4. 在 JSON 中查找要替换的活动（按 ID，再按 seq+name，最后按名称）
        Map<String, Object> oldActivity = findActivityInVersion(activities,
                request.getActivityId(), request.getActivityName(), request.getSeq());
        if (oldActivity == null) {
            log.warn("未在版本JSON中找到活动: activityId={}, name={}, seq={}",
                    request.getActivityId(), request.getActivityName(), request.getSeq());
            for (Map<String, Object> a : activities) {
                log.debug("  JSON活动: id={}, name={}, seq={}",
                        a.get("id"), nameOf(a), a.get("seq"));
            }
            throw BizException.notFound("活动", request.getActivityId() != null ? request.getActivityId() : request.getActivityName());
        }

        int activityIndex = -1;
        for (int i = 0; i < activities.size(); i++) {
            Map<String, Object> act = activities.get(i);
            String actId = (String) act.get("id");
            if (oldActivity.get("id") != null && oldActivity.get("id").equals(actId)) {
                activityIndex = i;
                break;
            }
        }
        if (activityIndex == -1) {
            Object oldSeq = oldActivity.get("seq");
            String oldName = nameOf(oldActivity);
            for (int i = 0; i < activities.size(); i++) {
                Map<String, Object> act = activities.get(i);
                Object actSeq = act.get("seq");
                String actName = nameOf(act);
                if (oldSeq != null && actSeq != null && ((Number) oldSeq).intValue() == ((Number) actSeq).intValue()) {
                    activityIndex = i;
                    break;
                }
                if (oldName != null && oldName.equals(actName)) {
                    activityIndex = i;
                    break;
                }
            }
        }
        if (activityIndex == -1) {
            throw BizException.notFound("活动", request.getActivityId() != null ? request.getActivityId() : request.getActivityName());
        }

        String oldName = nameOf(oldActivity);
        log.info("找到要替换的活动: index={}, name={}", activityIndex, oldName);

        String city = extractCityFromTrip(trip);

        // 5. 更新活动信息（同步 poi_name / poiName / name，前端与 routes 均依赖）
        Map<String, Object> newActivity = new HashMap<>(oldActivity);
        newActivity.put("name", request.getAlternativeName());
        newActivity.put("poiName", request.getAlternativeName());
        newActivity.put("poi_name", request.getAlternativeName());
        newActivity.put("replacedFrom", oldName);
        newActivity.put("replacedAt", LocalDateTime.now().toString());
        // 清除旧景点坐标与距离，替换后强制重新解析
        newActivity.remove("lat");
        newActivity.remove("lng");
        newActivity.remove("travel_distance_km");
        newActivity.remove("travelDistanceKm");
        newActivity.remove("travel_duration_min");
        // 搜索换入：携带目标 POI 坐标/地址时直接写入（resolveCoord 首级命中，无需地理编码）
        if (request.getLat() != null && request.getLng() != null) {
            newActivity.put("lat", request.getLat());
            newActivity.put("lng", request.getLng());
        }
        if (request.getAddress() != null && !request.getAddress().isBlank()) {
            newActivity.put("poi_address", request.getAddress());
            newActivity.put("poiAddress", request.getAddress());
        }
        String actType = String.valueOf(newActivity.getOrDefault("activityType",
                newActivity.getOrDefault("activity_type", "visit")));
        if (request.getNotes() != null) {
            newActivity.put("notes", request.getNotes());
        } else {
            String typePrefix = switch (actType) {
                case "meal" -> "用餐";
                case "transit" -> "交通";
                case "stay" -> "住宿";
                case "shopping" -> "购物";
                default -> "游览";
            };
            newActivity.put("notes", typePrefix + request.getAlternativeName());
        }
        newActivity.put("slogan", sloganService.generateSlogan(request.getAlternativeName(), actType, request.getTripId()));

        activities.set(activityIndex, newActivity);

        // 6. 替换后重算邻接段真实距离/时长（前驱→新、新→后继），并回写坐标
        recomputeAdjacentTravel(activities, activityIndex, city);

        // 6.5 真实路程时间反映到下一起点：start_i ≥ end_{i-1} + travel_{i-1}
        resequenceDayByTravel(activities, activityIndex);

        // 7. 按最新活动重建 routes（distance_km 与 activity.travel_distance_km 一致）
        List<Map<String, Object>> newRoutes = buildRoutesFromActivities(activities);

        // 8. 保存新版本
        TripVersion newVersion = new TripVersion();
        newVersion.setId(UUID.randomUUID().toString().replace("-", ""));
        newVersion.setTripId(request.getTripId());
        newVersion.setVersionNum(currentVersion.getVersionNum() + 1);
        newVersion.setParentVersionId(currentVersion.getId());
        newVersion.setActivities(jsonUtils.toJson(activities));
        newVersion.setRoutes(jsonUtils.toJson(newRoutes));
        newVersion.setConflicts("[]");
        newVersion.setStats(currentVersion.getStats());
        newVersion.setFeedback("替换景点: " + oldName + " -> " + request.getAlternativeName());
        newVersion.setSolverMeta("{}");
        newVersion.setStatus("completed");

        versionRepository.insert(newVersion);

        // 9. 更新行程当前版本
        trip.setCurrentVersionId(newVersion.getId());
        tripRepository.updateById(trip);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("tripId", request.getTripId());
        result.put("oldActivityName", oldName);
        result.put("newActivityName", request.getAlternativeName());
        result.put("newVersionId", newVersion.getId());
        result.put("versionNum", newVersion.getVersionNum());
        // 返回重算后的邻接段，便于前端立即刷新
        Map<String, Object> prevLeg = activityIndex > 0
                ? Map.of("from", nameOf(activities.get(activityIndex - 1)),
                         "to", request.getAlternativeName(),
                         "distanceKm", num(activities.get(activityIndex - 1).get("travel_distance_km")),
                         "durationMin", num(activities.get(activityIndex - 1).get("travel_duration_min")))
                : Map.of();
        Map<String, Object> nextLeg = activityIndex < activities.size() - 1
                ? Map.of("from", request.getAlternativeName(),
                         "to", nameOf(activities.get(activityIndex + 1)),
                         "distanceKm", num(newActivity.get("travel_distance_km")),
                         "durationMin", num(newActivity.get("travel_duration_min")))
                : Map.of();
        result.put("previousLeg", prevLeg);
        result.put("nextLeg", nextLeg);

        log.info("替换景点成功: {} -> {}, version={}, prevLeg={}, nextLeg={}",
                oldName, request.getAlternativeName(), newVersion.getVersionNum(), prevLeg, nextLeg);

        return result;
    }

    private static String nameOf(Map<String, Object> act) {
        if (act == null) return "";
        Object v = act.get("poi_name");
        if (v == null) v = act.get("poiName");
        if (v == null) v = act.get("name");
        return v == null ? "" : String.valueOf(v);
    }

    private static Object num(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    /**
     * 替换点邻接段重算：
     * - index>0：更新前驱 → 新活动 的 travel_*
     * - index<size-1：更新 新活动 → 后继 的 travel_*
     * 坐标：活动 lat/lng → 静态表 → 高德 geocode；路线：高德 route（失败则 Haversine×1.3 估算）
     */
    private void recomputeAdjacentTravel(List<Map<String, Object>> activities, int index, String city) {
        Map<String, Object> cur = activities.get(index);

        if (index > 0) {
            Map<String, Object> prev = activities.get(index - 1);
            applyLeg(prev, cur, city);
        }
        if (index < activities.size() - 1) {
            Map<String, Object> next = activities.get(index + 1);
            applyLeg(cur, next, city);
        }
    }

    /**
     * 替换后按真实路程时间顺延被影响当天的后续活动起点：
     * start_i ≥ end_{i-1} + travel_{i-1}（只后推不前拉）。
     */
    private void resequenceDayByTravel(List<Map<String, Object>> activities, int index) {
        if (activities == null || activities.isEmpty() || index < 0 || index >= activities.size()) {
            return;
        }
        int day = numToInt(activities.get(index).get("day"), 1);
        java.time.LocalTime prevEnd = null;
        int prevTravel = 0;
        for (Map<String, Object> a : activities) {
            if (numToInt(a.get("day"), 1) != day) continue;
            java.time.LocalTime start = parseClock(firstNonBlank(a.get("scheduled_start"), a.get("startTime")));
            if (start == null) continue;
            int dur = numToInt(firstNonBlank(a.get("duration_min"), a.get("durationMin")), 60);
            if (dur <= 0) dur = 60;
            java.time.LocalTime original = start;
            if (prevEnd != null) {
                java.time.LocalTime earliest = prevEnd.plusMinutes(Math.max(0, prevTravel));
                if (start.isBefore(earliest)) {
                    start = earliest;
                }
            }
            if (!start.equals(original)) {
                java.time.LocalTime end = start.plusMinutes(dur);
                a.put("scheduled_start", clock(start));
                a.put("scheduled_end", clock(end));
                if (a.containsKey("startTime")) a.put("startTime", clock(start));
                if (a.containsKey("endTime")) a.put("endTime", clock(end));
                log.info("替换后顺延: {} {} -> {}", day, nameOf(a), clock(start));
            }
            prevEnd = start.plusMinutes(dur);
            prevTravel = numToInt(firstNonBlank(a.get("travel_duration_min"), a.get("travelTimeMin")), 0);
        }
    }

    private static Object firstNonBlank(Object a, Object b) {
        if (a != null && !String.valueOf(a).isBlank()) return a;
        return b;
    }

    private static int numToInt(Object v, int dft) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return dft;
        }
    }

    private static java.time.LocalTime parseClock(Object v) {
        try {
            return java.time.LocalTime.parse(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String clock(java.time.LocalTime t) {
        return t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** 计算 from→next 一段，写入 from 的 travel_distance_km / travel_duration_min / transport_mode */
    private void applyLeg(Map<String, Object> from, Map<String, Object> to, String city) {
        double[] a = resolveCoord(from, city);
        double[] b = resolveCoord(to, city);
        if (a == null || b == null) {
            log.warn("邻接段坐标缺失，跳过重算: {} → {}", nameOf(from), nameOf(to));
            return;
        }

        double straightKm = GeoUtils.distanceKm(a[0], a[1], b[0], b[1]);
        double roadKm = Math.round(straightKm * 1.3 * 10.0) / 10.0;
        int minutes = Math.max(5, (int) Math.ceil(straightKm / 20.0 * 60.0));
        String mode = straightKm < 1.0 ? "walk" : "transit";

        // 优先真实路网（plan-service → 高德）
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("originLat", a[0]);
            body.put("originLng", a[1]);
            body.put("destLat", b[0]);
            body.put("destLng", b[1]);
            body.put("mode", mode);
            if (city != null) body.put("city", city);
            ApiResponse<Map<String, Object>> resp = planMapClient.route(body);
            Map<String, Object> data = resp != null ? resp.getData() : null;
            if (data != null && Boolean.TRUE.equals(data.get("success"))) {
                Object dist = data.get("distance"); // 米
                Object dur = data.get("duration");  // 秒
                Object m = data.get("mode");
                if (dist instanceof Number n && n.doubleValue() > 0) {
                    roadKm = Math.round(n.doubleValue() / 1000.0 * 10.0) / 10.0;
                }
                if (dur instanceof Number n && n.intValue() > 0) {
                    minutes = (int) Math.ceil(n.doubleValue() / 60.0);
                }
                if (m != null && !String.valueOf(m).isBlank()) {
                    mode = String.valueOf(m);
                }
                log.info("邻接段真实路线: {}→{} {}km {}min {}", nameOf(from), nameOf(to), roadKm, minutes, mode);
            } else {
                log.warn("邻接段路线接口失败，使用直线×1.3: {}→{}", nameOf(from), nameOf(to));
            }
        } catch (Exception e) {
            log.warn("邻接段路线调用异常，使用直线×1.3: {}→{} - {}", nameOf(from), nameOf(to), e.getMessage());
        }

        from.put("travel_distance_km", roadKm);
        from.put("travelDistanceKm", roadKm);
        from.put("travel_duration_min", minutes);
        from.put("transport_mode", mode);
        from.put("transportToNext", mode);
    }

    /** 解析活动坐标 [lat,lng]：lat/lng 字段 → 静态表 → 高德 geocode（剥离餐次前缀） */
    private double[] resolveCoord(Map<String, Object> act, String city) {
        try {
            Object lat = act.get("lat");
            Object lng = act.get("lng");
            if (lat != null && lng != null) {
                double la = Double.parseDouble(String.valueOf(lat));
                double lo = Double.parseDouble(String.valueOf(lng));
                if (Double.isFinite(la) && Double.isFinite(lo) && (la != 0 || lo != 0)) {
                    return new double[]{la, lo};
                }
            }
        } catch (Exception ignored) {}

        String name = nameOf(act);
        if (name != null && !name.isEmpty()) {
            double[] staticC = PLACE_COORDS.get(name);
            if (staticC == null) {
                String geoName = name.replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
                staticC = PLACE_COORDS.get(geoName);
            }
            if (staticC != null) {
                act.put("lat", staticC[0]);
                act.put("lng", staticC[1]);
                return staticC;
            }
            String geoName = name.replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("query", geoName);
                if (city != null && !city.isEmpty()) body.put("city", city);
                body.put("source", "amap");
                ApiResponse<Map<String, Object>> resp = planMapClient.geocode(body);
                Map<String, Object> data = resp != null ? resp.getData() : null;
                if (data != null) {
                    Object la = data.get("lat");
                    Object lo = data.get("lng");
                    if (la != null && lo != null) {
                        double lat = Double.parseDouble(String.valueOf(la));
                        double lng = Double.parseDouble(String.valueOf(lo));
                        if (Double.isFinite(lat) && Double.isFinite(lng) && (lat != 0 || lng != 0)) {
                            act.put("lat", lat);
                            act.put("lng", lng);
                            return new double[]{lat, lng};
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("geocode失败: {} - {}", geoName, e.getMessage());
            }
        }
        return null;
    }

    /** 由活动列表重建 routes（与 TripService.buildRoutesFromActivities 字段对齐） */
    private List<Map<String, Object>> buildRoutesFromActivities(List<Map<String, Object>> activities) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < activities.size() - 1; i++) {
            Map<String, Object> from = activities.get(i);
            Map<String, Object> to = activities.get(i + 1);
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("from", nameOf(from));
            route.put("to", nameOf(to));
            Object mode = from.getOrDefault("transport_mode",
                    from.getOrDefault("transportToNext",
                            from.getOrDefault("transportMode", "transit")));
            route.put("mode", mode);
            Object dist = from.getOrDefault("travel_distance_km",
                    from.getOrDefault("travelDistanceKm", 0.0));
            route.put("distance_km", dist);
            Integer durMin = from.get("travel_duration_min") instanceof Number n
                    ? n.intValue()
                    : (from.get("travelDurationMin") instanceof Number n2 ? n2.intValue() : 0);
            route.put("duration_min", durMin);
            if (from.get("lat") != null && from.get("lng") != null) {
                route.put("from_lat", from.get("lat"));
                route.put("from_lng", from.get("lng"));
            }
            if (to.get("lat") != null && to.get("lng") != null) {
                route.put("to_lat", to.get("lat"));
                route.put("to_lng", to.get("lng"));
            }
            routes.add(route);
        }
        return routes;
    }

    /**
     * 查找备选项
     */
    private List<ActivityAlternative> findAlternatives(Activity original, String city, int limit, double radiusKm,
                                                      List<Map<String, Object>> versionActivities) {
        List<ActivityAlternative> alternatives = new ArrayList<>();

        // 1. 从城市景点库获取同类型景点
        List<Map<String, Object>> cityPlaces = CITY_ATTRACTIONS.getOrDefault(city, List.of());
        String originalType = original.getActivityType() != null ? original.getActivityType() : "scenic";

        // 获取相似类型
        List<String> similarTypes = TYPE_SIMILARITY.getOrDefault(originalType, List.of(originalType));

        // 2. 从 TripVersion JSON 获取已在行程中的景点名称
        Set<String> existingNames = new HashSet<>();
        if (versionActivities != null) {
            for (Map<String, Object> a : versionActivities) {
                String n = nameOf(a);
                if (!n.isEmpty()) existingNames.add(n);
            }
        }

        String originalName = original.getPoiName() != null ? original.getPoiName() : "";

        for (Map<String, Object> place : cityPlaces) {
            String name = (String) place.get("name");
            String type = (String) place.get("type");

            // 跳过原景点
            if (name.equals(originalName)) {
                continue;
            }

            // 跳过已在行程中的景点
            if (existingNames.contains(name)) {
                continue;
            }

            // 检查类型相似度
            if (!similarTypes.contains(type)) {
                continue;
            }

            // 创建备选项（附带与原景点距离/时长）
            Integer distM = calculateDistance(original, name, city);
            Integer travelMin = distM == null ? null
                    : Math.max(5, (int) Math.ceil(distM / 1000.0 / 20.0 * 60.0));
            ActivityAlternative alternative = ActivityAlternative.builder()
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .name(name)
                    .type(type)
                    .reason(generateRecommendReason(name, type, originalName))
                    .durationMin((Integer) place.get("duration"))
                    .priority("recommended")
                    .rating((Number) place.get("rating") != null ? ((Number) place.get("rating")).doubleValue() : 4.5)
                    .distanceFromOriginalMeters(distM)
                    .travelTimeFromOriginalMin(travelMin)
                    .source("ai_suggest")
                    .tags(List.of(city, type))
                    .build();

            alternatives.add(alternative);

            if (alternatives.size() >= limit) {
                break;
            }
        }

        // 3. 如果备选项不足，补充其他类型景点
        if (alternatives.size() < limit) {
            for (Map<String, Object> place : cityPlaces) {
                String name = (String) place.get("name");
                String type = (String) place.get("type");

                if (name.equals(originalName) || existingNames.contains(name)) {
                    continue;
                }

                boolean alreadyAdded = alternatives.stream()
                        .anyMatch(a -> a.getName().equals(name));
                if (alreadyAdded) {
                    continue;
                }

                Integer distM2 = calculateDistance(original, name, city);
                Integer travelMin2 = distM2 == null ? null
                        : Math.max(5, (int) Math.ceil(distM2 / 1000.0 / 20.0 * 60.0));
                ActivityAlternative alternative = ActivityAlternative.builder()
                        .id(UUID.randomUUID().toString().replace("-", ""))
                        .name(name)
                        .type(type)
                        .reason("热门景点推荐")
                        .durationMin((Integer) place.get("duration"))
                        .priority("optional")
                        .rating(((Number) place.get("rating")).doubleValue())
                        .distanceFromOriginalMeters(distM2)
                        .travelTimeFromOriginalMin(travelMin2)
                        .source("ai_suggest")
                        .tags(List.of(city, type))
                        .build();

                alternatives.add(alternative);

                if (alternatives.size() >= limit) {
                    break;
                }
            }
        }

        return alternatives;
    }

    /**
     * 生成推荐理由
     */
    private String generateRecommendReason(String name, String type, String originalName) {
        if (originalName == null || originalName.isEmpty()) {
            originalName = "当前景点";
        }
        Map<String, String> reasons = Map.of(
                "scenic", "与" + originalName + "同类型的热门景点",
                "museum", "文化类景点，适合深度游",
                "temple", "历史文化景点，值得一游",
                "shopping", "购物休闲好去处",
                "park", "自然风光，放松身心",
                "restaurant", "特色美食推荐"
        );
        return reasons.getOrDefault(type, "热门景点推荐");
    }

    /**
     * 计算原景点 → 目标景点 直线距离（米），基于静态坐标表；无坐标返回 null
     */
    private Integer calculateDistance(Activity original, String targetName, String city) {
        Map<String, Object> from = new HashMap<>();
        if (original != null && original.getPoiName() != null) from.put("poi_name", original.getPoiName());
        Map<String, Object> to = new HashMap<>();
        if (targetName != null) to.put("poi_name", targetName);
        double[] a = resolveCoord(from, city);
        double[] b = resolveCoord(to, city);
        if (a == null || b == null) return null;
        return (int) Math.round(GeoUtils.distance(a[0], a[1], b[0], b[1]));
    }

    /**
     * 计算交通时间（分钟）：直线距离 / 20km/h（公交均速），最少 5 分钟
     */
    private Integer calculateTravelTime(Activity original, String targetName, String city) {
        Integer meters = calculateDistance(original, targetName, city);
        if (meters == null || meters <= 0) return null;
        double km = meters / 1000.0;
        return Math.max(5, (int) Math.ceil(km / 20.0 * 60.0));
    }

    /**
     * 从行程中提取城市（排除路名误判）
     */
    private String extractCityFromTrip(Trip trip) {
        if (trip.getRawInput() != null) {
            String city = com.tripplanner.common.util.CityOwnershipUtils.extractCity(trip.getRawInput());
            if (city != null && !city.isBlank()) {
                return city;
            }
        }
        return null;
    }
}
