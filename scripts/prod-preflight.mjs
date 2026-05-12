import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

const envFile = argValue('--env-file', '--env') || '.env'
const resolvedEnvFile = path.resolve(envFile)
const errors = []
const warnings = []

if (!fs.existsSync(resolvedEnvFile)) {
  fail(`env file not found: ${resolvedEnvFile}`)
}

const env = parseEnv(fs.readFileSync(resolvedEnvFile, 'utf8'))

required('MYSQL_ROOT_PASSWORD')
required('MYSQL_DATABASE')
required('MYSQL_USER')
required('MYSQL_PASSWORD')
required('XIYIYUN_ADMIN_USERNAME')
required('XIYIYUN_ADMIN_PASSWORD_BCRYPT')
required('XIYIYUN_PAYMENT_CALLBACK_SECRET')
required('XIYIYUN_CARD_ENCRYPTION_SECRET')
required('XIYIYUN_CORS_ALLOWED_ORIGINS')

strongPassword('MYSQL_ROOT_PASSWORD')
strongPassword('MYSQL_PASSWORD')
if (value('MYSQL_ROOT_PASSWORD') && value('MYSQL_PASSWORD') && value('MYSQL_ROOT_PASSWORD') === value('MYSQL_PASSWORD')) {
  errors.push('MYSQL_ROOT_PASSWORD and MYSQL_PASSWORD must be different')
}

const adminHash = value('XIYIYUN_ADMIN_PASSWORD_BCRYPT').replace(/\$\$/g, '$')
if (!/^\$2[aby]?\$\d{2}\$.{53}$/.test(adminHash)) {
  errors.push('XIYIYUN_ADMIN_PASSWORD_BCRYPT must be a bcrypt hash; remember to escape $ as $$ in Docker Compose .env files')
}
if (adminHash === '$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq') {
  errors.push('XIYIYUN_ADMIN_PASSWORD_BCRYPT must not be the development default admin123 hash')
}

strongSecret('XIYIYUN_PAYMENT_CALLBACK_SECRET', 'xiyiyun_mock_payment_secret')
strongSecret('XIYIYUN_CARD_ENCRYPTION_SECRET', 'xiyiyun_dev_card_secret')
if (value('XIYIYUN_PAYMENT_CALLBACK_SECRET') === value('XIYIYUN_CARD_ENCRYPTION_SECRET')) {
  errors.push('XIYIYUN_PAYMENT_CALLBACK_SECRET and XIYIYUN_CARD_ENCRYPTION_SECRET must be different')
}

validateCorsOrigins()
validatePort('WEB_PORT')
validatePort('H5_PORT')
validatePort('ADMIN_PORT')
validateDistinctPorts()
validateHttpBind()
validateOptionalHttpsUrl('NPM_REGISTRY')
validateOptionalHttpsUrl('MAVEN_MIRROR_URL')

if (errors.length) {
  console.error(`prod-preflight: ${resolvedEnvFile} is not ready for production`)
  for (const error of errors) {
    console.error(`- ${error}`)
  }
  process.exit(1)
}

runComposeConfig()

console.log(`prod-preflight: ${resolvedEnvFile} passed production configuration checks`)
for (const warning of warnings) {
  console.warn(`warning: ${warning}`)
}

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

function parseEnv(source) {
  const result = new Map()
  for (const line of source.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) {
      continue
    }
    const separator = trimmed.indexOf('=')
    if (separator === -1) {
      continue
    }
    const key = trimmed.slice(0, separator).trim()
    let rawValue = trimmed.slice(separator + 1).trim()
    if ((rawValue.startsWith('"') && rawValue.endsWith('"')) || (rawValue.startsWith("'") && rawValue.endsWith("'"))) {
      rawValue = rawValue.slice(1, -1)
    }
    result.set(key, rawValue)
  }
  return result
}

function value(key) {
  return env.get(key) || ''
}

function required(key) {
  if (!value(key)) {
    errors.push(`${key} is required`)
  }
}

