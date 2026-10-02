// 午/晚餐作息校验：多用例端到端（创建 → 规划 → 轮询 → 断言）
// 用例覆盖：不同城市 / 天数 / 节奏，断言午餐、晚餐时刻落在人的作息窗口内
const BASE = 'http://localhost:8086';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;

function check(label, cond, detail) {
  if (cond) { pass++; console.log(`  [PASS] ${label}`); }
  else { fail++; console.log(`  [FAIL] ${label} :: ${detail}`); }
}

async function j(method, path, body, token) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch (e) { data = { _raw: text.slice(0, 300) }; }
  return { status: res.status, data };
}

const toMin = (s) => {
  if (!s) return -1;
  const m = String(s).match(/(\d{1,2}):(\d{2})/);
  return m ? (+m[1]) * 60 + (+m[2]) : -1;
};
const hm = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

// 作息规则（宽松可执行口径）
const RULES = {
  lunchStart: { min: 11 * 60, max: 13 * 60 + 30 },   // 午餐 11:00-13:30 起
  dinnerStart: { min: 17 * 60, max: 19 * 60 + 30 },  // 晚餐 17:00-19:30 起
  mealGapMin: 90,                                     // 午餐结束 → 晚餐开始 ≥ 90 分钟
  dayEnd: 21 * 60,
};

const CASES = [
  {
    name: 'caseA-杭州3天moderate',
    body: {
      title: 'verify-meals-A',
      rawInput: '杭州3天：西湖、断桥、雷峰塔、河坊街、灵隐寺、西溪湿地、南宋御街、宋城、龙井村、太子湾公园，每天3个景点，公共交通',
      timeStart: '2026-11-05T08:00:00', timeEnd: '2026-11-07T20:00:00',
      transportMode: 'public', pace: 'moderate',
    },
  },
  {
    name: 'caseB-长白山2天relaxed',
    body: {
      title: 'verify-meals-B',
      rawInput: '长白山2天：长白山瀑布、地下森林、绿渊潭、温泉群、长白山博物馆，节奏宽松，自驾',
      timeStart: '2026-11-10T08:00:00', timeEnd: '2026-11-11T20:00:00',
      transportMode: 'drive', pace: 'relaxed',
    },
  },
  {
    name: 'caseC-北京3天compact',
    body: {
      title: 'verify-meals-C',
      rawInput: '北京3天：故宫、天安门广场、颐和园、天坛、八达岭长城、南锣鼓巷、什刹海、鸟巢、清华北大，紧凑节奏，地铁出行',
      timeStart: '2026-11-15T08:00:00', timeEnd: '2026-11-17T20:00:00',
      transportMode: 'public', pace: 'compact',
    },
  },
  {
    name: 'caseD-成都2天moderate',
    body: {
      title: 'verify-meals-D',
      rawInput: '成都2天：宽窄巷子、锦里、武侯祠、杜甫草堂、青羊宫、春熙路、太古里、大熊猫繁育基地，公共交通',
      timeStart: '2026-11-20T09:00:00', timeEnd: '2026-11-21T20:00:00',
      transportMode: 'public', pace: 'moderate',
    },
  },
];

