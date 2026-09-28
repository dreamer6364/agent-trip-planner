package com.tripplanner.trip.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 景点个性化签名服务
 *
 * 按景点生成专属签名（slogan），三级生成策略：
 * 1. 词库精确/模糊匹配：同景多签，按 (seed + 景点名) 哈希稳定轮换 —— 同一行程内恒定，跨行程各不相同；
 * 2. 名称特征模板：词库未覆盖时按名称关键词（寺/湖/山/塔/街…）套用带 {name} 的模板；
 * 3. 类型兜底：按 activityType 返回通用签名。
 *
 * 另提供 {@link #isGeneric} 判定 LLM 产出的泛化文案（如「游览西湖」「雷峰塔」），
 * 供读取链路用词库签名替换。
 *
 * @author TripForge Team
 * @since 1.16.0
 */
@Service
public class SloganService {

    /** 景点 → 签名数组（同景多签，下标由 seed 哈希轮换） */
    private static final Map<String, String[]> SLOGANS = Map.ofEntries(
        // === 北京 ===
        Map.entry("北京首都国际机场", new String[]{"万里之行，始于此门"}),
        Map.entry("天安门广场", new String[]{"神州第一街，祖国心脏", "看一次升旗，热一次眼眶"}),
        Map.entry("天安门", new String[]{"城楼之上看变迁", "红墙内外，两个时代"}),
        Map.entry("天坛", new String[]{"与天对话的神圣殿堂", "祈年殿前，听见回音"}),
        Map.entry("故宫博物院", new String[]{"穿越六百年的紫禁城", "一砖一瓦，皆是王朝"}),
        Map.entry("故宫", new String[]{"红墙黄瓦间的帝王梦", "转过宫门，就是六百年"}),
        Map.entry("八达岭长城", new String[]{"不到长城非好汉", "好汉坡上，山河万里"}),
        Map.entry("慕田峪长城", new String[]{"长城静处，草木皆兵"}),
        Map.entry("圆明园", new String[]{"万园之园的百年沧桑", "废墟之上，勿忘来路"}),
        Map.entry("颐和园", new String[]{"皇家园林的山水画卷", "昆明湖畔，长廊入画"}),
        Map.entry("北海公园", new String[]{"白塔映碧水，皇城后花园", "让我们荡起双桨"}),
        Map.entry("景山公园", new String[]{"俯瞰紫禁城的最佳视角", "万春亭上，一览紫禁"}),
        Map.entry("什刹海", new String[]{"胡同深处的老北京记忆", "银锭桥边看西山"}),
        Map.entry("南锣鼓巷", new String[]{"胡同文化的活化石", "拐进胡同，撞见老北京"}),
        Map.entry("王府井大街", new String[]{"百年老街，购物天堂"}),
        Map.entry("王府井", new String[]{"百年老街，购物天堂", "王府井里的人来人往"}),
        Map.entry("798艺术区", new String[]{"工业遗址上的艺术新生", "烟囱底下全是灵感"}),
        Map.entry("鸟巢", new String[]{"钢铁编织的奥运梦想", "鸟巢夜色，钢铁柔情"}),
        Map.entry("水立方", new String[]{"蓝色水晶宫的水上奇迹"}),
        Map.entry("雍和宫", new String[]{"香火鼎盛的皇家寺院", "雍和香火，愿有所得"}),
        Map.entry("恭王府", new String[]{"一座恭王府，半部清代史"}),
        Map.entry("前门大街", new String[]{"京味儿十足的商业长廊"}),
        Map.entry("国家博物馆", new String[]{"五千年文明的殿堂", "一日看尽五千年"}),
        Map.entry("中国美术馆", new String[]{"艺术的海洋，美的殿堂"}),
        Map.entry("回酒店", new String[]{"旅途中的温馨港湾"}),

        // === 杭州 ===
        Map.entry("西湖", new String[]{"淡妆浓抹总相宜", "一半湖山一半城", "泛舟湖上，才懂江南"}),
        Map.entry("灵隐寺", new String[]{"千年古刹，禅意悠然", "云林深处，木鱼声声"}),
        Map.entry("雷峰塔", new String[]{"白娘子的千年守望", "塔影斜阳，湖光入梦"}),
        Map.entry("河坊街", new String[]{"南宋御街的市井烟火", "一街灯火，满嘴杭帮味"}),
        Map.entry("龙井村", new String[]{"一杯龙井，满山茶香", "茶山之上，春风十里"}),
        Map.entry("千岛湖", new String[]{"千岛碧水画中游", "千座岛屿，一湖星河"}),
        Map.entry("断桥", new String[]{"白蛇传里的浪漫邂逅", "断桥不断，情意相连"}),
        Map.entry("西溪湿地", new String[]{"城市中的世外桃源", "摇橹穿芦荡，城市藏野趣"}),
        Map.entry("宋城", new String[]{"给我一天，还你千年"}),
        Map.entry("九溪烟树", new String[]{"溪水潺潺，烟雨朦胧", "九溪十八涧，一步一涧"}),
        Map.entry("三潭印月", new String[]{"西湖之心，三潭印月"}),
        Map.entry("苏堤", new String[]{"苏堤春晓，柳浪闻莺"}),
        Map.entry("楼外楼", new String[]{"山外青山楼外楼", "一桌杭帮菜，半部西湖史"}),

        // === 上海 ===
        Map.entry("外滩", new String[]{"万国建筑博览群", "外滩夜色，浦江流金"}),
        Map.entry("东方明珠", new String[]{"上海腾飞的象征", "明珠之上，魔都尽收"}),
        Map.entry("豫园", new String[]{"江南园林的精致典雅", "大隐于市的江南园"}),
        Map.entry("城隍庙", new String[]{"老上海的烟火人间"}),
        Map.entry("南京路", new String[]{"中华商业第一街", "南京路上，霓虹不夜"}),
        Map.entry("田子坊", new String[]{"弄堂里的艺术天地"}),
        Map.entry("迪士尼", new String[]{"点亮心中奇梦", "烟火升起时，童话成真"}),
        Map.entry("新天地", new String[]{"石库门里的摩登时光"}),
        Map.entry("陆家嘴", new String[]{"金融城的璀璨明珠", "三件套下，江风正好"}),
        Map.entry("中华艺术宫", new String[]{"世博记忆的艺术殿堂"}),

        // === 成都 ===
        Map.entry("宽窄巷子", new String[]{"老成都的生活样本", "宽巷子闲，窄巷子慢"}),
        Map.entry("锦里", new String[]{"西蜀第一街", "红灯笼下的老成都夜"}),
        Map.entry("武侯祠", new String[]{"三国文化的圣地", "丞相祠堂何处寻"}),
        Map.entry("大熊猫基地", new String[]{"国宝的快乐家园", "看滚滚营业的一天"}),
        Map.entry("杜甫草堂", new String[]{"诗圣故居，文学圣地", "茅屋秋风里，诗声千年"}),
        Map.entry("春熙路", new String[]{"成都时尚的脉搏"}),
        Map.entry("青城山", new String[]{"青城天下幽", "幽幽青城，道法自然"}),
        Map.entry("都江堰", new String[]{"千年水利奇迹", "深淘滩低作堰，江水自此安"}),
        Map.entry("金沙遗址", new String[]{"古蜀文明的神秘面纱", "太阳神鸟，旋转千年"}),
        Map.entry("人民公园", new String[]{"成都慢生活的缩影", "盖碗茶里泡一天"}),

        // === 西安 ===
        Map.entry("兵马俑", new String[]{"世界第八大奇迹", "千年军阵，仍待出征", "陶土之师，守护华夏"}),
        Map.entry("大雁塔", new String[]{"唐代高僧的译经圣地", "大雁塔下，诗会长安"}),
        Map.entry("华清宫", new String[]{"千年温泉，帝妃情缘", "一池温汤，半部唐史"}),
        Map.entry("回民街", new String[]{"舌尖上的长安味道", "羊肉泡馍，长安一整天"}),
        Map.entry("城墙", new String[]{"十三朝古都的守护者", "骑行古城墙，环看长安"}),
        Map.entry("陕西历史博物馆", new String[]{"华夏宝库，国之重器", "三秦瑰宝，一日看尽"}),
        Map.entry("钟楼", new String[]{"晨钟暮鼓，古城心跳", "钟楼四望，四方来风"}),
        Map.entry("鼓楼", new String[]{"鼓声悠扬，回荡千年"}),
        Map.entry("大唐不夜城", new String[]{"梦回大唐的璀璨之夜", "不夜城里，灯火如昼"}),
        Map.entry("碑林博物馆", new String[]{"石质书库，书法圣地"}),

        // === 苏州 ===
        Map.entry("拙政园", new String[]{"苏州园林甲天下", "移步换景，方寸江南"}),
        Map.entry("虎丘", new String[]{"吴中第一名胜", "虎丘剑池，吴中第一"}),
        Map.entry("留园", new String[]{"园林之韵，方寸之间"}),
        Map.entry("平江路", new String[]{"小桥流水，吴侬软语", "平江路上，摇橹声慢"}),
        Map.entry("寒山寺", new String[]{"姑苏城外寒山寺", "夜半钟声，客船已远"}),
        Map.entry("山塘街", new String[]{"七里山塘到虎丘", "七里山塘，半入姑苏"}),
        Map.entry("周庄", new String[]{"中国第一水乡"}),
        Map.entry("同里", new String[]{"醇正水乡，旧时江南"}),

        // === 南京 ===
        Map.entry("中山陵", new String[]{"天下为公，长眠于此", "三百九十二级，步步敬意"}),
        Map.entry("夫子庙", new String[]{"千年文脉，秦淮灯火", "桨声灯影里的秦淮"}),
        Map.entry("明孝陵", new String[]{"明清皇家第一陵", "神道石象，六百年蹲守"}),
        Map.entry("总统府", new String[]{"中国近代史的缩影"}),
        Map.entry("玄武湖", new String[]{"金陵明珠，城市绿肺"}),
        Map.entry("秦淮河", new String[]{"桨声灯影里的旧梦", "画舫过处，旧梦金陵"}),
        Map.entry("侵华日军南京大屠杀遇难同胞纪念馆", new String[]{"铭记历史，珍爱和平"}),
        Map.entry("灵谷寺", new String[]{"深山古寺，灵秀之地"}),

        // === 重庆 ===
        Map.entry("洪崖洞", new String[]{"现实版的千与千寻", "洪崖洞的灯火，像跌进动画", "吊脚楼群，山城夜名片"}),
        Map.entry("解放碑", new String[]{"重庆的城市名片", "碑下人潮，渝中心跳"}),
        Map.entry("磁器口", new String[]{"千年古镇，磁器口的记忆", "磁器口的麻花香，飘过老街"}),
        Map.entry("长江索道", new String[]{"空中走廊，跨江而行", "过江，不走桥走索道"}),
        Map.entry("武隆天坑", new String[]{"大地的裂缝，自然的奇迹"}),
        Map.entry("大足石刻", new String[]{"石刻艺术的巅峰之作"}),
        Map.entry("南山一棵树", new String[]{"山城夜景的最佳观景台"}),
        Map.entry("朝天门", new String[]{"两江汇流，城市之门", "两江交汇，半城烟火"}),

        // === 长沙 ===
        Map.entry("岳麓山", new String[]{"惟楚有材，于斯为盛", "爱晚亭边，枫叶知秋"}),
        Map.entry("橘子洲", new String[]{"独立寒秋，湘江北去", "橘子洲头，看湘江北去"}),
        Map.entry("太平街", new String[]{"长沙古城的缩影"}),
        Map.entry("湖南省博物馆", new String[]{"马王堆的千年传奇", "辛追夫人，千年一梦"}),
        Map.entry("天心阁", new String[]{"古城长沙的历史见证"}),
        Map.entry("世界之窗", new String[]{"世界风情，欢乐天堂"}),
        Map.entry("火宫殿", new String[]{"长沙美食的圣地"}),

        // === 厦门 ===
        Map.entry("鼓浪屿", new String[]{"海上花园，钢琴之岛", "琴声绕过转角的三角梅", "屿见鼓浪，听浪与琴"}),
        Map.entry("南普陀寺", new String[]{"闽南佛教圣地"}),
        Map.entry("厦门大学", new String[]{"中国最美大学", "芙蓉隧道里，青春涂鸦"}),
        Map.entry("曾厝垵", new String[]{"最文艺的渔村"}),
        Map.entry("环岛路", new String[]{"最美马拉松赛道", "环海骑行，风都是咸的"}),
        Map.entry("集美学村", new String[]{"嘉庚精神，教育圣地"}),
        Map.entry("中山路", new String[]{"厦门的商业灵魂"}),

        // === 三亚 ===
        Map.entry("亚龙湾", new String[]{"天下第一湾"}),
        Map.entry("天涯海角", new String[]{"海枯石烂的誓言", "走到天涯，海角作证"}),
        Map.entry("南山寺", new String[]{"福如东海，寿比南山"}),
        Map.entry("蜈支洲岛", new String[]{"中国的马尔代夫", "潜下去，另一个三亚"}),
        Map.entry("大小洞天", new String[]{"南海仙山，道家福地"}),
        Map.entry("热带天堂森林公园", new String[]{"雨林秘境，鸟语花香"}),
        Map.entry("鹿回头", new String[]{"鹿鸣回首，情定三亚"}),

        // === 大理 ===
        Map.entry("洱海", new String[]{"风花雪月，洱海之畔", "环洱海一圈，风花雪月全", "洱海边，时间慢半拍"}),
        Map.entry("大理古城", new String[]{"苍山下，洱海边", "南诏古都，风花雪月"}),
        Map.entry("崇圣寺三塔", new String[]{"南诏佛国的千年守望", "三塔倒影，千年不移"}),
        Map.entry("苍山", new String[]{"十九峰十八溪的壮美", "苍山十九峰，峰峰有雪"}),
        Map.entry("双廊", new String[]{"苍洱风光第一镇"}),
        Map.entry("喜洲古镇", new String[]{"白族民居的活化石"}),
        Map.entry("蝴蝶泉", new String[]{"蝴蝶纷飞的浪漫传说"}),

        // === 丽江 ===
        Map.entry("丽江古城", new String[]{"纳西古韵，柔软时光", "四方街边，柔软时光"}),
        Map.entry("玉龙雪山", new String[]{"纳西圣山，银装素裹", "离赤道最近的白", "索道之上，雪线之下"}),
        Map.entry("束河古镇", new String[]{"茶马古道的宁静驿站"}),
        Map.entry("泸沽湖", new String[]{"东方女儿国的纯净之眼", "摩梭人的湖，猪槽船的歌"}),
        Map.entry("蓝月谷", new String[]{"蓝色仙境，雪山脚下", "蓝月谷的水，蓝得不像话"}),
        Map.entry("拉市海", new String[]{"候鸟天堂，高原明珠"}),
        Map.entry("木府", new String[]{"北有故宫，南有木府"}),

        // === 广州 ===
        Map.entry("广州塔", new String[]{"小蛮腰，广州新地标", "小蛮腰亮起，广州入夜"}),
        Map.entry("陈家祠", new String[]{"岭南建筑的艺术殿堂"}),
        Map.entry("沙面", new String[]{"欧陆风情的百年小岛"}),
        Map.entry("北京路", new String[]{"千年商业街的繁华"}),
        Map.entry("白云山", new String[]{"羊城第一秀"}),
        Map.entry("长隆欢乐世界", new String[]{"欢乐无限的主题乐园"}),
        Map.entry("珠江夜游", new String[]{"珠江两岸，流光溢彩", "游船过处，两岸生花"}),

        // === 武汉 ===
        Map.entry("黄鹤楼", new String[]{"天下江山第一楼", "晴川历历汉阳树", "黄鹤一去，楼还在"}),
        Map.entry("东湖", new String[]{"大城大湖，生态画卷", "东湖绿道，骑行画中"}),
        Map.entry("户部巷", new String[]{"汉味小吃第一巷", "户部巷过早，热干面开场"}),
        Map.entry("武汉大学", new String[]{"珞珈山上，樱花烂漫", "樱花大道，珞珈春信"}),
        Map.entry("归元禅寺", new String[]{"数罗汉，祈福地"}),
        Map.entry("长江大桥", new String[]{"万里长江第一桥", "桥上走一回，三镇连一线"}),
        Map.entry("汉口江滩", new String[]{"城市客厅，江景如画"}),

        // === 哈尔滨 ===
        Map.entry("冰雪大世界", new String[]{"冰城的梦幻童话", "冰雕亮起，童话开城"}),
        Map.entry("中央大街", new String[]{"百年建筑艺术长廊", "面包石响了百年"}),
        Map.entry("圣索菲亚大教堂", new String[]{"东方莫斯科的异域风情", "穹顶之下，雪落索菲亚"}),
        Map.entry("太阳岛", new String[]{"松花江上的明珠"}),
        Map.entry("东北虎林园", new String[]{"与百兽之王的亲密接触"}),
        Map.entry("松花江", new String[]{"母亲河的冬日恋歌", "江面封冻，冬泳者下水"}),
        Map.entry("哈尔滨极地馆", new String[]{"极地世界的奇妙旅程"}),

        // === 青岛 ===
        Map.entry("栈桥", new String[]{"飞阁回澜，青岛的序章", "栈桥尽头，海鸥起落"}),
        Map.entry("八大关", new String[]{"万国建筑的静谧林荫", "八大关的秋，落叶成毯"}),
        Map.entry("五四广场", new String[]{"五月的风，红了海岸"}),
        Map.entry("崂山", new String[]{"海上第一名山", "崂山顶上，山海相连"}),
        Map.entry("青岛啤酒博物馆", new String[]{"一罐啤酒的百年史", "原浆下线，青岛开怀"}),
        Map.entry("奥帆中心", new String[]{"帆船之都的启航处"}),

        // === 通用餐饮/住宿/占位 ===
        Map.entry("全聚德", new String[]{"百年炉火，烤出京城味"}),
        Map.entry("KFC", new String[]{"异乡中的熟悉味道"}),
        Map.entry("午餐", new String[]{"舌尖上的美味", "午间一餐，续能下半场"}),
        Map.entry("晚餐", new String[]{"品味人间烟火气", "夜色上桌，烟火开场"}),
        Map.entry("早餐", new String[]{"元气满满的一天从这里开始", "早起，从一口热的开始"}),
        Map.entry("火锅", new String[]{"热气腾腾的人间美味", "红油翻滚，辣得过瘾"}),
        Map.entry("烤鸭", new String[]{"皮脆肉嫩，满口留香"}),
        Map.entry("炸酱面", new String[]{"一碗面，一座城的味道"}),
        Map.entry("自由活动", new String[]{"留白，也是行程的一部分"}),
        Map.entry("市区漫步", new String[]{"随心走走，街角自有风景"})
    );

    /**
     * 名称特征模板：[0] 为逗号分隔的关键词，其余为含 {name} 的模板。
     * 词库未命中时按首个命中组轮换生成。
     */
    private static final String[][] FEATURE_TEMPLATES = {
        {"寺,庙,禅,庵,道观", "晨钟暮鼓里的{name}", "{name}，一炷香的清净"},
        {"湖,江,河,溪,瀑,泉,潭", "{name}，水色天光", "风过{name}，皱一池柔光"},
        {"海,海湾,岛,沙滩,滩,港,屿", "{name}，海风正好", "浪花把{name}写成诗"},
        {"山,峰,岭,崖,岩,谷", "登临{name}，天地入怀", "{name}，山高人为峰"},
        {"塔,楼,阁,亭,殿", "拾级{name}，望尽来处", "{name}，檐角挂着时光"},
        {"街,巷,坊,古镇,古村,集市,夜市,老城,古城,城区,里弄", "烟火气里的{name}", "{name}，慢下来刚刚好"},
        {"公园,园,苑,圃", "一步一景是{name}", "{name}，方寸之间的雅致"},
        {"博物馆,美术馆,科技馆,展览馆,纪念馆,馆,院", "走进{name}，与时光对话", "{name}，把过往收进橱窗"},
        {"广场,商业,购物中心,奥莱", "{name}，城市的人潮舞台"}
    };

    private static final String[] MEAL_TEMPLATES = {
        "把这座城市吃个明白", "{name}，一城风味落座", "人间烟火，{name}开席"
    };

    private static final Map<String, String> TYPE_SLOGANS = Map.of(
        "visit", "探索未知的精彩",
        "scenic", "大自然的鬼斧神工",
        "museum", "历史与艺术的殿堂",
        "park", "城市中的绿色氧吧",
        "temple", "心灵的宁静港湾",
        "shopping", "血拼狂欢的乐园",
        "transit", "在路上，风景在心",
        "stay", "旅途中的温馨港湾",
        "meal", "舌尖上的美味之旅"
    );

    /** 泛化文案的常见前缀动词 */
    private static final String[] GENERIC_VERBS = {
        "游览", "参观", "逛", "前往", "打卡", "漫步", "探索", "游玩", "体验", "走进",
        "品尝", "欣赏", "感受", "了解", "观", "赏", "看"
    };

    /** 词库签名全集：既有签名命中说明已是精选文案，不再替换 */
    private static final java.util.Set<String> CURATED = new java.util.HashSet<>();

    static {
        for (String[] variants : SLOGANS.values()) {
            java.util.Collections.addAll(CURATED, variants);
        }
        java.util.Collections.addAll(CURATED, MEAL_TEMPLATES);
    }

    /**
     * 生成个性化签名（无 seed，按景点名自身哈希选签）
     */
    public String generateSlogan(String poiName, String activityType) {
        return generateSlogan(poiName, activityType, null);
    }

    /**
     * 按景点生成个性化签名
     *
     * @param poiName      景点名称
     * @param activityType 活动类型
     * @param seed         轮换种子（建议传 tripId）：同一 seed 同景点签名恒定，不同 seed 可能不同
     */
    public String generateSlogan(String poiName, String activityType, String seed) {
        if (poiName == null) {
            return null;
        }

        String name = poiName.trim();
        String core = coreName(name);

        // 1. 词库：精确(全名) → 精确(去前缀) → 模糊(相互包含)，命中后按 seed 轮换多签
        String[] variants = lookup(name, core);
        if (variants != null && variants.length > 0) {
            if (variants.length == 1) {
                return variants[0];
            }
            return variants[variantIndex(core, seed, variants.length)];
        }

        String type = activityType != null ? activityType.toLowerCase() : "visit";

        // 2. 餐饮：类型模板（带景点名）
        if ("meal".equals(type)) {
            return fill(MEAL_TEMPLATES[variantIndex(core, seed, MEAL_TEMPLATES.length)], core);
        }

        // 3. 名称特征模板（根据景点名本身的意象）：取所有命中关键词中最长者，避免
        //    「青岛老城区」被「岛」误判、「小鱼山公园」被「山」误判
        String bestTemplate = null;
        int bestKeywordLen = -1;
        for (String[] group : FEATURE_TEMPLATES) {
            for (String kw : group[0].split(",")) {
                if (!kw.isEmpty() && core.contains(kw) && kw.length() > bestKeywordLen) {
                    bestKeywordLen = kw.length();
                    bestTemplate = group[1 + variantIndex(core, seed, group.length - 1)];
                }
            }
        }
        if (bestTemplate != null) {
            return fill(bestTemplate, core);
        }

        // 4. 类型兜底
        return TYPE_SLOGANS.getOrDefault(type, "探索精彩旅程");
    }

    /**
     * 判断既有 slogan 是否为泛化文案，需要按景点重新生成。
     *
     * 判定顺序：空 → 是；已是词库/餐饮模板签名 → 否；
     * 与名称（或去前缀名）相等 / 动词+名称结构（「游览西湖」）→ 是；
     * 动词+名称+后缀结构（「游览西湖美景」）→ 是；
     * 文案提及景点名（「骑行古城墙」「张生记，一城风味落座」）→ 否；
     * 动词开头或 6 字以内且未提及景点（「品尝杭帮菜」「观海景」「漫步」）→ 是。
     */
    public boolean isGeneric(String slogan, String poiName) {
        if (slogan == null || slogan.isBlank()) {
            return true;
        }
        String s = slogan.trim();
        if (CURATED.contains(s)) {
            return false;
        }
        if (poiName == null) {
            return false;
        }
        String core = coreName(poiName.trim());
        if (s.equals(poiName) || (!core.isEmpty() && s.equals(core))) {
            return true;
        }
        // 「动词 + 景点名」结构：游览西湖 / 逛河坊街
        String stripped = s;
        boolean verbStripped = false;
        for (String verb : GENERIC_VERBS) {
            if (stripped.startsWith(verb)) {
                stripped = stripped.substring(verb.length());
                verbStripped = true;
                break;
            }
        }
        stripped = stripped.replaceAll("(之行|之旅|一下|打卡)$", "").trim();
        if (stripped.equals(poiName) || (!core.isEmpty() && stripped.equals(core))) {
            return true;
        }
        // 动词+名称+后缀（游览西湖美景）；未带动词的点题句（张生记，一城风味落座）保留
        if (verbStripped && !core.isEmpty() && stripped.startsWith(core)) {
            return true;
        }
        if (!core.isEmpty() && s.contains(core)) {
            return false;
        }
        if (stripped.isEmpty() || s.length() <= 6) {
            return !core.isEmpty();
        }
        return false;
    }

    /** 去除「午餐·」「晚餐·」等前缀，返回核心景点名 */
    private String coreName(String poiName) {
        if (poiName == null) {
            return "";
        }
        String name = poiName.trim();
        int idx = name.indexOf('·');
        if (idx >= 0 && idx + 1 < name.length()) {
            return name.substring(idx + 1).trim();
        }
        return name;
    }

    private String[] lookup(String name, String core) {
        String[] hit = SLOGANS.get(name);
        if (hit != null) {
            return hit;
        }
        if (!core.equals(name)) {
            hit = SLOGANS.get(core);
            if (hit != null) {
                return hit;
            }
        }
        for (var entry : SLOGANS.entrySet()) {
            String key = entry.getKey();
            if (core.contains(key) || key.contains(core)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 按 (core + seed) 哈希稳定取下标；String.hashCode 规范保证跨 JVM 稳定 */
    private int variantIndex(String core, String seed, int size) {
        if (size <= 1) {
            return 0;
        }
        String key = (seed == null ? "" : seed) + "#" + core;
        return Math.floorMod(key.hashCode(), size);
    }

    private String fill(String template, String core) {
        return template.replace("{name}", core);
    }
}