function placeholder(valueToCheck) {
  const normalized = valueToCheck.toLowerCase()
  return !valueToCheck
    || normalized.includes('change_me')
    || normalized.includes('please_')
    || normalized.includes('your-domain')
    || normalized.includes('your_domain')
    || normalized.includes('example.com')
    || normalized.includes('example.')
    || normalized.includes('.example')
}

function strongPassword(key) {
  const current = value(key)
  if (placeholder(current)) {
    errors.push(`${key} must be replaced with a real production value`)
    return
  }
  if (current.length < 12) {
    errors.push(`${key} should be at least 12 characters`)
  }
}

function strongSecret(key, forbiddenDefault) {
  const current = value(key)
  if (placeholder(current) || current === forbiddenDefault) {
    errors.push(`${key} must be replaced with a generated production secret`)
    return
  }
  if (current.length < 32) {
    errors.push(`${key} must be at least 32 characters`)
  }
}

function validateCorsOrigins() {
  const raw = value('XIYIYUN_CORS_ALLOWED_ORIGINS')
  if (!raw) {
    return
  }
  const origins = raw.split(',').map((item) => item.trim()).filter(Boolean)
  if (!origins.length) {
    errors.push('XIYIYUN_CORS_ALLOWED_ORIGINS must contain at least one origin')
    return
  }
  const seen = new Set()
  for (const origin of origins) {
    if (seen.has(origin)) {
      warnings.push(`duplicate CORS origin: ${origin}`)
    }
    seen.add(origin)
    if (origin.includes('*')) {
      errors.push(`CORS origin must not use wildcard: ${origin}`)
    }
    if (!origin.startsWith('https://')) {
      errors.push(`CORS origin must use HTTPS: ${origin}`)
    }
    if (placeholder(origin) || origin.includes('localhost') || origin.includes('127.0.0.1') || origin.includes('0.0.0.0')) {
      errors.push(`CORS origin must be a real production domain: ${origin}`)
    }
  }
}

function validatePort(key) {
  const current = value(key)
  if (!current) {
    return
  }
  if (!/^\d+$/.test(current)) {
    errors.push(`${key} must be a numeric port`)
    return
  }
  const port = Number(current)
  if (port < 1 || port > 65535) {
    errors.push(`${key} must be between 1 and 65535`)
  }
}

function validateDistinctPorts() {
  const ports = new Map()
  for (const key of ['WEB_PORT', 'H5_PORT', 'ADMIN_PORT']) {
    const current = value(key)
    if (!current || !/^\d+$/.test(current)) {
      continue
    }
    const existing = ports.get(current)
    if (existing) {
      errors.push(`${key} must not reuse ${existing} (${current})`)
    } else {
      ports.set(current, key)
    }
  }
}

function validateHttpBind() {
  const current = value('XIYIYUN_HTTP_BIND') || '127.0.0.1'
  if (!['127.0.0.1', 'localhost', '::1'].includes(current)) {
    errors.push('XIYIYUN_HTTP_BIND must stay on localhost/127.0.0.1/::1 for production; expose traffic through an HTTPS reverse proxy')
  }
}

function validateOptionalHttpsUrl(key) {
  const current = value(key)
  if (!current) {
    return
  }
  if (placeholder(current)) {
    errors.push(`${key} must be replaced with a real HTTPS URL or left empty`)
    return
  }
  try {
    const parsed = new URL(current)
    if (parsed.protocol !== 'https:') {
      errors.push(`${key} must use HTTPS`)
    }
  } catch {
    errors.push(`${key} must be a valid URL`)
  }
}

function runComposeConfig() {
  const result = spawnSync(
    'docker',
    ['compose', '-p', 'xiyiyun', '-f', 'docker-compose.prod.yml', '--env-file', resolvedEnvFile, 'config', '--quiet'],
    {
      cwd: path.resolve('.'),
      encoding: 'utf8',
    },
  )
  if (result.status !== 0) {
    console.error('prod-preflight: docker compose production config check failed')
    if (result.stderr) {
      console.error(result.stderr.trim())
    }
    if (result.stdout) {
      console.error(result.stdout.trim())
    }
    process.exit(result.status || 1)
  }
}

function fail(message) {
  console.error(`prod-preflight: ${message}`)
  process.exit(1)
}
