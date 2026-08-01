import * as THREE from 'three'
import './style.css'

loadPublicSettings()

const canvas = document.querySelector('#xiyi-scene')
const scene = new THREE.Scene()
scene.fog = new THREE.FogExp2(0x05070d, 0.038)

const renderer = new THREE.WebGLRenderer({
  canvas,
  alpha: true,
  antialias: true,
  powerPreference: 'high-performance',
  preserveDrawingBuffer: true
})
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.8))
renderer.setSize(window.innerWidth, window.innerHeight)
renderer.outputColorSpace = THREE.SRGBColorSpace

const camera = new THREE.PerspectiveCamera(42, window.innerWidth / window.innerHeight, 0.1, 100)
camera.position.set(0, 0.45, 9.2)

const root = new THREE.Group()
scene.add(root)

scene.add(new THREE.AmbientLight(0xb8caff, 1.1))

const lights = [
  [0x6af7ff, 42, -4.2, 2.6, 4.2],
  [0xb18cff, 36, 4.2, -0.6, 4.8],
  [0xffd77a, 18, 1.4, 3.4, -2.8]
]
lights.forEach(([color, intensity, x, y, z]) => {
  const light = new THREE.PointLight(color, intensity, 18)
  light.position.set(x, y, z)
  scene.add(light)
})

const core = createExchangeCore()
root.add(core)

const streamGroup = new THREE.Group()
root.add(streamGroup)

const streams = [
  { name: '上游供货', color: 0x5df4ff, axis: 'x', phase: 0 },
  { name: '商品库存', color: 0x79a8ff, axis: 'z', phase: 1.3 },
  { name: '批发采购', color: 0xb997ff, axis: 'x', phase: 2.6 },
  { name: '订单交付', color: 0xffd36a, axis: 'z', phase: 3.9 }
].map((stream, index) => {
  const rail = createStream(stream, index)
  streamGroup.add(rail)
  return rail
})

const supplyNodes = [
  { name: '影视', color: 0x62bdff, angle: -0.34, radius: 2.45, y: 0.78 },
  { name: '网盘', color: 0x58e078, angle: 0.52, radius: 2.72, y: -0.18 },
  { name: 'AI', color: 0xc9a0ff, angle: 1.4, radius: 2.58, y: 0.36 },
  { name: '履约', color: 0xffd36a, angle: 2.3, radius: 2.38, y: -0.62 }
].map((nodeData) => {
  const node = createSupplyNode(nodeData)
  node.userData = nodeData
  root.add(node)
  return node
})

const particles = createParticleField()
scene.add(particles)

const mouse = new THREE.Vector2()
const targetMouse = new THREE.Vector2()
let scrollProgress = 0
let baseSceneY = 0

window.addEventListener('pointermove', (event) => {
  targetMouse.x = (event.clientX / window.innerWidth - 0.5) * 2
  targetMouse.y = (event.clientY / window.innerHeight - 0.5) * 2
})

window.addEventListener('scroll', () => {
  const max = Math.max(document.documentElement.scrollHeight - window.innerHeight, 1)
  scrollProgress = window.scrollY / max
}, { passive: true })

window.addEventListener('resize', resize)
positionScene()

function resize() {
  camera.aspect = window.innerWidth / window.innerHeight
  camera.updateProjectionMatrix()
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.8))
  renderer.setSize(window.innerWidth, window.innerHeight)
  positionScene()
}

function positionScene() {
  const isMobile = window.innerWidth <= 620
  root.position.x = window.innerWidth > 1280 ? 2.25 : window.innerWidth > 960 ? 1.55 : isMobile ? 0.2 : 0.72
  baseSceneY = isMobile ? -1.08 : -0.04
  root.position.y = baseSceneY
  root.scale.setScalar(isMobile ? 0.58 : window.innerWidth > 1280 ? 1.02 : 0.88)
  supplyNodes.forEach((node) => {
    node.visible = window.innerWidth > 740
    node.scale.setScalar(window.innerWidth > 1280 ? 0.92 : 0.78)
  })
}

const clock = new THREE.Clock()
renderer.setAnimationLoop(() => {
  const time = clock.getElapsedTime()
  mouse.lerp(targetMouse, 0.055)
  const sceneOpacity = scrollProgress > 0.12 ? Math.max(0.2, 1 - (scrollProgress - 0.12) * 3.8) : 1
  canvas.style.opacity = sceneOpacity.toFixed(2)

  root.rotation.y = mouse.x * 0.12 + scrollProgress * 0.5
  root.rotation.x = -mouse.y * 0.08 - scrollProgress * 0.18
  root.position.y = baseSceneY - scrollProgress * 1.5

  core.rotation.y = time * 0.1
  core.rotation.z = Math.sin(time * 0.22) * 0.035

  streams.forEach((rail, index) => {
    rail.rotation.y += 0.0018 * (index % 2 ? -1 : 1)
    rail.children.forEach((child) => {
      if (child.userData.orbit !== undefined) {
        const p = (time * 0.16 + child.userData.orbit) % 1
        const point = child.userData.curve.getPointAt(p)
        child.position.copy(point)
        child.material.opacity = 0.24 + Math.sin(p * Math.PI) * 0.52
      }
    })
  })

  supplyNodes.forEach((node, index) => {
    const { angle, radius, y } = node.userData
    const orbit = angle + time * 0.055
    node.position.set(Math.cos(orbit) * radius, y + Math.sin(time * 0.72 + index) * 0.08, Math.sin(orbit) * radius)
    node.lookAt(camera.position)
  })

  particles.rotation.y = time * 0.012
  particles.rotation.x = Math.sin(time * 0.06) * 0.035

  camera.position.x = mouse.x * 0.26
  camera.position.y = 0.45 - mouse.y * 0.12
  camera.lookAt(root.position.x * 0.18, -0.08 - scrollProgress * 0.8, 0)
  renderer.render(scene, camera)
})

