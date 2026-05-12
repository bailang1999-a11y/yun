import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const envFile = argValue('--env-file', '--env') || '.env'
const resolvedEnvFile = path.resolve(envFile)

const checks = [
  ['schema', ['npm', 'run', 'schema:check']],
  ['frontend builds', ['npm', 'run', 'typecheck']],
  ['backend tests', ['npm', 'run', 'test:backend']],
  ['production dependency audit', ['npm', 'audit', '--omit=dev', '--audit-level=high']],
  ['production env preflight', ['npm', 'run', 'prod:preflight', '--', '--env-file', resolvedEnvFile]]
]

if (!fs.existsSync(resolvedEnvFile)) {
  console.error(`launch-verify: env file not found: ${resolvedEnvFile}`)
  console.error('launch-verify: create .env from .env.example or .env.feiniu.example before running final launch verification')
  process.exit(1)
}

for (const [label, command] of checks) {
  console.log(`\nlaunch-verify: running ${label}`)
  const result = spawnSync(command[0], command.slice(1), {
    cwd: path.resolve('.'),
    env: process.env,
    stdio: 'inherit'
  })
  if (result.status !== 0) {
    console.error(`\nlaunch-verify: ${label} failed`)
    process.exit(result.status || 1)
  }
}

console.log('\nlaunch-verify: running production compose config')
const composeResult = spawnSync(
  'docker',
  ['compose', '-p', 'xiyiyun', '-f', 'docker-compose.prod.yml', '--env-file', resolvedEnvFile, 'config', '--quiet'],
  {
    cwd: path.resolve('.'),
    env: process.env,
    stdio: 'inherit'
  }
)
if (composeResult.status !== 0) {
  console.error('\nlaunch-verify: production compose config failed')
  process.exit(composeResult.status || 1)
}
console.log('launch-verify: production compose config passed')

console.log('\nlaunch-verify: all launch checks passed')

function argValue(...names) {
  for (const name of names) {
    const inline = process.argv.find((arg) => arg.startsWith(`${name}=`))
    if (inline) {
      return inline.slice(name.length + 1)
    }
    const index = process.argv.indexOf(name)
    if (index !== -1) {
      return process.argv[index + 1] || ''
    }
  }
  return ''
}
