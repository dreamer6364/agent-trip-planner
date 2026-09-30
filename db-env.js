/* 本地数据库连接参数：凭据从 .env / 环境变量读取，禁止硬编码 */
const fs = require('fs');
const path = require('path');

function loadEnvFile() {
  const file = path.join(__dirname, '.env');
  const out = {};
  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (!line || line.trim().startsWith('#')) continue;
      const i = line.indexOf('=');
      if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
  }
  return out;
}

const fileEnv = loadEnvFile();
const host = process.env.MYSQL_HOST || fileEnv.MYSQL_HOST || '127.0.0.1';
const port = process.env.MYSQL_PORT || fileEnv.MYSQL_PORT || '3306';
// 这些脚本历史上固定使用 root（.env 的 MYSQL_USER 面向 Docker Compose），仅允许环境变量覆盖
const user = process.env.MYSQL_USER || 'root';
const password = process.env.MYSQL_PASSWORD || fileEnv.MYSQL_PASSWORD || fileEnv.MYSQL_ROOT_PASSWORD || '';

if (!password) {
  console.error('MYSQL_PASSWORD 未配置：请在 .env 或环境变量中设置');
  process.exit(1);
}

module.exports = {
  host,
  port,
  user,
  password,
  args: () => [`-h${host}`, `-P${port}`, `-u${user}`, `-p${password}`],
};
