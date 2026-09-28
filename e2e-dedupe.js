/* UTF-8 E2E: Hangzhou day trip + lunch at Louwailou */
const BASE = 'http://localhost:8086';
const fs = require('fs');

async function req(path, opts = {}) {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      ...(opts.headers || {}),
    },
  });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { raw: text }; }
  return { status: res.status, data };
}

function writeReport(lines) {
  fs.writeFileSync('D:\\agent-trip-planner\\e2e-dedupe-report.txt', lines.join('\n'), 'utf8');
  console.log(lines.join('\n'));
}

(async () => {
  // 1. Login
  const login = await req('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({
      email: 'admin@tripplanner.com',
      password: 'Admin@123456',
    }),
  });
  const token = login.data?.data?.accessToken || login.data?.accessToken;
  if (!token) {
    console.error('LOGIN_FAIL', JSON.stringify(login.data).slice(0, 500));
    process.exit(1);
  }
  const auth = { Authorization: `Bearer ${token}` };

  // 2. Create trip
  const rawInput = '明天去杭州玩一天，想去西湖、灵隐寺，中午吃楼外楼，晚上逛河坊街吃小吃';
  const create = await req('/api/trips', {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({
      title: '杭州西湖美食一日游验证',
      rawInput,
      timeStart: '2026-10-01T08:00:00',
      timeEnd: '2026-10-01T20:00:00',
      transportMode: 'mixed',
      preferences: {},
    }),
  });
  const trip = create.data?.data;
  if (!trip?.id) {
    console.error('CREATE_FAIL', create.status, JSON.stringify(create.data).slice(0, 800));
    process.exit(1);
  }
  console.log('tripId=' + trip.id, 'status=' + trip.status);

  // 3. Wait for planning
  let detail;
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 3000));
    const d = await req(`/api/trips/${trip.id}`, { headers: auth });
    detail = d.data?.data;
    console.log('status=' + detail?.status);
    if (detail?.status === 'completed' || detail?.status === 'failed') break;
  }
  if (!detail || detail.status !== 'completed') {
    console.error('PLAN_STATUS=' + detail?.status);
    process.exit(1);
  }

  // 4. Activities from latestVersion
  let acts = detail.latestVersion?.activities;
  if (!acts || !acts.length) {
    const v = await req(`/api/trips/${trip.id}/versions/1`, { headers: auth });
    acts = v.data?.data?.activities;
  }
  if (!acts || !acts.length) {
    fs.writeFileSync('D:\\agent-trip-planner\\e2e-detail.json',
      JSON.stringify(detail, null, 2), 'utf8');
    console.error('NO_ACTIVITIES');
    process.exit(1);
  }

  const report = [];
  report.push('ACT_COUNT=' + acts.length);
  const visitNames = [];
  const mealNames = [];
  for (const a of acts) {
    const day = a.day ?? 1;
    const name = a.name || a.poi_name || '';
    const type = a.type || a.activity_type || '';
    const st = a.startTime || a.scheduled_start || '';
    const et = a.endTime || a.scheduled_end || '';
    report.push(`${day}|${type}|${name}|${st}-${et}`);
    if (['visit', 'scenic', 'temple', 'shopping', 'park', 'museum'].includes(type)) {
      visitNames.push(name);
    } else if (type === 'meal') {
      mealNames.push(name);
    }
  }

  // Dedupe visits
  report.push('---DUP VISITS---');
  const vc = {};
  visitNames.forEach((n) => { vc[n] = (vc[n] || 0) + 1; });
  const dupV = Object.entries(vc).filter(([, c]) => c > 1);
  if (dupV.length) dupV.forEach(([n, c]) => report.push(`DUP: ${n} x${c}`));
  else report.push('none');

  report.push('---DUP MEALS---');
  const mc = {};
  mealNames.forEach((n) => { mc[n] = (mc[n] || 0) + 1; });
  const dupM = Object.entries(mc).filter(([, c]) => c > 1);
  if (dupM.length) dupM.forEach(([n, c]) => report.push(`DUP: ${n} x${c}`));
  else report.push('none');

  report.push('---LOULOU---');
  const loulou = mealNames.filter((n) => n.includes('楼外楼'));
  if (loulou.length) report.push(loulou.join('; '));
  else report.push('MISSING');

  report.push('---PLACEHOLDER---');
  const ph = [...visitNames, ...mealNames].filter((n) =>
    /无则省略|具体餐厅名|口味或菜系|偏好描述|景点名称|仅写真实/.test(n));
  if (ph.length) ph.forEach((n) => report.push(n));
  else report.push('none');

  report.push('---CITY CHECK---');
  const beijing = ['故宫', '天安门', '长城', '颐和园', '天坛', '圆明园', '南锣鼓巷', '王府井'];
  const bjHit = visitNames.filter((n) => beijing.some((b) => n.includes(b)));
  if (bjHit.length) bjHit.forEach((n) => report.push('BJ:' + n));
  else report.push('no-beijing');

  // Hangzhou places expected
  report.push('---HANGZHOU CHECK---');
  const hz = visitNames.filter((n) => /西湖|灵隐|河坊街|雷峰|断桥|苏堤|白堤/.test(n));
  if (hz.length) report.push(hz.join('; '));
  else report.push('MISSING_HZ');

  writeReport(report);
  console.log('REPORT_WRITTEN');
})().catch((e) => {
  console.error('E2E_ERROR', e);
  process.exit(1);
});
