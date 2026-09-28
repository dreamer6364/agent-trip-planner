const { execFileSync } = require('child_process');
const tripId = process.argv[2];
const sql = `select activities, routes from trip_planner.trip_versions where trip_id='${tripId}' order by created_at desc limit 1;`;
const out = execFileSync('mysql', ['-h127.0.0.1', '-P3306', '-uroot', '-p12345678mxy', '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], { encoding: 'utf8' });
const line = out.split(/\r?\n/).find(l => l.trim().length > 0);
if (!line) { console.log('no row'); process.exit(0); }
const parts = line.split('\t');
const acts = JSON.parse(parts[0]);
console.log('ACT_COUNT=' + acts.length);
for (const a of acts) {
  console.log([
    'day=' + (a.day ?? 1),
    'seq=' + (a.seq ?? ''),
    'type=' + (a.type || a.activity_type),
    'name=' + (a.name || a.poi_name),
    (a.startTime || a.scheduled_start) + '~' + (a.endTime || a.scheduled_end),
    'dur=' + (a.durationMin ?? a.duration_min),
    'tt=' + (a.travelTimeMin ?? a.travel_duration_min ?? 0),
    'mode=' + (a.transportToNext || a.transport_mode || ''),
    'prio=' + (a.priority || ''),
  ].join(' | '));
}
