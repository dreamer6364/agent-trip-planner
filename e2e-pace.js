/* E2E: 活动频率三档验证 —— 紧凑/适中/宽松 各建一次 2 日行程，校验每日游览时长与景点数 */
const BASE = 'http://localhost:8086';
const { execFileSync } = require('child_process');

const CASES = [
  { pace: 'compact', expect: { minHours: 8, maxHours: 10, minVisits: 4, maxVisits: 6 } },
  { pace: 'moderate', expect: { minHours: 6, maxHours: 8, minVisits: 3, maxVisits: 5 } },
  { pace: 'relaxed', expect: { minHours: 3, maxHours: 5, minVisits: 2, maxVisits: 3 } },
];

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
  const out = execFileSync('mysql', ['-h127.0.0.1', '-P3306', '-uroot', '-p12345678mxy',
    '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], { encoding: 'utf8' }).trim();
  return JSON.parse(out);
}
function loadPace(tripId) {
  const sql = `select pace from trip_planner.trips where id='${tripId}';`;
  return execFileSync('mysql', ['-h127.0.0.1', '-P3306', '-uroot', '-p12345678mxy',
    '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], { encoding: 'utf8' }).trim();
}

(async () => {
  const login = await req('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'admin@tripplanner.com', password: 'Admin@123456' }),
  });
  const token = login.data?.data?.accessToken || login.data?.accessToken;
  if (!token) { console.error('LOGIN_FAIL'); process.exit(1); }
  const auth = { Authorization: `Bearer ${token}` };

  const problems = [];
  const summary = [];

  const only = process.env.PACE;
  const cases = only ? CASES.filter(c => c.pace === only) : CASES;
  for (const c of cases) {
    console.log('\n========== pace=' + c.pace + ' ==========');
    const t0 = Date.now();
    const create = await req('/api/trips', {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({
        title: `活动频率验证-${c.pace}`,
        rawInput: '想去成都玩两天，想看熊猫，也想逛逛老街和博物馆',
        timeStart: '2026-11-10T08:00:00',
        timeEnd: '2026-11-11T20:00:00',
        transportMode: 'mixed',
        pace: c.pace,
        preferences: {},
      }),
    });
    const trip = create.data?.data;
    if (!trip?.id) {
      console.error('CREATE_FAIL', create.status, JSON.stringify(create.data).slice(0, 600));
      problems.push(c.pace + ': create failed');
      continue;
    }
    console.log('tripId=' + trip.id);

    let status = '';
    const deadline = Date.now() + 300000;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 4000));
      const d = await req(`/api/trips/${trip.id}`, { headers: auth });
      status = d.data?.data?.status;
      if (status === 'completed' || status === 'failed') break;
    }
    const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
    console.log('status=' + status + ' elapsed=' + elapsed + 's');
    if (status !== 'completed') { problems.push(c.pace + ': status=' + status); continue; }

    const dbPace = loadPace(trip.id);
    console.log('db.pace=' + dbPace + ' (expect ' + c.pace + ')');
    if (dbPace !== c.pace) problems.push(c.pace + ': db pace=' + dbPace);

    const acts = loadActs(trip.id);
    const byDay = {};
    for (const a of acts) (byDay[a.day || 1] = byDay[a.day || 1] || []).push(a);

    const days = Object.keys(byDay).sort((x, y) => x - y);
    for (const day of days) {
      const list = byDay[day];
      list.sort((x, y) => toMin(x.scheduled_start) - toMin(y.scheduled_start));
      let visitMin = 0, visitCnt = 0;
      for (const a of list) {
        const t = a.activity_type || a.type;
        if (t !== 'meal' && t !== 'transit' && t !== 'rest') { visitMin += Number(a.duration_min || 0); visitCnt++; }
      }
      const inBand = visitMin >= c.expect.minHours * 60 - 30 && visitMin <= c.expect.maxHours * 60;
      const cntOk = visitCnt >= c.expect.minVisits && visitCnt <= c.expect.maxVisits;
      console.log('day' + day + ' acts=' + list.length
        + ' visits=' + visitCnt + ' [' + c.expect.minVisits + '~' + c.expect.maxVisits + ']'
        + ' visitHours=' + (visitMin / 60).toFixed(1) + 'h [' + c.expect.minHours + '~' + c.expect.maxHours + ']'
        + ' band=' + (inBand ? 'OK' : 'OUT') + ' cnt=' + (cntOk ? 'OK' : 'OUT'));
      summary.push(`${c.pace} day${day}: ${visitCnt}景点 / ${(visitMin / 60).toFixed(1)}h`);

      if (visitMin > c.expect.maxHours * 60) problems.push(c.pace + ' day' + day + ': over budget ' + visitMin + 'min > ' + (c.expect.maxHours * 60));
      if (visitCnt < c.expect.minVisits) problems.push(c.pace + ' day' + day + ': visits ' + visitCnt + ' < ' + c.expect.minVisits);
      if (visitMin < c.expect.minHours * 60 - 30) problems.push(c.pace + ' day' + day + ': under floor ' + visitMin + 'min < ' + (c.expect.minHours * 60 - 30));
      if (visitCnt > c.expect.maxVisits + 2) problems.push(c.pace + ' day' + day + ': visits ' + visitCnt + ' > ' + (c.expect.maxVisits + 2));

      for (const a of list) {
        const name = a.poi_name || a.name || '';
        if (/自由活动|市区漫步|待定景点|待定餐厅/.test(name)) problems.push(c.pace + ' day' + day + ': placeholder ' + name);
      }
      const meals = list.filter(a => (a.activity_type || a.type) === 'meal');
      if (!meals.some(m => String(m.poi_name).includes('午'))) problems.push(c.pace + ' day' + day + ': no lunch');
      if (!meals.some(m => String(m.poi_name).includes('晚'))) problems.push(c.pace + ' day' + day + ': no dinner');

      for (let i = 1; i < list.length; i++) {
        const p = list[i - 1], cur = list[i];
        const tt = Number(p.travel_duration_min || 0);
        const need = toMin(p.scheduled_end) + tt;
        const got = toMin(cur.scheduled_start);
        if (got < need) problems.push(c.pace + ' day' + day + ' TIME: ' + cur.poi_name + ' start=' + cur.scheduled_start + ' < ' + fmt(need));
      }
      const last = list[list.length - 1];
      if (toMin(last.scheduled_end) > 21 * 60) problems.push(c.pace + ' day' + day + ': ends ' + last.scheduled_end);
      if (visitCnt <= c.expect.maxVisits) {
        console.log('  ' + list.map(a => a.scheduled_start + ' ' + a.poi_name + '(' + a.duration_min + ')').join(' | '));
      }
    }
  }

  console.log('\n---SUMMARY---');
  console.log(summary.join('\n'));
  console.log('\n---PROBLEMS---');
  if (problems.length) { console.log(problems.join('\n')); console.log('RESULT=FAIL'); process.exit(1); }
  console.log('none');
  console.log('RESULT=PASS');
})();
