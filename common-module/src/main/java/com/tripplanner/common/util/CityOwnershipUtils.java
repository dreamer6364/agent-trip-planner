package com.tripplanner.common.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 城市识别与景点归属校验工具。
 * 防止「北京路→北京」类子串误判，以及跨城景点混入行程。
 */
public final class CityOwnershipUtils {

    private CityOwnershipUtils() {
    }

    /** 按长度降序，优先最长匹配 */
    private static final List<String> CITIES = new ArrayList<>(List.of(
            "乌鲁木齐", "呼和浩特", "石家庄", "哈尔滨",
            "北京", "上海", "广州", "深圳", "成都", "杭州", "西安", "苏州", "南京",
            "重庆", "长沙", "厦门", "三亚", "大理", "丽江", "武汉", "青岛", "昆明",
            "洛阳", "天津", "郑州", "合肥", "福州", "贵阳", "南宁", "太原", "济南",
            "南昌", "沈阳", "长春", "拉萨", "银川", "西宁", "兰州", "宁波", "无锡",
            "桂林", "敦煌", "珠海", "汕头", "唐山", "保定", "大同", "榆林", "汉中"
    ));

    static {
        CITIES.sort(Comparator.comparingInt(String::length).reversed());
    }

    /** 城市名后紧跟这些后缀时，多半是外城路名（如广州「北京路」、上海「南京路」） */
    private static final String[] ROAD_SUFFIXES = {"路", "街", "大道", "巷", "里", "口", "桥"};

    /** 景点名 → 所属城市（权威归属，用于过滤跨城混入） */
    private static final Map<String, String> PLACE_TO_CITY = new LinkedHashMap<>();