function createExchangeCore() {
  const group = new THREE.Group()

  const shell = new THREE.Mesh(
    new THREE.SphereGeometry(1.18, 72, 72),
    new THREE.MeshPhysicalMaterial({
      color: 0x7fe8ff,
      roughness: 0.08,
      metalness: 0.18,
      transmission: 0.24,
      thickness: 1.1,
      transparent: true,
      opacity: 0.72,
      clearcoat: 1,
      clearcoatRoughness: 0.12,
      emissive: 0x0d4564,
      emissiveIntensity: 0.32
    })
  )
  group.add(shell)

  const wire = new THREE.Mesh(
    new THREE.IcosahedronGeometry(1.42, 2),
    new THREE.MeshBasicMaterial({
      color: 0x98f8ff,
      wireframe: true,
      transparent: true,
      opacity: 0.16,
      blending: THREE.AdditiveBlending
    })
  )
  group.add(wire)

  const inner = new THREE.Mesh(
    new THREE.OctahedronGeometry(0.58, 1),
    new THREE.MeshPhysicalMaterial({
      color: 0xd6f7ff,
      roughness: 0.2,
      metalness: 0.55,
      emissive: 0x2cdfff,
      emissiveIntensity: 0.46
    })
  )
  group.add(inner)

  const halos = [
    [1.84, 0x68f6ff, 0.32, 1.34, 0.1, 0.2],
    [2.26, 0x7d9dff, 0.22, 1.42, 0.58, 0.72],
    [2.72, 0xbf9dff, 0.18, 1.12, -0.38, -0.5]
  ]

  halos.forEach(([radius, color, opacity, x, y, z]) => {
    const halo = new THREE.Mesh(
      new THREE.TorusGeometry(radius, 0.008, 12, 220),
      new THREE.MeshBasicMaterial({
        color,
        transparent: true,
        opacity,
        blending: THREE.AdditiveBlending
      })
    )
    halo.rotation.set(x, y, z)
    group.add(halo)
  })

  return group
}

function createStream(stream, index) {
  const group = new THREE.Group()
  const sign = index < 2 ? 1 : -1
  const y = index % 2 ? 0.86 : -0.86
  const curve = new THREE.CatmullRomCurve3([
    new THREE.Vector3(-5.6 * sign, y, -0.9),
    new THREE.Vector3(-2.4 * sign, y * 0.22, 0.9),
    new THREE.Vector3(0, 0, 0),
    new THREE.Vector3(2.4 * sign, -y * 0.22, -0.85),
    new THREE.Vector3(5.6 * sign, -y, 0.9)
  ])

  const rail = new THREE.Mesh(
    new THREE.TubeGeometry(curve, 120, 0.008, 8, false),
    new THREE.MeshBasicMaterial({
      color: stream.color,
      transparent: true,
      opacity: 0.46,
      blending: THREE.AdditiveBlending
    })
  )
  group.add(rail)

  for (let i = 0; i < 8; i += 1) {
    const bead = new THREE.Mesh(
      new THREE.SphereGeometry(0.035 + (i % 3) * 0.012, 16, 16),
      new THREE.MeshBasicMaterial({
        color: stream.color,
        transparent: true,
        opacity: 0.55,
        blending: THREE.AdditiveBlending
      })
    )
    bead.userData = { curve, orbit: i / 8 }
    group.add(bead)
  }

  group.rotation.z = index * 0.38
  return group
}

function createSupplyNode(nodeData) {
  const group = new THREE.Group()
  const glow = new THREE.Mesh(
    new THREE.SphereGeometry(0.12, 24, 24),
    new THREE.MeshBasicMaterial({
      color: nodeData.color,
      transparent: true,
      opacity: 0.84,
      blending: THREE.AdditiveBlending
    })
  )
  group.add(glow)

  const ring = new THREE.Mesh(
    new THREE.TorusGeometry(0.31, 0.006, 10, 96),
    new THREE.MeshBasicMaterial({
      color: nodeData.color,
      transparent: true,
      opacity: 0.46,
      blending: THREE.AdditiveBlending
    })
  )
  ring.rotation.x = Math.PI / 2.6
  group.add(ring)

  const labelTexture = new THREE.CanvasTexture(drawPillTexture(nodeData.name, `#${nodeData.color.toString(16).padStart(6, '0')}`, 220, 86))
  labelTexture.colorSpace = THREE.SRGBColorSpace
  const label = new THREE.Sprite(new THREE.SpriteMaterial({
    map: labelTexture,
    transparent: true,
    opacity: 0.72,
    depthWrite: false
  }))
  label.position.y = -0.34
  label.scale.set(0.58, 0.22, 1)
  group.add(label)
  return group
}

