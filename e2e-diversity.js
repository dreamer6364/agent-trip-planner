/* E2E: 大理4日 — 多元化 + 真实路程时间约束校验（读 DB 最新版本） */
const BASE = 'http://localhost:8086';
const { execFileSync } = require('child_process');
const dbEnv = require('./db-env');

async function req(path, opts = {}) {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: { 'Content-Type': 'application/json;charset=UTF-8', ...(opts.headers || {}) },
  });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { raw: text }; }
  return { status: res.status, data };
}

function toMin(t) {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(t || '').trim());
  return m ? (+m[1]) * 60 + (+m[2]) : 0;
}
function fmt(m) {
  return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
}
function loadActs(tripId) {
  const sql = `select activities from trip_planner.trip_versions where trip_id='${tripId}' order by created_at desc limit 1;`;
  const out = execFileSync('mysql', [...dbEnv.args(),
    '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], { encoding: 'utf8' }).trim();
  return JSON.parse(out);
}

(async () => {
  const login = await req('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'admin@tripplanner.com', password: 'Admin@123456' }),
  });
  const token = login.data?.data?.accessToken || login.data?.accessToken;
  if (!token) { console.error('LOGIN_FAIL'); process.exit(1); }
  const auth = { Authorization: `Bearer ${token}` };

  const rawInput = '想去大理玩四天，喜欢自然风光和人文历史，也想逛逛古城的夜市';
  const t0 = Date.now();
  const create = await req('/api/trips', {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({
      title: '大理四日多元行程验证2',
      rawInput,
      timeStart: '2026-11-01T08:00:00',
      timeEnd: '2026-11-04T20:00:00',
      transportMode: 'mixed',
      preferences: {},
    }),
  });
  const trip = create.data?.data;
  if (!trip?.id) { console.error('CREATE_FAIL', create.status, JSON.stringify(create.data).slice(0, 800)); process.exit(1); }
  console.log('tripId=' + trip.id);

  let status = '';
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 4000));
    const d = await req(`/api/trips/${trip.id}`, { headers: auth });
    status = d.data?.data?.status;
    if (status === 'completed' || status === 'failed') break;
  }
  console.log('status=' + status + ' elapsed=' + ((Date.now() - t0) / 1000).toFixed(1) + 's');
  if (status !== 'completed') { console.log('RESULT=FAIL(status)'); process.exit(1); }

  const acts = loadActs(trip.id);
  console.log('ACT_COUNT=' + acts.length);

  const byDay = {};
  for (const a of acts) (byDay[a.day || 1] = byDay[a.day || 1] || []).push(a);

  const problems = [];
  const placeholders = [];
  const visits = [];
  const mealReport = [];

  for (const a of acts) {
    const name = a.poi_name || a.name || '';
    if (/自由活动|市区漫步|待定景点|待定餐厅|\(\d+-\d+\)/.test(name)) placeholders.push(name);
    const t = a.activity_type || a.type;
    if (t !== 'meal' && t !== 'transit') visits.push(name);
  }

  for (const [day, list] of Object.entries(byDay)) {
    list.sort((x, y) => toMin(x.scheduled_start) - toMin(y.scheduled_start));
    console.log('day' + day + ' acts=' + list.length);
    for (const a of list) {
      console.log('  ' + a.scheduled_start + '-' + a.scheduled_end + ' | ' + a.activity_type
        + ' | ' + a.poi_name + ' | tt=' + (a.travel_duration_min ?? '-'));
    }
    const meals = list.filter(a => a.activity_type === 'meal');
    const lunch = meals.some(m => String(m.poi_name).includes('午'));
    const dinner = meals.some(m => String(m.poi_name).includes('晚'));
    mealReport.push(day + ' lunch=' + lunch + ' dinner=' + dinner);
    if (!lunch) problems.push('day' + day + ': no lunch');
    if (!dinner) problems.push('day' + day + ': no dinner');
    for (let i = 1; i < list.length; i++) {
      const p = list[i - 1], c = list[i];
      const tt = Number(p.travel_duration_min || 0);
      const need = toMin(p.scheduled_end) + tt;
      const got = toMin(c.scheduled_start);
      if (got < need) problems.push('day' + day + ' TIME: ' + c.poi_name + ' start=' + c.scheduled_start
        + ' < prevEnd=' + p.scheduled_end + ' + travel=' + tt + ' => ' + fmt(need));
    }
    const last = list[list.length - 1];
    if (toMin(last.scheduled_end) > 21 * 60) problems.push('day' + day + ': ends ' + last.scheduled_end);
  }

  const cat = n => {
    if (/博物馆|纪念馆|展览|博物院|文化展厅|美术馆/.test(n)) return 'museum';
    if (/寺|庙|宫|塔|教堂/.test(n)) return 'temple';
    if (/街|市场|广场|商城|夜市|古城/.test(n)) return 'shopping';
    if (/公园|湿地|花园|植物园/.test(n)) return 'park';
    return 'scenic';
  };
  const cats = new Set(visits.map(cat));
  console.log('---DIVERSITY---');
  console.log('visits=' + visits.map(v => cat(v) + ':' + v).join(' | '));
  console.log('categories=' + [...cats].join(','));
  if (!cats.has('museum')) problems.push('diversity: no 人文/博物馆类');
  if (!cats.has('shopping')) problems.push('diversity: no 商圈/夜市/步行街类');
  if (cats.size < 3) problems.push('diversity: only ' + cats.size + ' categories');

  console.log('---PLACEHOLDER---');
  console.log(placeholders.length ? placeholders.join('\n') : 'none');
  if (placeholders.length) problems.push('placeholders=' + placeholders.length);

  console.log('---MEALS---');
  console.log(mealReport.join('\n'));

  console.log('---PROBLEMS---');
  if (problems.length) { console.log(problems.join('\n')); console.log('RESULT=FAIL'); process.exit(1); }
  console.log('none');
  console.log('RESULT=PASS');
})();