    static {
        putCity("北京", "故宫", "故宫博物院", "天安门", "天安门广场", "长城", "八达岭长城",
                "颐和园", "天坛", "圆明园", "北海公园", "南锣鼓巷", "王府井", "什刹海",
                "雍和宫", "798艺术区", "香山", "景山公园", "前门大街", "鸟巢", "水立方");
        putCity("上海", "外滩", "东方明珠", "豫园", "城隍庙", "南京路", "南京路步行街",
                "田子坊", "迪士尼", "上海迪士尼", "陆家嘴", "新天地", "朱家角", "武康路");
        putCity("杭州", "西湖", "灵隐寺", "雷峰塔", "断桥", "河坊街", "龙井", "龙井村",
                "千岛湖", "西溪湿地", "宋城", "九溪烟树", "苏堤", "白堤", "钱塘江");
        putCity("成都", "宽窄巷子", "锦里", "武侯祠", "杜甫草堂", "大熊猫基地",
                "成都大熊猫繁育研究基地", "春熙路", "太古里", "青城山", "都江堰", "人民公园");
        putCity("西安", "兵马俑", "秦始皇兵马俑", "秦始皇兵马俑博物馆", "大雁塔", "华清宫",
                "钟楼", "鼓楼", "回民街", "西安城墙", "城墙", "陕西历史博物馆", "大唐不夜城");
        putCity("苏州", "拙政园", "虎丘", "留园", "平江路", "寒山寺", "周庄", "金鸡湖",
                "苏州博物馆", "山塘街");
        putCity("南京", "中山陵", "明孝陵", "夫子庙", "秦淮河", "总统府", "玄武湖",
                "侵华日军南京大屠杀遇难同胞纪念馆", "南京博物院", "鸡鸣寺");
        putCity("重庆", "洪崖洞", "解放碑", "磁器口", "长江索道", "南山", "朝天门",
                "武隆天生三桥", "李子坝", "鹅岭二厂");
        putCity("长沙", "橘子洲", "岳麓山", "岳麓书院", "太平街", "坡子街", "湖南省博物馆",
                "世界之窗", "长沙海底世界");
        putCity("厦门", "鼓浪屿", "厦门大学", "南普陀寺", "曾厝垵", "环岛路", "中山路",
                "日光岩", "胡里山炮台", "集美学村");
        putCity("三亚", "亚龙湾", "天涯海角", "蜈支洲岛", "南山寺", "三亚湾", "海棠湾",
                "大东海", "鹿回头");
        putCity("大理", "大理古城", "洱海", "崇圣寺三塔", "双廊", "苍山", "喜洲", "蝴蝶泉");
        putCity("丽江", "丽江古城", "玉龙雪山", "束河", "泸沽湖", "黑龙潭", "木府");
        putCity("广州", "广州塔", "小蛮腰", "沙面", "珠江", "白云山", "北京路", "陈家祠",
                "越秀公园", "长隆", "沙面岛");
        putCity("武汉", "黄鹤楼", "东湖", "武汉大学", "户部巷", "江汉路", "晴川阁",
                "湖北省博物馆", "归元寺");
        putCity("哈尔滨", "中央大街", "圣索菲亚教堂", "冰雪大世界", "太阳岛", "松花江",
                "老道外", "果戈里大街");
        putCity("青岛", "栈桥", "八大关", "崂山", "五四广场", "青岛啤酒博物馆", "金沙滩",
                "天主教堂");
        putCity("昆明", "滇池", "石林", "翠湖", "西山", "金殿", "云南民族村", "官渡古镇");
        putCity("洛阳", "龙门石窟", "白马寺", "洛阳博物馆", "老君山", "丽景门", "洛阳牡丹园");
        putCity("深圳", "世界之窗", "欢乐谷", "大梅沙", "小梅沙", "深圳湾", "东部华侨城",
                "莲花山", "梧桐山");
        putCity("黄山", "黄山", "宏村", "西递", "屯溪老街", "徽州古城");
        putCity("张家界", "张家界国家森林公园", "天门山", "玻璃桥", "武陵源", "黄龙洞");
        putCity("桂林", "漓江", "象鼻山", "阳朔", "西街", "龙脊梯田", "两江四湖", "七星公园");
        putCity("敦煌", "莫高窟", "鸣沙山", "月牙泉", "玉门关", "阳关", "雅丹地质公园");
        putCity("拉萨", "布达拉宫", "大昭寺", "八廓街", "纳木错", "色拉寺", "哲蚌寺");
        putCity("无锡", "鼋头渚", "灵山", "拈花湾", "锡惠公园", "南禅寺");
        putCity("宁波", "天一阁", "老外滩", "溪口", "象山影视城", "东钱湖");
        putCity("天津", "五大道", "意式风情区", "瓷房子", "天津之眼", "古文化街", "盘山");
        putCity("郑州", "少林寺", "嵩山", "河南博物院", "黄河风景名胜区", "二七纪念塔");
        putCity("合肥", "包公园", "逍遥津", "徽园", "巢湖", "三河古镇");
        putCity("福州", "三坊七巷", "鼓山", "西湖公园", "烟台山", "平潭");
        putCity("南昌", "滕王阁", "八一广场", "秋水广场", "梅岭", "万寿宫");
        putCity("贵阳", "青岩古镇", "黔灵山", "甲秀楼", "花溪公园", "黄果树瀑布");
        putCity("南宁", "青秀山", "南宁园博园", "扬美古镇", "德天瀑布", "北海银滩");
        putCity("太原", "晋祠", "乔家大院", "平遥古城", "蒙山", "双塔寺");
        putCity("济南", "趵突泉", "大明湖", "千佛山", "芙蓉街", "泉城广场");
        putCity("沈阳", "故宫", "沈阳故宫", "张氏帅府", "北陵", "东陵", "中街");
        putCity("长春", "伪满皇宫", "净月潭", "长影世纪城", "南湖公园");
        putCity("乌鲁木齐", "天山", "大巴扎", "国际大巴扎", "天池", "南山牧场");
        putCity("银川", "西夏王陵", "镇北堡西部影城", "贺兰山", "沙湖");
        putCity("西宁", "塔尔寺", "青海湖", "茶卡盐湖", "东关清真大寺");
        putCity("兰州", "中山桥", "白塔山", "黄河铁桥", "甘肃省博物馆", "水车博览园");
    }

    private static void putCity(String city, String... places) {
        for (String p : places) {
            PLACE_TO_CITY.putIfAbsent(p, city);
        }
    }

    /** 从输入识别城市；优先显式城市名，排除「北京路」类路名误判 */
    public static String extractCity(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return null;
        }
        String text = rawInput;

        // 1) 显式城市词（最长优先），排除后接路/街后缀的伪城市
        for (String city : CITIES) {
            int idx = text.indexOf(city);
            while (idx >= 0) {
                if (!isRoadNameMatch(text, idx, city)) {
                    return city;
                }
                idx = text.indexOf(city, idx + city.length());
            }
        }

