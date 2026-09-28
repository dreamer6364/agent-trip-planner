import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import { existsSync, readFileSync } from 'node:fs'

function loadRootEnv(key: string): string {
  const envPath = resolve(__dirname, '..', '.env')
  if (!existsSync(envPath)) return ''
  for (const line of readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (m && m[1] === key) return m[2].trim()
  }
  return ''
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8086',
        ws: true,
      },
    },
  },
  define: {
    __VITE_AMAP_KEY__: JSON.stringify(process.env.VITE_AMAP_KEY || loadRootEnv('VITE_AMAP_KEY')),
  },
})
