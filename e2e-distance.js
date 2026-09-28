/* E2E: verify real distances + replace recalculates adjacent legs */
const BASE = 'http://localhost:8086';
const fs = require('fs');

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

(async () => {
  const report = [];
  const login = await req('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: 'admin@tripplanner.com', password: 'Admin@123456' }),
  });
  const token = login.data?.data?.accessToken || login.data?.accessToken;
  if (!token) { console.error('LOGIN_FAIL'); process.exit(1); }
  const auth = { Authorization: `Bearer ${token}` };

  // Create trip
  const create = await req('/api/trips', {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({
      title: '距离精准验证',
      rawInput: '明天去杭州玩一天，想去西湖、灵隐寺，中午吃楼外楼，晚上逛河坊街吃小吃',
      timeStart: '2026-10-02T08:00:00',
      timeEnd: '2026-10-02T20:00:00',
      transportMode: 'mixed',
      preferences: {},
    }),
  });
  const trip = create.data?.data;
  if (!trip?.id) { console.error('CREATE_FAIL', JSON.stringify(create.data).slice(0, 800)); process.exit(1); }
  console.log('tripId=' + trip.id);

  let detail;
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 3000));
    const d = await req(`/api/trips/${trip.id}`, { headers: auth });
    detail = d.data?.data;
    if (detail?.status === 'completed' || detail?.status === 'failed') break;
  }
  if (!detail || detail.status !== 'completed') { console.error('PLAN_FAIL', detail?.status); process.exit(1); }

  const acts = detail.latestVersion?.activities || [];
  const routes = detail.latestVersion?.routes || [];
  report.push('ACT_COUNT=' + acts.length);
  report.push('---DISTANCES---');
  let nonZeroDist = 0;
  let zeroDist = 0;
  let hasLat = 0;
  for (const a of acts) {
    const name = a.poi_name || a.name || a.poiName || '';
    const type = a.activity_type || a.type || '';
    const d = a.travel_distance_km ?? a.travelDistanceKm ?? null;
    const t = a.travel_duration_min ?? a.travelDurationMin ?? null;
    const mode = a.transport_mode || a.transportToNext || '';
    const lat = a.lat;
    const lng = a.lng;
    if (lat != null && lng != null) hasLat++;
    if (d != null && Number(d) > 0) nonZeroDist++;
    else zeroDist++;
    report.push(`${type}|${name}|dist=${d}|time=${t}|mode=${mode}|lat=${lat}|lng=${lng}`);
  }
  report.push('nonZeroDist=' + nonZeroDist + ' zeroDist=' + zeroDist + ' hasCoord=' + hasLat);

  report.push('---ROUTES---');
  for (const r of routes) {
    report.push(`${r.from}->${r.to}|km=${r.distance_km}|min=${r.duration_min}|mode=${r.mode}`);
  }

  // Find a visit activity in middle to replace (not first, not last)
  let replaceTarget = null;
  for (let i = 1; i < acts.length - 1; i++) {
    const t = acts[i].activity_type || acts[i].type;
    const n = acts[i].poi_name || acts[i].name;
    if (t && t !== 'meal' && t !== 'transit') { replaceTarget = { idx: i, name: n, seq: acts[i].seq }; break; }
  }
  report.push('---REPLACE---');
  if (!replaceTarget) {
    report.push('NO_REPLACE_TARGET');
  } else {
    report.push('target=' + replaceTarget.name + ' seq=' + replaceTarget.seq);
    const beforePrev = acts[replaceTarget.idx - 1];
    const beforeCur = acts[replaceTarget.idx];
    const beforeNext = acts[replaceTarget.idx + 1];
    report.push('BEFORE prevDist=' + (beforePrev.travel_distance_km) + ' curDist=' + (beforeCur.travel_distance_km));

    // Get alternatives
    const altRes = await req(`/api/trips/${trip.id}/alternatives`, {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({
        activityName: replaceTarget.name,
        seq: replaceTarget.seq,
        activityType: beforeCur.activity_type || beforeCur.type,
        city: '杭州',
        limit: 5,
      }),
    });
    const alts = altRes.data?.data?.alternatives || [];
    report.push('altCount=' + alts.length);
    if (alts.length === 0) {
      report.push('NO_ALTERNATIVES');
    } else {
      const pick = alts[0];
      report.push('pick=' + pick.name + ' distFromOrig=' + pick.distanceFromOriginalMeters);
      const rep = await req(`/api/trips/${trip.id}/replace`, {
        method: 'POST',
        headers: auth,
        body: JSON.stringify({
          activityName: replaceTarget.name,
          seq: replaceTarget.seq,
          alternativeName: pick.name,
          autoAdjust: true,
        }),
      });
      const rd = rep.data?.data;
      report.push('replaceStatus=' + rep.status + ' success=' + rd?.success);
      report.push('prevLeg=' + JSON.stringify(rd?.previousLeg));
      report.push('nextLeg=' + JSON.stringify(rd?.nextLeg));

      // Reload trip
      await new Promise((r) => setTimeout(r, 1000));
      const d2 = await req(`/api/trips/${trip.id}`, { headers: auth });
      const acts2 = d2.data?.data?.latestVersion?.activities || [];
      const routes2 = d2.data?.data?.latestVersion?.routes || [];
      report.push('AFTER_REPLACE acts=' + acts2.length);
      for (const a of acts2) {
        const name = a.poi_name || a.name || '';
        const d = a.travel_distance_km ?? a.travelDistanceKm ?? null;
        const t = a.travel_duration_min ?? null;
        report.push(`AFTER|${name}|dist=${d}|time=${t}`);
      }
      for (const r of routes2) {
        report.push(`AFTER_ROUTE|${r.from}->${r.to}|km=${r.distance_km}|min=${r.duration_min}`);
      }

      // Validate: replaced activity must be the pick, prev/next legs recalculated with coords
      const afterIdx = acts2.findIndex((a) => (a.poi_name || a.name) === pick.name);
      const afterPrev = acts2[Math.max(0, afterIdx - 1)] || {};
      const afterCur = acts2[afterIdx >= 0 ? afterIdx : replaceTarget.idx] || {};
      const prevDist = Number(afterPrev.travel_distance_km ?? 0);
      const curDist = Number(afterCur.travel_distance_km ?? 0);
      const afterRoutes = routes2.filter((r) => (r.from === pick.name || r.to === pick.name));
      report.push('VALIDATE afterIdx=' + afterIdx + ' afterName=' + (afterCur.poi_name || afterCur.name));
      report.push('VALIDATE afterPrevDist=' + prevDist + ' afterCurDist=' + curDist);
      report.push('VALIDATE routesNearPick=' + JSON.stringify(afterRoutes));
      const nameOk = afterIdx === replaceTarget.idx && (afterCur.poi_name || afterCur.name) === pick.name;
      const prevOk = afterIdx > 0 && prevDist > 0;
      const curOk = afterIdx >= 0 && afterIdx < acts2.length - 1 && curDist > 0;
      const routesOk = afterRoutes.length >= 1 && afterRoutes.every((r) => r.from && r.to && Number(r.distance_km) > 0);
      if (nameOk && prevOk && curOk && routesOk) report.push('RECALC_OK');
      else report.push('RECALC_MISSING nameOk=' + nameOk + ' prevOk=' + prevOk + ' curOk=' + curOk + ' routesOk=' + routesOk);
    }
  }

  // No random 2-7 km pattern in routes
  report.push('---RANDOM_CHECK---');
  const randomish = routes.filter((r) => {
    const d = Number(r.distance_km);
    return d >= 2 && d <= 7 && Math.abs(d - Math.round(d * 10) / 10) < 1e-9 && d !== 0;
  });
  report.push('routesIn2to7=' + randomish.length + '/' + routes.length);

  fs.writeFileSync('D:\\agent-trip-planner\\e2e-distance-report.txt', report.join('\n'), 'utf8');
  console.log(report.join('\n'));
})().catch((e) => { console.error('E2E_ERROR', e); process.exit(1); });