function evaluateCase(name, acts) {
  console.log(`== ${name} ==`);
  const byDay = new Map();
  for (const a of acts) {
    const d = a.day || 1;
    if (!byDay.has(d)) byDay.set(d, []);
    byDay.get(d).push(a);
  }
  for (const [day, list] of [...byDay].sort((x, y) => x[0] - y[0])) {
    list.sort((a, b) => toMin(a.scheduled_start) - toMin(b.scheduled_start));
    console.log(`  -- day ${day} --`);
    for (const a of list) {
      console.log(`     ${a.scheduled_start}-${a.scheduled_end} ${(a.activity_type || '').padEnd(6)} ${(a.poi_name || '').slice(0, 16)}`);
    }
    const meals = list.filter(a => a.activity_type === 'meal');
    const lunch = meals.find(a => (a.poi_name || '').includes('午'));
    const dinner = meals.find(a => (a.poi_name || '').includes('晚'));

    check(`day${day} 有午餐`, !!lunch, '缺午餐');
    check(`day${day} 有晚餐`, !!dinner, '缺晚餐');

    if (lunch) {
      const s = toMin(lunch.scheduled_start);
      check(`day${day} 午餐起点 ${lunch.scheduled_start} ∈ [11:00,13:30]`,
        s >= RULES.lunchStart.min && s <= RULES.lunchStart.max,
        `超出作息窗口`);
    }
    if (dinner) {
      const s = toMin(dinner.scheduled_start);
      check(`day${day} 晚餐起点 ${dinner.scheduled_start} ∈ [17:00,19:30]`,
        s >= RULES.dinnerStart.min && s <= RULES.dinnerStart.max,
        `超出作息窗口`);
    }
    if (lunch && dinner) {
      const gap = toMin(dinner.scheduled_start) - toMin(lunch.scheduled_end);
      check(`day${day} 午→晚间隔 ${gap}min ≥ ${RULES.mealGapMin}`,
        gap >= RULES.mealGapMin, `间隔过近（午${lunch.scheduled_start}-${lunch.scheduled_end} 晚${dinner.scheduled_start}）`);
    }
    // 相邻活动重叠检查
    for (let i = 1; i < list.length; i++) {
      const prevEnd = toMin(list[i - 1].scheduled_end);
      const curStart = toMin(list[i].scheduled_start);
      if (curStart < prevEnd) {
        check(`day${day} 无时间重叠`, false,
          `${list[i - 1].poi_name}(${list[i - 1].scheduled_start}-${list[i - 1].scheduled_end}) 与 ${list[i].poi_name}(${list[i].scheduled_start}) 重叠`);
        break;
      }
    }
    const last = list[list.length - 1];
    check(`day${day} 收口 ${last.scheduled_end} ≤ 21:00`, toMin(last.scheduled_end) <= RULES.dayEnd, `超出21:00`);
  }
}

(async () => {
  const only = process.argv[2];
  const cases = only ? CASES.filter(c => c.name.includes(only)) : CASES;
  const login = await j('POST', '/api/auth/login', { email: 'admin@tripplanner.com', password: 'Admin@123456' });
  const token = login.data?.data?.accessToken;
  if (!token) { console.log('LOGIN_FAIL'); process.exit(1); }

  for (const c of cases) {
    const t0 = Date.now();
    const create = await j('POST', '/api/trips', c.body, token);
    const trip = create.data?.data;
    if (!trip?.id) { console.log(`${c.name} CREATE_FAIL ` + JSON.stringify(create.data).slice(0, 300)); fail++; continue; }
    const kick = await j('POST', `/api/trips/${trip.id}/plan`, null, token);
    let last = null;
    for (let i = 0; i < 70; i++) {
      await sleep(5000);
      const g = await j('GET', `/api/trips/${trip.id}`, null, token);
      const t = g.data?.data;
      const st = t?.status;
      if (st === 'planned' || st === 'completed') { last = t; break; }
      if (st === 'failed') { break; }
    }
    const elapsed = Math.round((Date.now() - t0) / 1000);
    if (!last) { console.log(`${c.name} PLAN_TIMEOUT/FAIL (${elapsed}s)`); fail++; continue; }
    const acts = last.activities || (last.latestVersion && last.latestVersion.activities) || [];
    if (!acts.length) { console.log(`${c.name} NO_ACTIVITIES (${elapsed}s)`); fail++; continue; }
    console.log(`${c.name} planned in ${elapsed}s, ${acts.length} acts`);
    evaluateCase(c.name, acts);
  }

  console.log(`\nRESULT: pass=${pass} fail=${fail}`);
  process.exit(fail > 0 ? 1 : 0);
})();
