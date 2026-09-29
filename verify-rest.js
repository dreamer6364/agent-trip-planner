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
const g = (a, ...keys) => {
  for (const k of keys) if (a && a[k] !== undefined && a[k] !== null) return a[k];
  return undefined;
};

(async () => {
  const login = await j('POST', '/api/auth/login', { email: 'admin@tripplanner.com', password: 'Admin@123456' });
  const token = login.data?.data?.accessToken;
  if (!token) { console.log('LOGIN_FAIL'); process.exit(1); }
  console.log('LOGIN_OK');

  const create = await j('POST', '/api/trips', {
    title: 'verify-rest-3d',
    rawInput: '杭州3天：西湖、断桥、河坊街、灵隐寺、雷峰塔、西溪湿地、京杭大运河、南宋御街，每天早中晚3餐，公共交通',
    timeStart: '2026-10-15T08:00:00',
    timeEnd: '2026-10-17T20:00:00',
    transportMode: 'public',
    pace: 'moderate',
  }, token);
  const trip = create.data?.data;
  if (!trip?.id) { console.log('CREATE_FAIL ' + JSON.stringify(create).slice(0, 500)); process.exit(1); }
  console.log('TRIP_ID=' + trip.id);

  const t0 = Date.now();
  const plan = await j('POST', '/api/trips/' + trip.id + '/plan', null, token);
  console.log('PLAN_KICK=' + JSON.stringify(plan.data).slice(0, 200));

  let last = null;
  for (let i = 0; i < 58; i++) {
    await sleep(5000);
    const g0 = await j('GET', '/api/trips/' + trip.id, null, token);
    last = g0.data?.data;
    const acts = last?.activities || last?.latestVersion?.activities || [];
    console.log('poll#' + i + ' acts=' + acts.length + ' status=' + last?.status + ' elapsed=' + ((Date.now() - t0) / 1000).toFixed(0) + 's');
    if (acts.length >= 10) break;
    if (Date.now() - t0 > 280000) break;
  }

  const acts = (last?.activities || last?.latestVersion?.activities || [])
    .slice()
    .sort((x, y) => (Number(g(x, 'day', 'dayIndex', 'dayNum') || 1) - Number(g(y, 'day', 'dayIndex', 'dayNum') || 1))
      || (toMin(g(x, 'scheduled_start', 'scheduledStart', 'startTime')) - toMin(g(y, 'scheduled_start', 'scheduledStart', 'startTime'))));
  const stats = last?.latestVersion?.stats || last?.stats || {};
  const typeOf = (a) => String(g(a, 'activity_type', 'activityType') || 'visit').toLowerCase();
  const rests = acts.filter(a => typeOf(a) === 'rest');

  console.log('\n### REST-1 休息节点存在性');
  check('规划完成且活动≥10', acts.length >= 10, 'acts=' + acts.length);
  check('存在 type=rest 活动', rests.length >= 1, 'restCount=' + rests.length);
  console.log('         rest=' + rests.map(r => `${g(r, 'poi_name', 'poiName', 'name')}(${g(r, 'scheduled_start', 'startTime')}-${g(r, 'scheduled_end', 'endTime')})`).join(' | '));

  console.log('\n### REST-2 三档节奏频率（moderate 每日至多2次）');
  const byDay = {};
  for (const a of acts) {
    const d = Number(g(a, 'day', 'dayIndex', 'dayNum') || 1);
    (byDay[d] = byDay[d] || []).push(a);
  }
  for (const d of Object.keys(byDay)) {
    const cnt = byDay[d].filter(a => typeOf(a) === 'rest').length;
    check(`day${d} rest≤2`, cnt <= 2, 'cnt=' + cnt);
  }

  console.log('\n### REST-3 休息时长/命名');
  for (const r of rests) {
    const dur = Number(g(r, 'duration_min', 'durationMin') || 0);
    check(`rest 时长=20 (${g(r, 'poi_name', 'poiName', 'name')})`, dur === 20, 'dur=' + dur);
    const nm = String(g(r, 'poi_name', 'poiName', 'name') || '');
    check(`rest 名称属三档 (${nm})`, ['上午茶歇', '午后小憩', '中场休息'].includes(nm), nm);
  }

  console.log('\n### REST-4 时间不变式（start≥prevEnd+prevTravel，容差1分钟）');
  let instOk = true, instDetail = '';
  for (const d of Object.keys(byDay)) {
    const list = byDay[d].slice().sort((a, b) => toMin(g(a, 'scheduled_start', 'scheduledStart', 'startTime')) - toMin(g(b, 'scheduled_start', 'scheduledStart', 'startTime')));
    for (let i = 1; i < list.length; i++) {
      const p = list[i - 1], c = list[i];
      const pEnd = toMin(g(p, 'scheduled_end', 'scheduledEnd', 'endTime'));
      const pTravel = Number(g(p, 'travel_duration_min', 'travelDurationMin', 'travelTimeMin') || 0);
      const cStart = toMin(g(c, 'scheduled_start', 'scheduledStart', 'startTime'));
      if (pEnd < 0 || cStart < 0 || cStart + 1 < pEnd + pTravel) {
        instOk = false;
        instDetail = `day${d} ${g(p, 'poi_name', 'poiName', 'name')} end=${pEnd}+travel=${pTravel} > ${g(c, 'poi_name', 'poiName', 'name')} start=${cStart}`;
        break;
      }
    }
  }
  check('相邻活动时间不重叠且顺延', instOk, instDetail);

  console.log('\n### REST-5 休息前一活动路程归零 + 休息接管路程');
  for (const r of rests) {
    const idx = acts.indexOf(r);
    const prev = idx > 0 ? acts[idx - 1] : null;
    if (prev && Number(g(prev, 'day', 'dayIndex', 'dayNum') || 1) === Number(g(r, 'day', 'dayIndex', 'dayNum') || 1)) {
      const pTravel = Number(g(prev, 'travel_duration_min', 'travelDurationMin', 'travelTimeMin') || 0);
      check(`休息前一活动(${g(prev, 'poi_name', 'poiName', 'name')})travel=0`, pTravel === 0, 'travel=' + pTravel);
    }
    const next = idx + 1 < acts.length ? acts[idx + 1] : null;
    if (next && Number(g(next, 'day', 'dayIndex', 'dayNum') || 1) === Number(g(r, 'day', 'dayIndex', 'dayNum') || 1)) {
      const rEnd = toMin(g(r, 'scheduled_end', 'scheduledEnd', 'endTime'));
      const rTravel = Number(g(r, 'travel_duration_min', 'travelDurationMin', 'travelTimeMin') || 0);
      const nStart = toMin(g(next, 'scheduled_start', 'scheduledStart', 'startTime'));
      check(`休息(${g(r, 'poi_name', 'poiName', 'name')})接管路程 → 下一活动 start=${nStart}`, nStart + 1 >= rEnd + rTravel, `rEnd=${rEnd}+${rTravel} next=${nStart}`);
    }
  }

  console.log('\n### REST-6 休息无坐标 + 不计入景点统计');
  for (const r of rests) {
    const lat = g(r, 'lat');
    check('rest 无坐标', lat === undefined || lat === null || lat === '', 'lat=' + lat);
  }
  const visitActs = acts.filter(a => !['transit', 'meal', 'rest'].includes(typeOf(a)));
  const handVisit = visitActs.reduce((s, a) => s + Number(g(a, 'duration_min', 'durationMin') || 0), 0);
  const handRest = rests.reduce((s, a) => s + Number(g(a, 'duration_min', 'durationMin') || 0), 0);
  console.log(`         stats.visitDurationMin=${stats.visitDurationMin} handVisit=${handVisit} stats.restDurationMin=${stats.restDurationMin} handRest=${handRest}`);
  check('stats.restDurationMin>0', Number(stats.restDurationMin) > 0, stats.restDurationMin);
  check('stats.visitDurationMin 与手工验算一致（rest 不计入）', Number(stats.visitDurationMin) === handVisit, `${stats.visitDurationMin} vs ${handVisit}`);
  check('stats.restDurationMin 与手工验算一致', Number(stats.restDurationMin) === handRest, `${stats.restDurationMin} vs ${handRest}`);
  check('rest 不计入 placeCount', Number(stats.placeCount?.must ?? -1) + Number(stats.placeCount?.recommended ?? -1) + Number(stats.placeCount?.optional ?? -1) === visitActs.length,
    JSON.stringify(stats.placeCount) + ' vs visitActs=' + visitActs.length);

  console.log('\n### REST-7 当日收口≤21:00');
  for (const d of Object.keys(byDay)) {
    const list = byDay[d];
    const lastEnd = Math.max(...list.map(a => toMin(g(a, 'scheduled_end', 'scheduledEnd', 'endTime'))));
    check(`day${d} 末活动≤21:00 (${Math.floor(lastEnd / 60)}:${String(lastEnd % 60).padStart(2, '0')})`, lastEnd <= 21 * 60, 'end=' + lastEnd);
  }

  console.log(`\nRESULT: pass=${pass} fail=${fail}`);
  console.log('TRIP_ID_FOR_UI=' + trip.id);
  process.exit(fail > 0 ? 1 : 0);
})().catch(e => { console.error('ERR', e.stack || e.message); process.exit(1); });