        // 2) 景点名反查
        for (Map.Entry<String, String> e : PLACE_TO_CITY.entrySet()) {
            if (text.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 匹配处是否像「北京路」这类外城路名 */
    private static boolean isRoadNameMatch(String text, int idx, String city) {
        int end = idx + city.length();
        if (end >= text.length()) {
            return false;
        }
        String rest = text.substring(end);
        for (String suffix : ROAD_SUFFIXES) {
            if (rest.startsWith(suffix)) {
                // 「北京市/北京游玩」不是路名；「北京路/北京街」是
                return true;
            }
        }
        return false;
    }

    /** 景点名归属城市；未知返回 null */
    public static String ownerCityOfPlace(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return null;
        }
        String name = placeName.trim();
        String direct = PLACE_TO_CITY.get(name);
        if (direct != null) {
            return direct;
        }
        // 去掉餐次前缀：「午餐·陈麻婆豆腐」
        int dot = name.indexOf('·');
        if (dot >= 0 && dot < name.length() - 1) {
            name = name.substring(dot + 1).trim();
            direct = PLACE_TO_CITY.get(name);
            if (direct != null) {
                return direct;
            }
        }
        // 最长包含匹配
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : PLACE_TO_CITY.entrySet()) {
            String key = e.getKey();
            if (key.length() > bestLen && name.contains(key)) {
                best = e.getValue();
                bestLen = key.length();
            }
        }
        return best;
    }

    /**
     * 景点是否允许出现在目标城市行程中。
     * 归属未知 → 允许；归属明确且不等于目标城市 → 拒绝。
     */
    public static boolean belongsToCity(String placeName, String targetCity) {
        if (targetCity == null || targetCity.isBlank()) {
            return true;
        }
        String owner = ownerCityOfPlace(placeName);
        if (owner == null) {
            return true;
        }
        return owner.equals(targetCity) || targetCity.startsWith(owner) || owner.startsWith(targetCity);
    }

    /** 过滤掉明确属于其他城市的景点名列表 */
    public static List<String> filterNamesForCity(List<String> names, String targetCity) {
        if (names == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (belongsToCity(n, targetCity)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * VERIFY_CITY 节点：过滤 visit 类跨城景点。
     * meal/transit/buffer 不校验；目标城市为空时原样返回。
     *
     * @return kept=保留项，rejected=被剔除的景点名，visitTotal=参与校验的 visit 总数
     */
    public static <T> CityVerifyResult<T> filterVisitsForCity(
            List<T> items,
            java.util.function.Function<T, String> nameFn,
            java.util.function.Function<T, String> typeFn,
            String targetCity) {
        if (items == null) {
            return new CityVerifyResult<>(List.of(), List.of(), 0);
        }
        if (targetCity == null || targetCity.isBlank()) {
            return new CityVerifyResult<>(new ArrayList<>(items), List.of(), 0);
        }
        List<T> kept = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int visitTotal = 0;
        for (T item : items) {
            String name = nameFn.apply(item);
            String type = typeFn.apply(item);
            boolean isVisit = type == null
                    || (!"meal".equals(type) && !"transit".equals(type)
                        && !"buffer".equals(type) && !"transport".equals(type));
            if (isVisit && name != null && !name.isBlank()) {
                visitTotal++;
                if (!belongsToCity(name, targetCity)) {
                    rejected.add(name);
                    continue;
                }
            }
            kept.add(item);
        }
        return new CityVerifyResult<>(kept, rejected, visitTotal);
    }

    /**
     * 全类型跨城过滤（visit + meal 均校验，仅 transit/buffer/transport 跳过）。
     * 归属明确且与目标城市不符 → 剔除；归属未知 → 放行；目标城市为空 → 原样返回。
     * 用于「餐段不得混入外城餐厅」场景（ownerCityOfPlace 已支持剥「午餐·」前缀）。
     *
     * @return kept=保留项，rejected=被剔除的名称，visitTotal=参与校验的条目数
     */
    public static <T> CityVerifyResult<T> filterActivitiesForCity(
            List<T> items,
            java.util.function.Function<T, String> nameFn,
            java.util.function.Function<T, String> typeFn,
            String targetCity) {
        if (items == null) {
            return new CityVerifyResult<>(List.of(), List.of(), 0);
        }
        if (targetCity == null || targetCity.isBlank()) {
            return new CityVerifyResult<>(new ArrayList<>(items), List.of(), 0);
        }
        List<T> kept = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int checkedTotal = 0;
        for (T item : items) {
            String name = nameFn.apply(item);
            String type = typeFn.apply(item);
            boolean isTransport = "transit".equals(type) || "buffer".equals(type)
                    || "transport".equals(type);
            if (!isTransport && name != null && !name.isBlank()) {
                checkedTotal++;
                if (!belongsToCity(name, targetCity)) {
                    rejected.add(name);
                    continue;
                }
            }
            kept.add(item);
        }
        return new CityVerifyResult<>(kept, rejected, checkedTotal);
    }

    /** VERIFY_CITY 过滤结果 */
    public record CityVerifyResult<T>(List<T> kept, List<String> rejected, int visitTotal) {
        /** 超过一半 visit 被剔除时视为校验失败 */
        public boolean isOverwhelmed() {
            return visitTotal > 0 && rejected.size() * 2 > visitTotal;
        }

        public String rejectedSummary() {
            return String.join("、", rejected);
        }
    }
}