function createSmallLabel(text, color) {
  const texture = new THREE.CanvasTexture(drawPillTexture(text, `#${color.toString(16).padStart(6, '0')}`, 300, 104))
  texture.colorSpace = THREE.SRGBColorSpace
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: texture,
    transparent: true,
    opacity: 0.86,
    depthWrite: false
  }))
  sprite.scale.set(0.82, 0.28, 1)
  return sprite
}

function drawPillTexture(text, color, width, height) {
  const c = document.createElement('canvas')
  c.width = width
  c.height = height
  const ctx = c.getContext('2d')
  ctx.clearRect(0, 0, width, height)
  roundRect(ctx, 10, 10, width - 20, height - 20, 32)
  const gradient = ctx.createLinearGradient(0, 0, width, height)
  gradient.addColorStop(0, 'rgba(255,255,255,0.18)')
  gradient.addColorStop(0.5, 'rgba(28,38,70,0.72)')
  gradient.addColorStop(1, 'rgba(5,8,20,0.62)')
  ctx.fillStyle = gradient
  ctx.fill()
  ctx.strokeStyle = 'rgba(255,255,255,0.2)'
  ctx.lineWidth = 2
  ctx.stroke()

  ctx.shadowColor = color
  ctx.shadowBlur = 20
  ctx.fillStyle = color
  roundRect(ctx, 30, height / 2 - 19, 38, 38, 12)
  ctx.fill()
  ctx.shadowBlur = 0

  ctx.fillStyle = 'rgba(248,251,255,0.96)'
  ctx.font = `900 ${height > 120 ? 32 : 26}px Arial`
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, 84, height / 2)
  return c
}

function createParticleField() {
  const count = 1500
  const positions = new Float32Array(count * 3)
  const colors = new Float32Array(count * 3)
  const palette = [new THREE.Color(0x57f4ff), new THREE.Color(0x8f80ff), new THREE.Color(0xffd36a)]

  for (let i = 0; i < count; i += 1) {
    const radius = 4 + Math.random() * 12
    const theta = Math.random() * Math.PI * 2
    const y = (Math.random() - 0.5) * 7
    positions[i * 3] = Math.cos(theta) * radius
    positions[i * 3 + 1] = y
    positions[i * 3 + 2] = Math.sin(theta) * radius
    const color = palette[Math.floor(Math.random() * palette.length)]
    colors[i * 3] = color.r
    colors[i * 3 + 1] = color.g
    colors[i * 3 + 2] = color.b
  }

  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geometry.setAttribute('color', new THREE.BufferAttribute(colors, 3))
  return new THREE.Points(
    geometry,
    new THREE.PointsMaterial({
      size: 0.024,
      vertexColors: true,
      transparent: true,
      opacity: 0.72,
      blending: THREE.AdditiveBlending,
      depthWrite: false
    })
  )
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

async function loadPublicSettings() {
  const apiBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  const endpoint = `${apiBase}/api/h5/settings`
  try {
    const response = await fetch(endpoint, { headers: { Accept: 'application/json' } })
    if (!response.ok) return
    const payload = unwrapApiPayload(await response.json())
    const settings = payload && typeof payload === 'object' ? payload : {}
    applyFooterSettings({
      companyName: cleanSettingText(settings.companyName),
      icpRecordNo: cleanSettingText(settings.icpRecordNo),
      policeRecordNo: cleanSettingText(settings.policeRecordNo),
      disclaimer: cleanSettingText(settings.disclaimer)
    })
  } catch {
    // Public filing information should not block the portal if the API is unreachable.
  }
}

function unwrapApiPayload(payload) {
  if (!payload || typeof payload !== 'object') return payload
  if (Number(payload.code) !== 0 && payload.code !== undefined) return null
  if ('data' in payload) return unwrapApiPayload(payload.data)
  if ('result' in payload) return unwrapApiPayload(payload.result)
  return payload
}

function cleanSettingText(value) {
  return value === null || value === undefined ? '' : String(value).trim()
}

function applyFooterSettings(settings) {
  const company = document.querySelector('[data-site-company]')
  const icp = document.querySelector('[data-site-icp]')
  const police = document.querySelector('[data-site-police]')
  const disclaimer = document.querySelector('[data-site-disclaimer]')

  if (settings.companyName && company) company.textContent = `© ${settings.companyName}`
  showFooterItem(icp, settings.icpRecordNo)
  showFooterItem(police, settings.policeRecordNo)
  showFooterItem(disclaimer, settings.disclaimer)
}

function showFooterItem(element, value) {
  if (!element || !value) return
  element.textContent = value
  element.hidden = false
}
