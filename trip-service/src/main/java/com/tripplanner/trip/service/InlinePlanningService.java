package com.tripplanner.trip.service;

import com.tripplanner.common.util.CityOwnershipUtils;
import com.tripplanner.common.util.JsonUtils;
import com.tripplanner.trip.entity.Trip;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 内联规划服务 - 无需 Kafka/LLM 即可生成基础行程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InlinePlanningService {

    private final TripVersionService versionService;
    private final JsonUtils jsonUtils;

    // 城市景点数据
    private static final Map<String, List<Map<String, Object>>> CITY_ATTRACTIONS = new HashMap<>();
    static {
        CITY_ATTRACTIONS.put("杭州", List.of(
            Map.of("name", "西湖", "type", "scenic", "duration", 180),
            Map.of("name", "灵隐寺", "type", "temple", "duration", 120),
            Map.of("name", "河坊街", "type", "shopping", "duration", 90),
            Map.of("name", "雷峰塔", "type", "scenic", "duration", 90),
            Map.of("name", "龙井村", "type", "scenic", "duration", 120),
            Map.of("name", "断桥", "type", "scenic", "duration", 30),
            Map.of("name", "西溪湿地", "type", "scenic", "duration", 180),
            Map.of("name", "宋城", "type", "scenic", "duration", 240),
            Map.of("name", "九溪烟树", "type", "scenic", "duration", 90),
            Map.of("name", "千岛湖", "type", "scenic", "duration", 300)
        ));
        CITY_ATTRACTIONS.put("北京", List.of(
            Map.of("name", "故宫", "type", "scenic", "duration", 240),
            Map.of("name", "天安门广场", "type", "scenic", "duration", 60),
            Map.of("name", "颐和园", "type", "scenic", "duration", 180),
            Map.of("name", "天坛", "type", "temple", "duration", 120),
            Map.of("name", "南锣鼓巷", "type", "shopping", "duration", 90),
            Map.of("name", "什刹海", "type", "scenic", "duration", 90),
            Map.of("name", "圆明园", "type", "scenic", "duration", 150),
            Map.of("name", "北海公园", "type", "scenic", "duration", 120),
            Map.of("name", "雍和宫", "type", "temple", "duration", 90),
            Map.of("name", "798艺术区", "type", "museum", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("上海", List.of(
            Map.of("name", "外滩", "type", "scenic", "duration", 90),
            Map.of("name", "东方明珠", "type", "scenic", "duration", 120),
            Map.of("name", "豫园", "type", "scenic", "duration", 90),
            Map.of("name", "南京路", "type", "shopping", "duration", 120),
            Map.of("name", "迪士尼", "type", "scenic", "duration", 480),
            Map.of("name", "田子坊", "type", "shopping", "duration", 90),
            Map.of("name", "新天地", "type", "shopping", "duration", 90),
            Map.of("name", "陆家嘴", "type", "scenic", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("成都", List.of(
            Map.of("name", "宽窄巷子", "type", "shopping", "duration", 90),
            Map.of("name", "锦里", "type", "shopping", "duration", 90),
            Map.of("name", "武侯祠", "type", "temple", "duration", 120),
            Map.of("name", "大熊猫基地", "type", "scenic", "duration", 180),
            Map.of("name", "杜甫草堂", "type", "temple", "duration", 90),
            Map.of("name", "春熙路", "type", "shopping", "duration", 120),
            Map.of("name", "青城山", "type", "scenic", "duration", 240),
            Map.of("name", "都江堰", "type", "scenic", "duration", 180)
        ));
        CITY_ATTRACTIONS.put("西安", List.of(
            Map.of("name", "兵马俑", "type", "museum", "duration", 180),
            Map.of("name", "大雁塔", "type", "temple", "duration", 90),
            Map.of("name", "回民街", "type", "shopping", "duration", 90),
            Map.of("name", "城墙", "type", "scenic", "duration", 120),
            Map.of("name", "陕西历史博物馆", "type", "museum", "duration", 180),
            Map.of("name", "钟楼", "type", "scenic", "duration", 60),
            Map.of("name", "大唐不夜城", "type", "shopping", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("苏州", List.of(
            Map.of("name", "拙政园", "type", "scenic", "duration", 120),
            Map.of("name", "虎丘", "type", "scenic", "duration", 90),
            Map.of("name", "留园", "type", "scenic", "duration", 90),
            Map.of("name", "平江路", "type", "shopping", "duration", 90),
            Map.of("name", "寒山寺", "type", "temple", "duration", 60),
            Map.of("name", "山塘街", "type", "shopping", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("南京", List.of(
            Map.of("name", "中山陵", "type", "scenic", "duration", 120),
            Map.of("name", "夫子庙", "type", "shopping", "duration", 90),
            Map.of("name", "明孝陵", "type", "scenic", "duration", 120),
            Map.of("name", "总统府", "type", "museum", "duration", 90),
            Map.of("name", "玄武湖", "type", "scenic", "duration", 120),
            Map.of("name", "秦淮河", "type", "scenic", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("重庆", List.of(
            Map.of("name", "洪崖洞", "type", "scenic", "duration", 60),
            Map.of("name", "解放碑", "type", "shopping", "duration", 90),
            Map.of("name", "磁器口", "type", "shopping", "duration", 90),
            Map.of("name", "长江索道", "type", "scenic", "duration", 30),
            Map.of("name", "武隆天坑", "type", "scenic", "duration", 300),
            Map.of("name", "朝天门", "type", "scenic", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("长沙", List.of(
            Map.of("name", "岳麓山", "type", "scenic", "duration", 180),
            Map.of("name", "橘子洲", "type", "scenic", "duration", 120),
            Map.of("name", "太平街", "type", "shopping", "duration", 90),
            Map.of("name", "湖南省博物馆", "type", "museum", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("厦门", List.of(
            Map.of("name", "鼓浪屿", "type", "scenic", "duration", 240),
            Map.of("name", "南普陀寺", "type", "temple", "duration", 90),
            Map.of("name", "厦门大学", "type", "scenic", "duration", 90),
            Map.of("name", "曾厝垵", "type", "shopping", "duration", 90),
            Map.of("name", "环岛路", "type", "scenic", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("三亚", List.of(
            Map.of("name", "亚龙湾", "type", "scenic", "duration", 180),
            Map.of("name", "天涯海角", "type", "scenic", "duration", 120),
            Map.of("name", "蜈支洲岛", "type", "scenic", "duration", 300),
            Map.of("name", "南山寺", "type", "temple", "duration", 180)
        ));
        CITY_ATTRACTIONS.put("大理", List.of(
            Map.of("name", "洱海", "type", "scenic", "duration", 240),
            Map.of("name", "大理古城", "type", "shopping", "duration", 120),
            Map.of("name", "崇圣寺三塔", "type", "temple", "duration", 90),
            Map.of("name", "苍山", "type", "scenic", "duration", 240),
            Map.of("name", "双廊", "type", "scenic", "duration", 120)
        ));
        CITY_ATTRACTIONS.put("丽江", List.of(
            Map.of("name", "丽江古城", "type", "shopping", "duration", 180),
            Map.of("name", "玉龙雪山", "type", "scenic", "duration", 300),
            Map.of("name", "束河古镇", "type", "shopping", "duration", 120),
            Map.of("name", "泸沽湖", "type", "scenic", "duration", 360),
            Map.of("name", "蓝月谷", "type", "scenic", "duration", 90)
        ));
        CITY_ATTRACTIONS.put("广州", List.of(
            Map.of("name", "广州塔", "type", "scenic", "duration", 120),
            Map.of("name", "陈家祠", "type", "museum", "duration", 90),
            Map.of("name", "沙面", "type", "scenic", "duration", 90),
            Map.of("name", "北京路", "type", "shopping", "duration", 120),
            Map.of("name", "白云山", "type", "scenic", "duration", 180)
        ));
        CITY_ATTRACTIONS.put("武汉", List.of(
            Map.of("name", "黄鹤楼", "type", "scenic", "duration", 90),
            Map.of("name", "东湖", "type", "scenic", "duration", 180),
            Map.of("name", "户部巷", "type", "shopping", "duration", 60),
            Map.of("name", "武汉大学", "type", "scenic", "duration", 90),
            Map.of("name", "长江大桥", "type", "scenic", "duration", 60)
        ));
        CITY_ATTRACTIONS.put("哈尔滨", List.of(
            Map.of("name", "冰雪大世界", "type", "scenic", "duration", 180),
            Map.of("name", "中央大街", "type", "shopping", "duration", 90),
            Map.of("name", "圣索菲亚大教堂", "type", "scenic", "duration", 60),
            Map.of("name", "太阳岛", "type", "scenic", "duration", 120),
            Map.of("name", "松花江", "type", "scenic", "duration", 60)
        ));
    }

    /** 景点静态坐标（就近排序用；无坐标则保持原顺序） */
    private static final Map<String, double[]> ATTRACTION_COORDS = new HashMap<>();
    static {
        coord("西湖", 30.2480, 120.1480);
        coord("灵隐寺", 30.2410, 120.1010);
        coord("河坊街", 30.2420, 120.1690);
        coord("雷峰塔", 30.2320, 120.1490);
        coord("龙井村", 30.2290, 120.1180);
        coord("断桥", 30.2600, 120.1520);
        coord("西溪湿地", 30.2680, 120.0700);
        coord("宋城", 30.1990, 120.1050);
        coord("九溪烟树", 30.2100, 120.1200);
        coord("故宫", 39.9163, 116.3972);
        coord("天安门广场", 39.9087, 116.3975);
        coord("颐和园", 39.9990, 116.2755);
        coord("天坛", 39.8822, 116.4066);
        coord("南锣鼓巷", 39.9370, 116.4030);
        coord("什刹海", 39.9400, 116.3830);
        coord("圆明园", 40.0080, 116.3000);
        coord("北海公园", 39.9250, 116.3890);
        coord("雍和宫", 39.9470, 116.4170);
        coord("外滩", 31.2400, 121.4900);
        coord("东方明珠", 31.2397, 121.4998);
        coord("豫园", 31.2270, 121.4920);
        coord("南京路", 31.2340, 121.4740);
        coord("宽窄巷子", 30.6690, 104.0550);
        coord("锦里", 30.6420, 104.0440);
        coord("武侯祠", 30.6440, 104.0470);
        coord("大熊猫基地", 30.7350, 104.1460);
        coord("杜甫草堂", 30.6590, 104.0300);
        coord("春熙路", 30.6530, 104.0810);
        coord("兵马俑", 34.3840, 109.2780);
        coord("大雁塔", 34.2180, 108.9640);
        coord("回民街", 34.2610, 108.9400);
        coord("城墙", 34.2600, 108.9420);
        coord("钟楼", 34.2610, 108.9420);
        coord("鼓浪屿", 24.4450, 118.0650);
        coord("中山陵", 32.0580, 118.8520);
        coord("夫子庙", 32.0210, 118.7880);
        coord("洪崖洞", 29.5660, 106.5790);
        coord("解放碑", 29.5570, 106.5770);
        coord("岳麓山", 28.1870, 112.9430);
        coord("橘子洲", 28.1930, 112.9580);
    }

    private static void coord(String name, double lat, double lng) {
        ATTRACTION_COORDS.put(name, new double[]{lat, lng});
    }

    /** 城市餐厅库（口味匹配 + 就近） */
    private static final Map<String, List<Map<String, Object>>> CITY_RESTAURANTS = new HashMap<>();
    static {
        putRest("成都",
                rest("蜀大侠", 30.6550, 104.0720, "火锅"),
                rest("小龙坎", 30.6500, 104.0800, "火锅"),
                rest("大龙燚", 30.6650, 104.0750, "火锅"),
                rest("陈麻婆豆腐", 30.6600, 104.0600, "川菜,麻婆豆腐"),
                rest("马旺子", 30.6480, 104.0680, "川菜"),
                rest("明婷饭店", 30.6700, 104.0820, "川菜,家常菜"),
                rest("龙抄手", 30.6580, 104.0700, "小吃,早餐"),
                rest("廖老妈蹄花", 30.6620, 104.0650, "小吃,蹄花"));
        putRest("北京",
                rest("全聚德", 39.9090, 116.4050, "北京烤鸭,烤鸭"),
                rest("便宜坊", 39.8910, 116.3970, "北京烤鸭,烤鸭"),
                rest("四季民福", 39.9160, 116.4100, "北京烤鸭,烤鸭"),
                rest("东来顺", 39.9370, 116.4030, "火锅,涮羊肉"),
                rest("南门涮肉", 39.8700, 116.4000, "火锅,涮羊肉"),
                rest("海底捞(北京)", 39.9080, 116.4120, "火锅"),
                rest("护国寺小吃", 39.9380, 116.3780, "小吃,早餐"),
                rest("局气", 39.9200, 116.4000, "京菜,小吃"));
        putRest("杭州",
                rest("楼外楼", 30.2485, 120.1425, "杭帮菜,西湖醋鱼,东坡肉"),
                rest("知味观", 30.2470, 120.1640, "杭帮菜,小吃,早餐"),
                rest("新白鹿", 30.2560, 120.1620, "杭帮菜,性价比"),
                rest("外婆家", 30.2430, 120.1570, "杭帮菜,家常菜"),
                rest("绿茶餐厅", 30.2680, 120.1450, "杭帮菜,绿茶饼"),
                rest("奎元馆", 30.2450, 120.1660, "面食,杭帮菜"));
        putRest("上海",
                rest("南翔馒头店", 31.2270, 121.4920, "小笼包,小吃"),
                rest("绿波廊", 31.2280, 121.4930, "本帮菜,小吃"),
                rest("上海老饭店", 31.2300, 121.4900, "本帮菜,红烧肉"),
                rest("海底捞(上海)", 31.2350, 121.4700, "火锅"),
                rest("鼎泰丰", 31.2180, 121.4600, "小笼包,点心"),
                rest("新荣记", 31.1950, 121.5000, "海鲜,台州菜"));
        putRest("西安",
                rest("老孙家泡馍", 34.2580, 108.9400, "泡馍,清真"),
                rest("春发生", 34.2560, 108.9380, "葫芦头,陕菜"),
                rest("贾三灌汤包", 34.2570, 108.9390, "小吃,灌汤包"),
                rest("biangbiang面(西安)", 34.2600, 108.9420, "面食,拉面"),
                rest("长安大牌档", 34.2520, 108.9500, "陕菜,小吃"),
                rest("海底捞(西安)", 34.2450, 108.9550, "火锅"));
        putRest("苏州",
                rest("松鹤楼", 31.3100, 120.6250, "苏帮菜,松鼠桂鱼"),
                rest("得月楼", 31.3120, 120.6230, "苏帮菜"),
                rest("同得兴", 31.3050, 120.6180, "面食,苏式面"),
                rest("朱鸿兴", 31.3080, 120.6200, "面食,小吃"));
        putRest("南京",
                rest("南京大牌档", 32.0400, 118.7900, "淮扬菜,小吃"),
                rest("鸭血粉丝汤(南京)", 32.0350, 118.7850, "小吃,鸭血粉丝"),
                rest("韩复兴", 32.0380, 118.7880, "鸭血粉丝,盐水鸭"),
                rest("绿柳居", 32.0420, 118.7920, "素食,小吃"));
        putRest("重庆",
                rest("珮姐老火锅", 29.5600, 106.5750, "火锅"),
                rest("赵二火锅", 29.5550, 106.5800, "火锅"),
                rest("山城小汤圆", 29.5620, 106.5780, "小吃,汤圆"),
                rest("花市豌杂面", 29.5580, 106.5720, "面食,小吃"));
        putRest("长沙",
                rest("火宫殿", 28.1900, 112.9750, "小吃,湘菜"),
                rest("文和友", 28.1880, 112.9780, "小龙虾,小吃"),
                rest("炊烟时代", 28.1920, 112.9800, "湘菜,小炒黄牛肉"),
                rest("茶颜悦色", 28.1910, 112.9760, "奶茶,小吃"));
        putRest("厦门",
                rest("沙茶面(厦门)", 24.4550, 118.0820, "沙茶面,小吃"),
                rest("姜母鸭(厦门)", 24.4580, 118.0850, "闽菜,姜母鸭"),
                rest("海蛎煎(厦门)", 24.4540, 118.0800, "小吃,海鲜"),
                rest("临家闽南菜", 24.4600, 118.0880, "闽菜"));
        putRest("广州",
                rest("陶陶居", 23.1250, 113.2600, "粤菜,早茶"),
                rest("点都德", 23.1280, 113.2650, "粤菜,早茶"),
                rest("广州酒家", 23.1200, 113.2700, "粤菜,早茶"),
                rest("炳胜", 23.1150, 113.2800, "粤菜"));
        putRest("武汉",
                rest("蔡林记", 30.5800, 114.2900, "热干面,小吃"),
                rest("老通城", 30.5780, 114.2880, "豆皮,小吃"),
                rest("小桃园", 30.5820, 114.2920, "煨汤,小吃"));
        putRest("三亚",
                rest("火车头万人海鲜广场", 18.2500, 109.5000, "海鲜"),
                rest("第一市场海鲜", 18.2520, 109.5050, "海鲜,小吃"),
                rest("海南菜馆(三亚)", 18.2480, 109.5100, "琼菜"));
        putRest("大理",
                rest("段公子餐厅", 25.6060, 100.2290, "滇菜,白族菜"),
                rest("喜洲粑粑(大理)", 25.6100, 100.1400, "小吃"),
                rest("梅子酒馆", 25.6050, 100.2300, "滇菜,梅子"));
        putRest("丽江",
                rest("阿妈腊排骨", 26.8700, 100.2340, "腊排骨,火锅"),
                rest("滇西小哥", 26.8720, 100.2360, "滇菜"),
                rest("纳西烤肉(丽江)", 26.8680, 100.2320, "烧烤,小吃"));
        putRest("哈尔滨",
                rest("老厨家", 45.7700, 126.6250, "东北菜,锅包肉"),
                rest("东方饺子王", 45.7720, 126.6280, "饺子"),
                rest("薛府一品酱骨", 45.7680, 126.6300, "东北菜"));
    }

    private static final List<String> FOOD_KEYWORDS = List.of(
            "火锅", "川菜", "湘菜", "粤菜", "杭帮菜", "苏帮菜", "鲁菜", "闽菜", "徽菜",
            "北京烤鸭", "烤鸭", "涮羊肉", "麻辣烫", "串串", "烧烤", "烤肉", "烤鱼",
            "小龙虾", "生煎", "小笼包", "拉面", "牛肉面", "米线", "螺蛳粉", "酸菜鱼",
            "麻婆豆腐", "东坡肉", "西湖醋鱼", "叫花鸡", "龙井虾仁", "佛跳墙",
            "披萨", "汉堡", "牛排", "日料", "寿司", "刺身", "韩餐", "炸鸡", "西餐",
            "东南亚菜", "泰国菜", "素食", "清真", "早茶", "小吃", "夜宵", "海鲜", "粥", "茶餐厅"
    );

    private static Map<String, Object> rest(String name, double lat, double lng, String tags) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("lat", lat);
        m.put("lng", lng);
        m.put("tags", tags);
        return m;
    }

    private static void putRest(String city, Map<String, Object>... items) {
        CITY_RESTAURANTS.put(city, List.of(items));
    }

    // 常见中国城市关键词
    private static final List<String> CITY_KEYWORDS = List.of(
        "北京", "上海", "杭州", "成都", "西安", "苏州", "南京", "重庆", "长沙", "厦门",
        "三亚", "大理", "丽江", "广州", "武汉", "哈尔滨", "深圳", "青岛", "大连", "昆明",
        "贵阳", "拉萨", "乌鲁木齐", "呼和浩特", "南宁", "海口", "珠海", "无锡", "宁波", "合肥",
        "福州", "济南", "郑州", "太原", "南昌", "长春", "沈阳", "石家庄", "银川", "兰州",
        "西宁", "三亚"
    );

    /**
     * 执行内联规划 - 根据行程数据生成活动安排
     */
    public Map<String, Object> planTrip(Trip trip) {
        log.info("开始内联规划: tripId={}, rawInput={}", trip.getId(), trip.getRawInput());

        String rawInput = trip.getRawInput() != null ? trip.getRawInput() : "";
        String city = CityOwnershipUtils.extractCity(rawInput);
        if (city == null || city.isBlank()) {
            city = "";
        }
        List<String> mentionedPlaces = extractPlaces(rawInput, city);

        // 计算天数
        int totalDays = 1;
        if (trip.getTimeStart() != null && trip.getTimeEnd() != null) {
            totalDays = (int) Math.max(1, Duration.between(trip.getTimeStart(), trip.getTimeEnd()).toDays());
            // 如果开始和结束是同一天但时间不同，按半天算
            if (totalDays <= 0) {
                long hours = Duration.between(trip.getTimeStart(), trip.getTimeEnd()).toHours();
                totalDays = hours >= 4 ? 1 : 1;
            }
        }

        log.info("规划参数: city={}, totalDays={}, mentionedPlaces={}", city, totalDays, mentionedPlaces);

        // 获取城市景点（禁止用 rawInput 子串反向覆盖已识别城市）
        List<Map<String, Object>> attractions = city != null && !city.isBlank()
                ? CITY_ATTRACTIONS.getOrDefault(city, List.of())
                : List.of();
        if (attractions.isEmpty() && (city == null || city.isBlank())) {
            for (String knownCity : CITY_ATTRACTIONS.keySet()) {
                String inferred = CityOwnershipUtils.extractCity(rawInput);
                if (knownCity.equals(inferred)) {
                    attractions = CITY_ATTRACTIONS.get(knownCity);
                    city = knownCity;
                    break;
                }
            }
        }

        // 如果仍无景点，使用默认通用景点（不硬编码杭州）
        if (attractions.isEmpty()) {
            if (city == null || city.isBlank()) {
                city = "本地";
            }
            attractions = List.of(
                Map.of("name", "市中心", "type", "scenic", "duration", 120),
                Map.of("name", "当地美食街", "type", "shopping", "duration", 90),
                Map.of("name", "城市公园", "type", "scenic", "duration", 60)
            );
        }

        // 去重 + 就近排序 + 跨城过滤
        attractions = dedupePlaces(attractions);
        attractions = filterAttractionsByCity(attractions, city);
        attractions = sortByProximity(attractions, mentionedPlaces);

        // 口味偏好
        String pref = extractFoodPreference(rawInput);

        // 生成活动安排
        List<Map<String, Object>> activities = generateActivities(city, attractions, mentionedPlaces, totalDays, pref, trip.getPace());

        // 生成路线
        List<Map<String, Object>> routes = generateRoutes(activities);

        // 生成统计（含时长/景点数，与 TripStats 展示字段对齐）
        Map<String, Object> stats = buildInlineStats(activities, totalDays, city);

        // 构建结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activities", activities);
        result.put("routes", routes);
        result.put("stats", stats);

        log.info("内联规划完成: tripId={}, activities={}, days={}", trip.getId(), activities.size(), totalDays);
        return result;
    }

    /**
     * 汇总内联规划统计信息
     */
    private Map<String, Object> buildInlineStats(
            List<Map<String, Object>> activities, int totalDays, String city) {
        int visitDuration = 0;
        int transitDuration = 0;
        int mealDuration = 0;
        Map<String, Long> placeCount = new LinkedHashMap<>();
        placeCount.put("must", 0L);
        placeCount.put("recommended", 0L);
        placeCount.put("optional", 0L);

        for (Map<String, Object> act : activities) {
            int duration = toIntQuiet(act.getOrDefault("duration_min", act.getOrDefault("durationMin", 0)));
            int travel = toIntQuiet(act.getOrDefault("travel_duration_min", act.getOrDefault("travelTimeMin", 0)));
            String type = String.valueOf(act.getOrDefault("activity_type", act.getOrDefault("type", "visit")));

            if ("transit".equals(type)) {
                transitDuration += duration + travel;
            } else if ("meal".equals(type)) {
                mealDuration += duration;
                transitDuration += travel;
            } else {
                visitDuration += duration;
                transitDuration += travel;
                String priority = String.valueOf(act.getOrDefault("priority", "recommended"));
                placeCount.merge(priority, 1L, Long::sum);
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDurationMin", visitDuration + transitDuration + mealDuration);
        stats.put("visitDurationMin", visitDuration);
        stats.put("transitDurationMin", transitDuration);
        stats.put("mealDurationMin", mealDuration);
        stats.put("bufferDurationMin", 0);
        stats.put("placeCount", placeCount);
        stats.put("totalActivities", activities.size());
        stats.put("totalDays", totalDays);
        stats.put("city", city);
        stats.put("planner", "inline");
        return stats;
    }

    private int toIntQuiet(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从用户输入中提取城市名（排除路名误判，无结果不默认杭州）
     */
    private String extractCity(String rawInput) {
        String city = CityOwnershipUtils.extractCity(rawInput);
        if (city != null && !city.isBlank()) {
            return city;
        }
        for (String known : CITY_ATTRACTIONS.keySet()) {
            if (rawInput.contains(known)) {
                return known;
            }
        }
        return "";
    }

    /** 过滤明确属于其他城市的景点 */
    private List<Map<String, Object>> filterAttractionsByCity(List<Map<String, Object>> attractions, String city) {
        if (attractions == null || attractions.isEmpty() || city == null || city.isBlank()) {
            return attractions;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> a : attractions) {
            String name = String.valueOf(a.getOrDefault("name", ""));
            if (CityOwnershipUtils.belongsToCity(name, city)) {
                kept.add(a);
            } else {
                log.warn("内联规划过滤跨城景点: '{}' 归属={} city={}",
                        name, CityOwnershipUtils.ownerCityOfPlace(name), city);
            }
        }
        return kept.isEmpty() ? attractions : kept;
    }

    /**
     * 从用户输入中提取提到的地点（仅保留目标城市景点）
     */
    private List<String> extractPlaces(String rawInput, String city) {
        List<String> places = new ArrayList<>();
        // 引号内容
        String[] parts = rawInput.split("[「」《》\"']");
        for (int i = 1; i < parts.length; i += 2) {
            if (!parts[i].isBlank()) {
                places.add(parts[i].trim());
            }
        }
        // 只扫描目标城市景点库，避免混入他城
        List<Map<String, Object>> pool = city != null && !city.isBlank()
                ? CITY_ATTRACTIONS.getOrDefault(city, List.of())
                : List.of();
        for (Map<String, Object> a : pool) {
            String n = String.valueOf(a.get("name"));
            if (rawInput.contains(n) && !places.contains(n)) {
                places.add(n);
            }
        }
        // 全局已知景点反查归属，不属于目标城市的丢弃
        if (city != null && !city.isBlank()) {
            places = places.stream()
                    .filter(p -> CityOwnershipUtils.belongsToCity(p, city))
                    .collect(java.util.stream.Collectors.toList());
        }
        return places;
    }

    /** 从原文提取想吃的东西 */
    private String extractFoodPreference(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) return "";
        String lower = rawInput.toLowerCase();
        List<String> hits = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:想吃|爱吃|喜欢吃|要吃|去吃|品尝|吃一顿)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})")
                .matcher(rawInput);
        while (m.find()) {
            String seg = m.group(1);
            for (String kw : FOOD_KEYWORDS) {
                if (seg.contains(kw)) {
                    hits.add(kw);
                    break;
                }
            }
        }
        for (String kw : FOOD_KEYWORDS) {
            if (lower.contains(kw.toLowerCase()) && !hits.contains(kw)) {
                hits.add(kw);
            }
        }
        if (hits.isEmpty()) return "";
        return String.join("/", new LinkedHashSet<>(hits));
    }

    private List<Map<String, Object>> dedupePlaces(List<Map<String, Object>> places) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> p : places) {
            String n = String.valueOf(p.getOrDefault("name", "")).trim();
            if (n.isEmpty()) continue;
            boolean dup = false;
            for (String prev : seen) {
                String a = n.replaceAll("\\s+", "");
                String b = prev.replaceAll("\\s+", "");
                if (a.equals(b) || (a.length() >= 3 && (a.contains(b) || b.contains(a)))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                seen.add(n);
                out.add(p);
            }
        }
        return out;
    }

    /** 就近贪心排序：提到的优先种子，其余按与上一地点距离升序 */
    private List<Map<String, Object>> sortByProximity(List<Map<String, Object>> places, List<String> mentioned) {
        if (places == null || places.size() <= 1) return places;
        List<Map<String, Object>> remaining = new ArrayList<>(places);
        List<Map<String, Object>> ordered = new ArrayList<>();

        Map<String, double[]> coordCache = new HashMap<>();
        for (Map<String, Object> p : remaining) {
            double[] c = ATTRACTION_COORDS.get(String.valueOf(p.get("name")));
            if (c != null) coordCache.put(String.valueOf(p.get("name")), c);
        }

        Map<String, Object> seed = remaining.get(0);
        if (mentioned != null) {
            for (Map<String, Object> p : remaining) {
                String n = String.valueOf(p.get("name"));
                if (mentioned.stream().anyMatch(m -> n.contains(m) || m.contains(n))) {
                    seed = p;
                    break;
                }
            }
        }
        ordered.add(seed);
        remaining.remove(seed);
        double[] last = coordCache.get(String.valueOf(seed.get("name")));

        while (!remaining.isEmpty()) {
            Map<String, Object> nearest = remaining.get(0);
            double best = Double.MAX_VALUE;
            for (Map<String, Object> p : remaining) {
                String n = String.valueOf(p.get("name"));
                boolean mentionedHit = mentioned != null
                        && mentioned.stream().anyMatch(m -> n.contains(m) || m.contains(n));
                double[] c = coordCache.get(n);
                double d;
                if (last == null || c == null) {
                    d = mentionedHit ? -1 : remaining.indexOf(p);
                } else {
                    d = geoDistance(last[0], last[1], c[0], c[1]) - (mentionedHit ? 10000 : 0);
                }
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            ordered.add(nearest);
            remaining.remove(nearest);
            double[] c = coordCache.get(String.valueOf(nearest.get("name")));
            if (c != null) last = c;
        }
        return ordered;
    }

    private double geoDistance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Map<String, Object> pickRestaurant(String city, String preference,
                                               Double prevLat, Double prevLng, Set<String> used) {
        List<Map<String, Object>> pool = CITY_RESTAURANTS.getOrDefault(city, List.of());
        if (pool.isEmpty()) return null;
        String pref = preference == null ? "" : preference.toLowerCase();
        List<String> prefs = pref.isEmpty() ? List.of() : Arrays.asList(pref.split("[/,、]"));

        Map<String, Object> best = null;
        double bestScore = Double.MAX_VALUE;
        for (Map<String, Object> r : pool) {
            String name = String.valueOf(r.get("name"));
            if (used != null && used.contains(name)) continue;
            String tags = String.valueOf(r.getOrDefault("tags", "")).toLowerCase();
            boolean tagMatch = false;
            for (String p : prefs) {
                if (p.isBlank()) continue;
                if (tags.contains(p) || name.toLowerCase().contains(p)) {
                    tagMatch = true;
                    break;
                }
            }
            double dist = 0;
            if (prevLat != null && prevLng != null) {
                dist = geoDistance(prevLat, prevLng,
                        ((Number) r.get("lat")).doubleValue(),
                        ((Number) r.get("lng")).doubleValue());
            }
            double score = dist + (tagMatch ? 0 : 50000);
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        if (best != null && used != null) used.add(String.valueOf(best.get("name")));
        return best;
    }

    private String mealDisplayName(String mealType, String city, String preference,
                                   Double prevLat, Double prevLng, Set<String> used) {
        Map<String, Object> r = pickRestaurant(city, preference, prevLat, prevLng, used);
        String label;
        if (r != null) {
            label = String.valueOf(r.get("name"));
        } else {
            label = (preference == null || preference.isBlank()) ? city + "美食" : preference;
        }
        return mealType.equals("lunch") ? ("午餐·" + label) : ("晚餐·" + label);
    }

    /**
     * 生成每日活动安排
     */
    private List<Map<String, Object>> generateActivities(
            String city,
            List<Map<String, Object>> attractions,
            List<String> mentionedPlaces,
            int totalDays,
            String preference,
            String paceCode
    ) {
        List<Map<String, Object>> allActivities = new ArrayList<>();
        int seq = 1;

        // 按优先级排序：用户提到的 > 默认
        List<Map<String, Object>> sortedAttractions = new ArrayList<>(attractions);
        if (!mentionedPlaces.isEmpty()) {
            sortedAttractions.sort((a, b) -> {
                String nameA = (String) a.get("name");
                String nameB = (String) b.get("name");
                boolean mentionedA = mentionedPlaces.stream().anyMatch(p -> nameA.contains(p));
                boolean mentionedB = mentionedPlaces.stream().anyMatch(p -> nameB.contains(p));
                return Boolean.compare(mentionedB, mentionedA);
            });
        }

        // 每天安排的景点数按活动频率：紧凑 4-6、适中 3-5、宽松 2-3
        String paceKey = paceCode == null ? "moderate" : paceCode.trim().toLowerCase();
        int minPerDay;
        int maxPerDay;
        switch (paceKey) {
            case "compact" -> { minPerDay = 4; maxPerDay = 6; }
            case "relaxed" -> { minPerDay = 2; maxPerDay = 3; }
            default -> { minPerDay = 3; maxPerDay = 5; }
        }
        int attractionsPerDay = Math.max(minPerDay,
                Math.min(maxPerDay, sortedAttractions.size() / Math.max(1, totalDays)));
        int attractionIdx = 0;
        Set<String> usedRestaurants = new HashSet<>();
        Double lastLat = null;
        Double lastLng = null;

        for (int day = 1; day <= totalDays; day++) {
            boolean hadLunch = false;
            // 添加交通活动
            Map<String, Object> transit = new LinkedHashMap<>();
            transit.put("id", "act" + seq++);
            transit.put("seq", seq - 1);
            transit.put("poi_name", city + "站/机场");
            transit.put("activity_type", "transit");
            transit.put("priority", "must");
            transit.put("duration_min", 30);
            transit.put("scheduled_start", "08:00");
            transit.put("scheduled_end", "08:30");
            transit.put("day", day);
            transit.put("status", "scheduled");
            allActivities.add(transit);

            LocalTime currentTime = LocalTime.of(9, 0);

            // 安排景点
            for (int i = 0; i < attractionsPerDay && attractionIdx < sortedAttractions.size(); i++) {
                Map<String, Object> attraction = sortedAttractions.get(attractionIdx++);
                int duration = (int) attraction.getOrDefault("duration", 90);
                String type = (String) attraction.getOrDefault("type", "scenic");

                // 确定活动类型
                String activityType = switch (type) {
                    case "temple" -> "visit";
                    case "shopping" -> "shopping";
                    case "museum" -> "visit";
                    case "park" -> "visit";
                    default -> "visit";
                };

                // 添加间隔时间
                if (!currentTime.equals(LocalTime.of(9, 0))) {
                    currentTime = currentTime.plusMinutes(30); // 30分钟间隔
                }

                LocalTime startTime = currentTime;
                LocalTime endTime = startTime.plusMinutes(duration);

                // 添加活动
                Map<String, Object> activity = new LinkedHashMap<>();
                activity.put("id", "act" + seq++);
                activity.put("seq", seq - 1);
                activity.put("poi_name", (String) attraction.get("name"));
                activity.put("activity_type", activityType);
                activity.put("priority", "must");
                activity.put("duration_min", duration);
                activity.put("scheduled_start", startTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                activity.put("scheduled_end", endTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                // 下一段路程稍后由 generateRoutes/坐标估算；此处用静态坐标估交通时长
                activity.put("travel_duration_min", estimateNextTravelMin(activity, seq > 1 ? allActivities : List.of()));
                activity.put("day", day);
                activity.put("status", "scheduled");
                // 写入静态坐标便于前端展示与距离计算
                double[] staticCoord = ATTRACTION_COORDS.get(String.valueOf(attraction.get("name")));
                if (staticCoord != null) {
                    activity.put("lat", staticCoord[0]);
                    activity.put("lng", staticCoord[1]);
                }
                allActivities.add(activity);

                double[] c = staticCoord;
                if (c != null) {
                    lastLat = c[0];
                    lastLng = c[1];
                }

                currentTime = endTime;

                // 在中午添加午餐（每天一次）
                if (!hadLunch && currentTime.isAfter(LocalTime.of(11, 30)) && currentTime.isBefore(LocalTime.of(13, 0))) {
                    Map<String, Object> lunch = new LinkedHashMap<>();
                    lunch.put("id", "act" + seq++);
                    lunch.put("seq", seq - 1);
                    String lunchName = mealDisplayName("lunch", city, preference, lastLat, lastLng, usedRestaurants);
                    lunch.put("poi_name", lunchName);
                    lunch.put("activity_type", "meal");
                    lunch.put("priority", "must");
                    lunch.put("duration_min", 60);
                    lunch.put("scheduled_start", currentTime.format(DateTimeFormatter.ofPattern("HH:mm")));
                    lunch.put("scheduled_end", currentTime.plusMinutes(60).format(DateTimeFormatter.ofPattern("HH:mm")));
                    lunch.put("travel_duration_min", estimateTravelToName(activity, lunchName, 10));
                    lunch.put("day", day);
                    lunch.put("status", "scheduled");
                    double[] lunchCoord = ATTRACTION_COORDS.get(lunchName);
                    if (lunchCoord == null) lunchCoord = CITY_RESTAURANTS.getOrDefault(city, List.of()).stream()
                            .filter(r -> String.valueOf(r.get("name")).equals(lunchName))
                            .findFirst()
                            .map(r -> new double[]{((Number) r.get("lat")).doubleValue(), ((Number) r.get("lng")).doubleValue()})
                            .orElse(null);
                    if (lunchCoord != null) {
                        lunch.put("lat", lunchCoord[0]);
                        lunch.put("lng", lunchCoord[1]);
                    }
                    allActivities.add(lunch);
                    currentTime = currentTime.plusMinutes(60);
                    hadLunch = true;
                }
            }

            // 添加晚餐（每天一次）
            if (currentTime.isAfter(LocalTime.of(17, 0))) {
                Map<String, Object> dinner = new LinkedHashMap<>();
                dinner.put("id", "act" + seq++);
                dinner.put("seq", seq - 1);
                String dinnerName = mealDisplayName("dinner", city, preference, lastLat, lastLng, usedRestaurants);
                dinner.put("poi_name", dinnerName);
                dinner.put("activity_type", "meal");
                dinner.put("priority", "must");
                dinner.put("duration_min", 60);
                dinner.put("scheduled_start", "18:00");
                dinner.put("scheduled_end", "19:00");
                Map<String, Object> prev = allActivities.isEmpty() ? null : allActivities.get(allActivities.size() - 1);
                dinner.put("travel_duration_min", prev != null
                        ? estimateTravelToName(prev, dinnerName, 10) : 10);
                dinner.put("day", day);
                dinner.put("status", "scheduled");
                double[] dinnerCoord = CITY_RESTAURANTS.getOrDefault(city, List.of()).stream()
                        .filter(r -> String.valueOf(r.get("name")).equals(dinnerName))
                        .findFirst()
                        .map(r -> new double[]{((Number) r.get("lat")).doubleValue(), ((Number) r.get("lng")).doubleValue()})
                        .orElse(null);
                if (dinnerCoord != null) {
                    dinner.put("lat", dinnerCoord[0]);
                    dinner.put("lng", dinnerCoord[1]);
                }
                allActivities.add(dinner);
            }
        }

        return allActivities;
    }

    /** 目的地名称 → 坐标（剥离餐次前缀后查静态表 / 餐厅库） */
    private double[] lookupCoordByName(String name) {
        if (name == null || name.isEmpty()) return null;
        String k = name.replaceFirst("^(早餐|午餐|晚餐|早饭|午饭|晚饭|夜宵)[·・\\.\\-—_\\s]+", "");
        double[] c = ATTRACTION_COORDS.get(k);
        if (c != null) return c;
        for (List<Map<String, Object>> rests : CITY_RESTAURANTS.values()) {
            for (Map<String, Object> r : rests) {
                String rn = String.valueOf(r.get("name"));
                if (k.equals(rn) || name.equals(rn)) {
                    return new double[]{((Number) r.get("lat")).doubleValue(), ((Number) r.get("lng")).doubleValue()};
                }
            }
        }
        return null;
    }

    /** 前一活动 → 目标名称 的公交估算分钟（有坐标则直线/20km/h） */
    private int estimateTravelToName(Map<String, Object> from, String toName, int fallbackMin) {
        double[] a = from != null ? coordOf(from) : null;
        double[] b = lookupCoordByName(toName);
        if (a == null || b == null) return fallbackMin;
        double km = com.tripplanner.common.util.GeoUtils.distanceKm(a[0], a[1], b[0], b[1]);
        if (km <= 0) return fallbackMin;
        return Math.max(5, (int) Math.ceil(km / 20.0 * 60.0));
    }

    /** 当前活动到上一活动的交通时长（首个活动返回默认 15） */
    private int estimateNextTravelMin(Map<String, Object> current, List<Map<String, Object>> prevActivities) {
        if (prevActivities == null || prevActivities.isEmpty()) return 15;
        Map<String, Object> prev = prevActivities.get(prevActivities.size() - 1);
        String toName = String.valueOf(current.getOrDefault("poi_name", ""));
        return estimateTravelToName(prev, toName, 15);
    }

    /**
     * 生成路线信息
     */
    private List<Map<String, Object>> generateRoutes(List<Map<String, Object>> activities) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < activities.size() - 1; i++) {
            Map<String, Object> from = activities.get(i);
            Map<String, Object> to = activities.get(i + 1);

            Map<String, Object> route = new LinkedHashMap<>();
            route.put("from", from.get("poi_name"));
            route.put("to", to.get("poi_name"));
            // 真实坐标直线距离（km），禁止随机假距离
            route.put("distance_km", legDistanceKm(from, to));
            route.put("duration_min", (int) from.getOrDefault("travel_duration_min", estimateLegMinutes(from, to)));
            route.put("mode", "transit");
            routes.add(route);
        }
        return routes;
    }

    /** 两活动间直线距离（km）；无坐标返回 0 */
    private double legDistanceKm(Map<String, Object> from, Map<String, Object> to) {
        double[] a = coordOf(from);
        double[] b = coordOf(to);
        if (a == null || b == null) return 0.0;
        return com.tripplanner.common.util.GeoUtils.distanceKm(a[0], a[1], b[0], b[1]);
    }

    /** 活动坐标：优先 lat/lng 字段，其次静态表按名称查 */
    private double[] coordOf(Map<String, Object> act) {
        try {
            Object lat = act.get("lat");
            Object lng = act.get("lng");
            if (lat != null && lng != null) {
                return new double[]{Double.parseDouble(String.valueOf(lat)), Double.parseDouble(String.valueOf(lng))};
            }
        } catch (Exception ignored) {}
        String name = String.valueOf(act.getOrDefault("poi_name", act.getOrDefault("name", "")));
        double[] c = ATTRACTION_COORDS.get(name);
        if (c != null) return c; // [lat, lng]
        return null;
    }

    /** 无 travel_duration_min 时按直线距离估算公交分钟数（约 20km/h 含候车） */
    private int estimateLegMinutes(Map<String, Object> from, Map<String, Object> to) {
        double km = legDistanceKm(from, to);
        if (km <= 0) return 10;
        return Math.max(5, (int) Math.ceil(km / 20.0 * 60.0));
    }
}
