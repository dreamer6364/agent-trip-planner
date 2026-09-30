const { execFileSync } = require('child_process');
const dbEnv = require('./db-env');
const sql = `select tp.id, tv.activities from trip_planner.trips tp join trip_planner.trip_versions tv on tv.trip_id=tp.id where tv.created_at > now() - interval 2 hour order by tv.created_at desc;`;
const out = execFileSync('mysql', [...dbEnv.args(), '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], { encoding: 'utf8' });

function toMin(t) {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(t || '').trim());
  return m ? (+m[1]) * 60 + (+m[2]) : 0;
}
let rows = out.split(/\r?\n/).filter(l => l.includes('\t'));
let fail = 0;
for (const row of rows) {
  const [tripId, actsJson] = row.split('\t');
  let acts;
  try { acts = JSON.parse(actsJson); } catch { continue; }
  if (!Array.isArray(acts) || acts.length === 0) continue;
  const byDay = {};
  for (const a of acts) (byDay[a.day || 1] = byDay[a.day || 1] || []).push(a);
  const problems = [];
  const types = new Set();
  const names = [];
  for (const a of acts) {
    const n = (a.poi_name || a.name || '');
    if (/自由活动|市区漫步|待定/.test(n)) problems.push('placeholder: ' + n);
    const t = a.type || a.activity_type;
    if (t !== 'meal' && t !== 'transit') { types.add(t); names.push(n); }
  }
  for (const [day, list] of Object.entries(byDay)) {
    list.sort((x, y) => toMin((x.scheduled_start || x.startTime)) - toMin((y.scheduled_start || y.startTime)));
    const meals = list.filter(a => (a.type || a.activity_type) === 'meal');
    if (!meals.some(m => String(m.poi_name || m.name || '').includes('午'))) problems.push('day' + day + ':no lunch');
    if (!meals.some(m => String(m.poi_name || m.name || '').includes('晚'))) problems.push('day' + day + ':no dinner');
    for (let i = 1; i < list.length; i++) {
      const p = list[i - 1], c = list[i];
      const tt = Number((p.travel_duration_min ?? p.travelTimeMin ?? 0));
      const need = toMin((p.scheduled_end || p.endTime)) + tt;
      const got = toMin((c.scheduled_start || c.startTime));
      if (got < need) problems.push('day' + day + ' TIME ' + (c.poi_name||c.name) + ' ' + got + ' < ' + need + ' (prev ' + (p.endTime) + ' + tt' + tt + ')');
    }
  }
  const flag = problems.length ? 'FAIL' : 'OK';
  if (problems.length) fail++;
  console.log('== ' + tripId + ' acts=' + acts.length + ' ' + flag);
  console.log('   types=' + [...types].join(','));
  if (problems.length) console.log('   ' + problems.join('\n   '));
}
console.log(fail === 0 ? 'ALL_PASS' : 'FAILURES=' + fail);
