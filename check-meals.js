// 餐次时间窗 DB 扫描（1.31.0 CHECK_MEALS 回归门禁）
// 用法: node check-meals.js [分钟窗口=120]
// FAIL 口径（近 N 分钟内的新版本，任一条即 exit 1）:
//   - 午餐起点不在 [11:00, 15:00]（15:00 为首日晚出发退化右缘，含 13:30 硬右缘与退化带）
//   - 晚餐起点不在 [17:00, 20:00]（20:00 为退化右缘）
//   - 正餐 scheduled_start 缺失
//   - 任一活动结束 > 21:00，或结束早于起点（跨零点回绕残迹）
// INFO: 近 30 天违规总数（含历史存量，仅供趋势参考，不参与判定）
const { execFileSync } = require('child_process');
const db = require('D:/agent-trip-planner/db-env');

const windowMin = Number(process.argv[2] || 120);
const run = (sql) => execFileSync('mysql',
  [...db.args(), '--default-character-set=utf8mb4', '-N', '-B', '-e', sql],
  { encoding: 'utf8' }).trim();

const JT = `cross join json_table(tv.activities, '$[*]' columns(
  activity_type varchar(20) path '$.activity_type',
  poi_name varchar(300) path '$.poi_name',
  scheduled_start varchar(30) path '$.scheduled_start',
  scheduled_end varchar(30) path '$.scheduled_end')) act`;

const WHERE_BAD = `(
  (act.activity_type = 'meal' and act.poi_name like '%午餐%'
    and (act.scheduled_start is null or act.scheduled_start = ''
         or time_to_sec(act.scheduled_start) < 11 * 3600
         or time_to_sec(act.scheduled_start) > 15 * 3600))
  or (act.activity_type = 'meal' and act.poi_name like '%晚餐%'
    and (act.scheduled_start is null or act.scheduled_start = ''
         or time_to_sec(act.scheduled_start) < 17 * 3600
         or time_to_sec(act.scheduled_start) > 20 * 3600))
  or (act.scheduled_end is not null and act.scheduled_end <> ''
    and time_to_sec(act.scheduled_end) > 21 * 3600)
  or (act.scheduled_start is not null and act.scheduled_start <> ''
      and act.scheduled_end is not null and act.scheduled_end <> ''
      and time_to_sec(act.scheduled_end) < time_to_sec(act.scheduled_start))
)`;

let fail = 0;

console.log(`== 餐次时间窗违规（近 ${windowMin} 分钟新版本，FAIL 口径） ==`);
const recent = run(`select tp.title, tv.created_at, act.poi_name, act.scheduled_start, act.scheduled_end
from trip_planner.trip_versions tv
join trip_planner.trips tp on tp.id = tv.trip_id
${JT}
where tv.created_at > now() - interval ${windowMin} minute
  and ${WHERE_BAD}
order by tv.created_at desc limit 50;`);
if (recent) {
  console.log(recent);
  const rows = recent.split('\n').length;
  fail += rows;
  console.log(`  -> ${rows} 条违规`);
} else {
  console.log('  -> 0 条违规 [PASS]');
}

console.log('== 硬右缘细节（近 7 天，午餐 >13:30 / 晚餐 >19:30，INFO 不参与判定） ==');
const strict = run(`select tp.title, act.poi_name, act.scheduled_start, act.scheduled_end
from trip_planner.trip_versions tv
join trip_planner.trips tp on tp.id = tv.trip_id
${JT}
where tv.created_at > now() - interval 7 day
  and act.activity_type = 'meal'
  and ((act.poi_name like '%午餐%' and time_to_sec(act.scheduled_start) > 13 * 3600 + 1800)
    or (act.poi_name like '%晚餐%' and time_to_sec(act.scheduled_start) > 19 * 3600 + 1800))
order by tv.created_at desc limit 20;`);
console.log(strict || '  (无)');

console.log('== 历史存量违规总数（近 30 天，INFO 不参与判定） ==');
const hist = run(`select count(*)
from trip_planner.trip_versions tv
${JT}
where tv.created_at > now() - interval 30 day
  and ${WHERE_BAD};`);
console.log(`  ${hist} 条`);

console.log(`\nRESULT: violations=${fail}`);
process.exit(fail > 0 ? 1 : 0);
