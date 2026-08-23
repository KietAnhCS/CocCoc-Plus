import { setTimeout as sleep } from 'node:timers/promises'

const BASE = process.env.VNSEARCH_BASE ?? 'http://localhost:8080'
const ADMIN_KEY = process.env.ADMIN_API_KEY ?? ''
const ORIGIN = 'http://localhost:5173'
const TIMEOUT = 15000

const results = []
let suite = ''

function group(name) {
  suite = name
}

async function test(name, fn) {
  const started = Date.now()
  try {
    await fn()
    results.push({ suite, name, status: 'PASS', ms: Date.now() - started })
  } catch (error) {
    const status = error instanceof Skip ? 'SKIP' : 'FAIL'
    results.push({
      suite,
      name,
      status,
      ms: Date.now() - started,
      message: error.message
    })
  }
}

class Skip extends Error {}

function skip(reason) {
  throw new Skip(reason)
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: mong đợi ${JSON.stringify(expected)}, nhận ${JSON.stringify(actual)}`)
  }
}

function assertIn(actual, expected, label) {
  if (!expected.includes(actual)) {
    throw new Error(`${label}: mong đợi một trong ${JSON.stringify(expected)}, nhận ${JSON.stringify(actual)}`)
  }
}

async function call(path, options = {}) {
  const url = new URL(path, BASE)
  for (const [key, value] of Object.entries(options.params ?? {})) {
    if (value !== undefined) {
      url.searchParams.set(key, String(value))
    }
  }
  const response = await fetch(url, {
    method: options.method ?? 'GET',
    headers: {
      Origin: ORIGIN,
      Accept: 'application/json',
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      ...(options.apiKey ? { 'X-API-Key': options.apiKey } : {}),
      ...options.headers
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(options.timeout ?? TIMEOUT),
    redirect: 'manual'
  })
  const text = await response.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  return { status: response.status, headers: response.headers, text, json }
}

const state = {
  username: `tester_${Date.now().toString(36)}`,
  password: 'Kiem-Thu-2026!aA',
  token: null,
  refreshToken: null,
  visitId: null,
  downloadId: crypto.randomUUID()
}

group('1. Sức khoẻ hệ thống')

await test('Gateway /actuator/health trả UP', async () => {
  const res = await call('/actuator/health')
  assertEqual(res.status, 200, 'HTTP status')
  assertEqual(res.json?.status, 'UP', 'trạng thái')
})

await test('Gateway trả 404 cho tuyến không tồn tại', async () => {
  const res = await call('/api/khong-ton-tai-abc')
  assertIn(res.status, [401, 404], 'HTTP status')
})

group('2. Tìm kiếm (công khai)')

await test('GET /api/search trả kết quả', async () => {
  const res = await call('/api/search', { params: { q: 'hà nội' } })
  assertEqual(res.status, 200, 'HTTP status')
  assert(Array.isArray(res.json?.results), 'results phải là mảng')
  assert(res.json.results.length > 0, 'phải có ít nhất một kết quả')
  assert(typeof res.json.totalResults === 'number', 'totalResults phải là số')
})

await test('GET /api/search có trường bắt buộc trong mỗi kết quả', async () => {
  const res = await call('/api/search', { params: { q: 'việt nam' } })
  const first = res.json?.results?.[0]
  assert(first, 'phải có kết quả đầu tiên')
  for (const field of ['title', 'url', 'snippet', 'score']) {
    assert(field in first, `thiếu trường ${field}`)
  }
})

await test('GET /api/search với q rỗng không sập', async () => {
  const res = await call('/api/search', { params: { q: '' } })
  assertIn(res.status, [200, 400], 'HTTP status')
})

await test('GET /api/search giới hạn size ngoài khoảng', async () => {
  const res = await call('/api/search', { params: { q: 'test', size: 9999 } })
  assertEqual(res.status, 200, 'HTTP status')
  assert(res.json.pageSize <= 100, `pageSize phải bị chặn, nhận ${res.json.pageSize}`)
})

await test('GET /api/search chống SQL injection', async () => {
  const res = await call('/api/search', { params: { q: "'; DROP TABLE pages; --" } })
  assertEqual(res.status, 200, 'HTTP status')
  const after = await call('/api/search', { params: { q: 'hà nội' } })
  assert(after.json?.results?.length > 0, 'chỉ mục phải còn nguyên sau truy vấn độc')
})

await test('GET /api/search phản hồi dưới 3 giây', async () => {
  const started = Date.now()
  await call('/api/search', { params: { q: 'bóng đá' } })
  const took = Date.now() - started
  assert(took < 3000, `mất ${took}ms`)
})

await test('GET /api/suggest nhận tham số mà giao diện thật gửi (q)', async () => {
  const res = await call('/api/suggest', { params: { q: 'ha', limit: 5 } })
  assertEqual(
    res.status,
    200,
    `giao diện gửi ?q= (searchApi.ts suggest) nhưng máy chủ trả ${res.status}: ${res.json?.detail ?? ''}`
  )
  const list = Array.isArray(res.json) ? res.json : res.json?.suggestions
  assert(Array.isArray(list), 'phải trả mảng gợi ý')
})

await test('GET /api/suggest hoạt động với tham số prefix', async () => {
  const res = await call('/api/suggest', { params: { prefix: 'ha', limit: 5 } })
  assertEqual(res.status, 200, 'HTTP status')
  const list = Array.isArray(res.json) ? res.json : res.json?.suggestions
  assert(Array.isArray(list) && list.length > 0, 'phải trả mảng gợi ý không rỗng')
})

await test('GET /api/images trả cấu trúc đúng', async () => {
  const res = await call('/api/images', { params: { q: 'hà nội', size: 10 } })
  assertEqual(res.status, 200, 'HTTP status')
  assert(Array.isArray(res.json?.results), 'results phải là mảng')
  assert(typeof res.json?.pagesScanned === 'number', 'phải có pagesScanned')
})

await test('GET /api/feed trả bảng tin', async () => {
  const res = await call('/api/feed', { params: { seed: 12345, page: 1, size: 12 } })
  assertEqual(res.status, 200, 'HTTP status')
  assert(res.json !== null, 'phải trả JSON')
})

group('3. CORS')

await test('Preflight OPTIONS trả đúng một Access-Control-Allow-Origin', async () => {
  const res = await call('/api/events', {
    method: 'OPTIONS',
    headers: {
      'Access-Control-Request-Method': 'POST',
      'Access-Control-Request-Headers': 'content-type'
    }
  })
  assertIn(res.status, [200, 204], 'HTTP status')
  const value = res.headers.get('access-control-allow-origin')
  assertEqual(value, ORIGIN, 'Access-Control-Allow-Origin')
  assert(!value.includes(','), `header bị nhân đôi: ${value}`)
})

await test('GET thật trả đúng một Access-Control-Allow-Origin', async () => {
  const res = await call('/api/search', { params: { q: 'test' } })
  const value = res.headers.get('access-control-allow-origin')
  assertEqual(value, ORIGIN, 'Access-Control-Allow-Origin')
  assert(!value.includes(','), `header bị nhân đôi: ${value}`)
})

await test('Origin lạ không được cấp CORS', async () => {
  const res = await call('/api/search', {
    params: { q: 'test' },
    headers: { Origin: 'http://ke-tan-cong.example' }
  })
  const value = res.headers.get('access-control-allow-origin')
  assert(
    value === null || value === undefined,
    `origin lạ vẫn nhận được CORS: ${value}`
  )
})

await test('Header CORS trên tuyến history không nhân đôi', async () => {
  const res = await call('/api/history/visits')
  const value = res.headers.get('access-control-allow-origin')
  if (value) {
    assert(!value.includes(','), `header bị nhân đôi: ${value}`)
  }
})

await test('Header CORS trên tuyến downloads không nhân đôi', async () => {
  const res = await call('/api/downloads')
  const value = res.headers.get('access-control-allow-origin')
  if (value) {
    assert(!value.includes(','), `header bị nhân đôi: ${value}`)
  }
})

await test('Header CORS trên tuyến settings không nhân đôi', async () => {
  const res = await call('/api/settings')
  const value = res.headers.get('access-control-allow-origin')
  if (value) {
    assert(!value.includes(','), `header bị nhân đôi: ${value}`)
  }
})

for (const method of ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']) {
  await test(`Preflight cho phép ${method} trên /api/settings`, async () => {
    const res = await call('/api/settings', {
      method: 'OPTIONS',
      headers: {
        'Access-Control-Request-Method': method,
        'Access-Control-Request-Headers': 'authorization,content-type'
      }
    })
    assertIn(res.status, [200, 204], `preflight bị từ chối (${res.text.slice(0, 60)})`)
    const allowed = (res.headers.get('access-control-allow-methods') ?? '').toUpperCase()
    assert(allowed.includes(method), `Access-Control-Allow-Methods = "${allowed}", thiếu ${method}`)
  })
}

for (const method of ['GET', 'POST', 'PATCH', 'DELETE']) {
  await test(`Preflight cho phép ${method} trên /api/downloads`, async () => {
    const res = await call('/api/downloads', {
      method: 'OPTIONS',
      headers: {
        'Access-Control-Request-Method': method,
        'Access-Control-Request-Headers': 'authorization,content-type'
      }
    })
    assertIn(res.status, [200, 204], `preflight bị từ chối (${res.text.slice(0, 60)})`)
    const allowed = (res.headers.get('access-control-allow-methods') ?? '').toUpperCase()
    assert(allowed.includes(method), `Access-Control-Allow-Methods = "${allowed}", thiếu ${method}`)
  })
}

await test('Preflight qua Gateway và request thật qua service khớp nhau', async () => {
  const probe = `cors_${Date.now().toString(36)}`
  await call('/api/auth/register', { method: 'POST', body: { username: probe, password: state.password } })
  const login = await call('/api/auth/login', { method: 'POST', body: { username: probe, password: state.password } })
  if (!login.json?.token) skip('không đăng nhập được tài khoản thử')

  const preflight = await call('/api/settings', {
    method: 'OPTIONS',
    headers: { 'Access-Control-Request-Method': 'PATCH', 'Access-Control-Request-Headers': 'authorization,content-type' }
  })
  const preflightOk = [200, 204].includes(preflight.status)

  const real = await call('/api/settings', {
    method: 'PATCH',
    token: login.json.token,
    body: { thu: 1 },
    headers: { Origin: ORIGIN }
  })
  const realBlocked = real.status === 403 && real.text.includes('Invalid CORS')

  assert(
    !(preflightOk && realBlocked),
    'Gateway cho preflight PATCH qua nhưng service phía sau trả 403 "Invalid CORS request" — ' +
      'trình duyệt vượt preflight rồi mới hỏng ở request thật, còn curl (không Origin) vẫn 200'
  )
})

await test('Header X-Device-Id được phép trong preflight', async () => {
  const res = await call('/api/downloads', {
    method: 'OPTIONS',
    headers: {
      'Access-Control-Request-Method': 'GET',
      'Access-Control-Request-Headers': 'authorization,x-device-id'
    }
  })
  const allowed = (res.headers.get('access-control-allow-headers') ?? '').toLowerCase()
  assert(
    allowed.includes('x-device-id'),
    `Access-Control-Allow-Headers = "${allowed}" — giao diện gửi X-Device-Id trên mọi lời gọi /api/downloads (userDataApi.ts deviceHeader)`
  )
})

await test('Header If-Match được phép trong preflight', async () => {
  const res = await call('/api/settings', {
    method: 'OPTIONS',
    headers: {
      'Access-Control-Request-Method': 'PATCH',
      'Access-Control-Request-Headers': 'authorization,if-match'
    }
  })
  const allowed = (res.headers.get('access-control-allow-headers') ?? '').toLowerCase()
  assert(
    allowed.includes('if-match'),
    `Access-Control-Allow-Headers = "${allowed}" — giao diện gửi If-Match để chống ghi đè (userDataApi.ts settingsApi.gop)`
  )
})

group('4. Xác thực')

await test('Endpoint riêng tư trả 401 khi không có token', async () => {
  for (const path of ['/api/history/visits', '/api/downloads', '/api/settings']) {
    const res = await call(path)
    assertEqual(res.status, 401, `${path} phải trả 401`)
  }
})

await test('Token rác bị từ chối', async () => {
  const res = await call('/api/settings', { token: 'khong-phai-jwt' })
  assertEqual(res.status, 401, 'HTTP status')
})

await test('JWT chữ ký sai bị từ chối', async () => {
  const fake = [
    Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url'),
    Buffer.from(JSON.stringify({ sub: 'admin', roles: ['ADMIN'], exp: 9999999999 })).toString('base64url'),
    'chu-ky-gia'
  ].join('.')
  const res = await call('/api/settings', { token: fake })
  assertEqual(res.status, 401, 'HTTP status')
})

await test('POST /api/auth/register tạo tài khoản', async () => {
  const res = await call('/api/auth/register', {
    method: 'POST',
    body: { username: state.username, password: state.password }
  })
  assertIn(res.status, [200, 201], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('Đăng ký trùng tên bị từ chối', async () => {
  const res = await call('/api/auth/register', {
    method: 'POST',
    body: { username: state.username, password: state.password }
  })
  assertIn(res.status, [400, 409, 422], 'HTTP status')
})

await test('Đăng ký mật khẩu yếu bị từ chối', async () => {
  const res = await call('/api/auth/register', {
    method: 'POST',
    body: { username: `yeu_${Date.now().toString(36)}`, password: '123' }
  })
  assertIn(res.status, [400, 422], 'HTTP status')
})

await test('POST /api/auth/login trả token', async () => {
  const res = await call('/api/auth/login', {
    method: 'POST',
    body: { username: state.username, password: state.password }
  })
  assertEqual(res.status, 200, `HTTP status (thân: ${res.text.slice(0, 200)})`)
  assert(res.json?.token, 'phải có token')
  state.token = res.json.token
  state.refreshToken = res.json.refreshToken
})

await test('Đăng nhập sai mật khẩu bị từ chối', async () => {
  const res = await call('/api/auth/login', {
    method: 'POST',
    body: { username: state.username, password: 'sai-mat-khau-hoan-toan' }
  })
  assertIn(res.status, [400, 401, 403], 'HTTP status')
})

await test('Phản hồi đăng nhập không lộ mật khẩu', async () => {
  const res = await call('/api/auth/login', {
    method: 'POST',
    body: { username: state.username, password: state.password }
  })
  assert(!res.text.includes(state.password), 'thân phản hồi chứa mật khẩu gốc')
  assert(!/\$2[aby]\$/.test(res.text), 'thân phản hồi chứa băm BCrypt')
})

await test('POST /api/auth/refresh cấp token mới', async () => {
  if (!state.refreshToken) {
    skip('không có refresh token')
  }
  const res = await call('/api/auth/refresh', {
    method: 'POST',
    body: { refreshToken: state.refreshToken }
  })
  assertEqual(res.status, 200, `HTTP status (thân: ${res.text.slice(0, 200)})`)
  assert(res.json?.token, 'phải có token mới')
  state.token = res.json.token
  state.refreshToken = res.json.refreshToken ?? state.refreshToken
})

group('5. Lịch sử (history-service)')

await test('GET /api/history/visits với token hợp lệ', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/history/visits', { token: state.token, params: { page: 0, size: 10 } })
  assertEqual(res.status, 200, `HTTP status (thân: ${res.text.slice(0, 200)})`)
  assert(Array.isArray(res.json?.content), 'content phải là mảng')
})

await test('POST /api/history/visits ghi lượt ghé', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/history/visits', {
    method: 'POST',
    token: state.token,
    body: { url: 'https://vnexpress.net/kiem-thu', title: 'Bài kiểm thử' }
  })
  assertIn(res.status, [200, 201, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('Lượt ghé vừa ghi đọc lại được', async () => {
  if (!state.token) skip('chưa đăng nhập')
  await sleep(600)
  const res = await call('/api/history/visits', { token: state.token, params: { page: 0, size: 50 } })
  const found = res.json?.content?.find((item) => item.url === 'https://vnexpress.net/kiem-thu')
  assert(found, 'không tìm thấy lượt ghé vừa ghi')
  state.visitId = found.id
})

await test('Tìm trong lịch sử theo từ khoá', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/history/visits', {
    token: state.token,
    params: { q: 'kiểm thử', page: 0, size: 20 }
  })
  assertEqual(res.status, 200, 'HTTP status')
})

await test('POST /api/history/searches ghi truy vấn', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/history/searches', {
    method: 'POST',
    token: state.token,
    body: { query: 'truy vấn kiểm thử', resultCount: 42 }
  })
  assertIn(res.status, [200, 201, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('Người dùng khác không đọc được lịch sử của mình', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const other = `khac_${Date.now().toString(36)}`
  await call('/api/auth/register', { method: 'POST', body: { username: other, password: state.password } })
  const login = await call('/api/auth/login', { method: 'POST', body: { username: other, password: state.password } })
  if (!login.json?.token) skip('không đăng nhập được tài khoản thứ hai')
  const res = await call('/api/history/visits', { token: login.json.token, params: { page: 0, size: 50 } })
  assertEqual(res.status, 200, 'HTTP status')
  const leaked = res.json?.content?.find((item) => item.url === 'https://vnexpress.net/kiem-thu')
  assert(!leaked, 'RÒ RỈ: người dùng khác đọc được lịch sử không phải của họ')
})

await test('DELETE một mục lịch sử', async () => {
  if (!state.token || !state.visitId) skip('không có mục để xoá')
  const res = await call(`/api/history/visits/${encodeURIComponent(state.visitId)}`, {
    method: 'DELETE',
    token: state.token
  })
  assertIn(res.status, [200, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

group('6. Tải xuống (downloads-service)')

await test('GET /api/downloads với token hợp lệ', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/downloads', {
    token: state.token,
    params: { page: 0, size: 20 },
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' }
  })
  assertEqual(res.status, 200, `HTTP status (thân: ${res.text.slice(0, 200)})`)
  assert(Array.isArray(res.json), 'phải trả mảng')
})

await test('POST /api/downloads tạo bản ghi', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/downloads', {
    method: 'POST',
    token: state.token,
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' },
    body: {
      id: state.downloadId,
      sourceUrl: 'https://example.com/tep.pdf',
      fileName: 'tep-kiem-thu.pdf',
      mimeType: 'application/pdf',
      totalBytes: 1048576
    }
  })
  assertIn(res.status, [200, 201, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('POST /api/downloads là idempotent', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const body = {
    id: state.downloadId,
    sourceUrl: 'https://example.com/tep.pdf',
    fileName: 'tep-kiem-thu.pdf',
    mimeType: 'application/pdf',
    totalBytes: 1048576
  }
  const res = await call('/api/downloads', {
    method: 'POST',
    token: state.token,
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' },
    body
  })
  assertIn(res.status, [200, 201, 204, 409], `HTTP status (thân: ${res.text.slice(0, 200)})`)
  const list = await call('/api/downloads', {
    token: state.token,
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' },
    params: { page: 0, size: 100 }
  })
  const matches = (list.json ?? []).filter((item) => item.id === state.downloadId)
  assert(matches.length <= 1, `tạo ${matches.length} bản ghi cho cùng id`)
})

await test('PATCH /api/downloads cập nhật tiến độ', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call(`/api/downloads/${encodeURIComponent(state.downloadId)}`, {
    method: 'PATCH',
    token: state.token,
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' },
    body: { receivedBytes: 524288, state: 'IN_PROGRESS' }
  })
  assertIn(res.status, [200, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('Bản ghi tải xuống đọc lại đúng tiến độ', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/downloads', {
    token: state.token,
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' },
    params: { page: 0, size: 100 }
  })
  const found = (res.json ?? []).find((item) => item.id === state.downloadId)
  assert(found, 'không tìm thấy bản ghi vừa tạo')
  assertEqual(found.receivedBytes, 524288, 'receivedBytes')
})

await test('Máy chủ KHÔNG trả localPath ra ngoài', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/downloads', {
    token: state.token,
    headers: { 'X-Device-Id': 'thiet-bi-kiem-thu' },
    params: { page: 0, size: 100 }
  })
  const found = (res.json ?? []).find((item) => item.id === state.downloadId)
  if (!found) skip('không có bản ghi')
  assert(!('localPath' in found), 'localPath bị lộ ra API công khai')
})

await test('DELETE một bản ghi tải xuống', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call(`/api/downloads/${encodeURIComponent(state.downloadId)}`, {
    method: 'DELETE',
    token: state.token
  })
  assertIn(res.status, [200, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

group('7. Tuỳ chọn (settings-service)')

await test('GET /api/settings trả khối tuỳ chọn', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/settings', { token: state.token })
  assertEqual(res.status, 200, `HTTP status (thân: ${res.text.slice(0, 200)})`)
  assert(res.json?.settings !== undefined, 'phải có trường settings')
  assert(typeof res.json?.version === 'number', 'phải có version dạng số')
})

await test('PATCH /api/settings gộp thay đổi', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const before = await call('/api/settings', { token: state.token })
  const res = await call('/api/settings', {
    method: 'PATCH',
    token: state.token,
    headers: { 'If-Match': String(before.json.version) },
    body: { giaoDien: 'toi', coKiemThu: true }
  })
  assertEqual(res.status, 200, `HTTP status (thân: ${res.text.slice(0, 200)})`)
  assertEqual(res.json?.settings?.giaoDien, 'toi', 'giá trị vừa ghi')
})

await test('If-Match cũ bị từ chối bằng 409', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/settings', {
    method: 'PATCH',
    token: state.token,
    headers: { 'If-Match': '0' },
    body: { xungDot: true }
  })
  assertIn(res.status, [409, 412], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('Version tăng sau mỗi lần ghi', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const before = await call('/api/settings', { token: state.token })
  const patched = await call('/api/settings', {
    method: 'PATCH',
    token: state.token,
    headers: { 'If-Match': String(before.json.version) },
    body: { demSoLan: Date.now() }
  })
  assert(patched.json.version > before.json.version, 'version phải tăng')
})

group('8. Bóng đá (football-service, Go)')

await test('GET /api/football/v1/... phản hồi', async () => {
  const res = await call('/api/football/v1/matches')
  assertIn(res.status, [200, 404], `HTTP status (thân: ${res.text.slice(0, 200)})`)
  if (res.status === 404) {
    skip('endpoint /v1/matches không tồn tại - cần xác minh đường dẫn thật')
  }
})

await test('Gateway cắt đúng tiền tố cho football', async () => {
  const res = await call('/api/football/v1/health')
  assert(res.status !== 502, 'Gateway không kết nối được football-service')
})

group('9. Số liệu (analytics-service)')

await test('POST /api/events nhận sự kiện công khai', async () => {
  const res = await call('/api/events', {
    method: 'POST',
    body: { type: 'search', query: 'kiem thu', ts: Date.now() }
  })
  assertIn(res.status, [200, 201, 202, 204], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

await test('GET /api/events KHÔNG mở công khai', async () => {
  const res = await call('/api/events')
  assertIn(res.status, [401, 403, 405], `GET phải bị chặn, nhận ${res.status}`)
})

group('10. Quản trị')

await test('/api/admin/** trả 401 khi không có gì', async () => {
  const res = await call('/api/admin/analytics')
  assertIn(res.status, [401, 403], 'HTTP status')
})

await test('/api/admin/** từ chối khoá API sai', async () => {
  const res = await call('/api/admin/analytics', { apiKey: 'khoa-hoan-toan-sai-nhung-du-dai' })
  assertIn(res.status, [401, 403], 'HTTP status')
})

await test('/api/admin/** từ chối token người dùng thường', async () => {
  if (!state.token) skip('chưa đăng nhập')
  const res = await call('/api/admin/analytics', { token: state.token })
  assertIn(res.status, [401, 403], `người dùng thường vào được /api/admin (${res.status})`)
})

await test('/api/admin/analytics chấp nhận khoá API đúng', async () => {
  if (!ADMIN_KEY) skip('không có ADMIN_API_KEY')
  const res = await call('/api/admin/analytics', { apiKey: ADMIN_KEY })
  assertIn(res.status, [200, 404], `HTTP status (thân: ${res.text.slice(0, 200)})`)
})

group('11. Header bảo mật')

await test('X-Content-Type-Options: nosniff', async () => {
  const res = await call('/api/search', { params: { q: 'test' } })
  assertEqual(res.headers.get('x-content-type-options'), 'nosniff', 'header')
})

await test('X-Frame-Options: DENY', async () => {
  const res = await call('/api/search', { params: { q: 'test' } })
  assertEqual(res.headers.get('x-frame-options'), 'DENY', 'header')
})

await test('Referrer-Policy: no-referrer', async () => {
  const res = await call('/api/search', { params: { q: 'test' } })
  assertEqual(res.headers.get('referrer-policy'), 'no-referrer', 'header')
})

await test('Không lộ Server/X-Powered-By', async () => {
  const res = await call('/api/search', { params: { q: 'test' } })
  const server = res.headers.get('server')
  const powered = res.headers.get('x-powered-by')
  assert(!powered, `X-Powered-By bị lộ: ${powered}`)
  assert(!server || !/\d/.test(server), `Server lộ phiên bản: ${server}`)
})

await test('Lỗi không lộ stack trace', async () => {
  const res = await call('/api/settings', { token: 'token-hong' })
  assert(!res.text.includes('java.lang'), 'thân phản hồi chứa stack trace Java')
  assert(!res.text.includes('org.springframework'), 'thân phản hồi chứa lớp Spring')
})

group('12. Định dạng lỗi RFC 7807')

await test('Lỗi 404 dùng application/problem+json', async () => {
  const res = await call('/api/search/khong-co-endpoint-nay')
  if (res.status === 401) skip('bị chặn ở tầng xác thực trước')
  const type = res.headers.get('content-type') ?? ''
  assert(
    type.includes('problem+json') || res.json?.type !== undefined,
    `content-type là ${type}, thân: ${res.text.slice(0, 150)}`
  )
})

await test('Lỗi 400 có trường title và status', async () => {
  const res = await call('/api/auth/login', { method: 'POST', body: { username: '' } })
  if (![400, 422].includes(res.status)) skip(`nhận ${res.status}`)
  assert(res.json?.title !== undefined || res.json?.detail !== undefined, 'thiếu title/detail')
})

group('13. Giới hạn tần suất')

const burst = []
for (let i = 0; i < 50; i += 1) {
  burst.push(
    call('/api/search', { params: { q: 'tan suat', page: i }, timeout: 30000 }).catch(() => ({
      status: 0,
      headers: new Headers(),
      text: '',
      json: null
    }))
  )
}
const burstResponses = await Promise.all(burst)

await test('Giới hạn tần suất kích hoạt khi bắn 50 request', async () => {
  const codes = burstResponses.map((r) => r.status)
  const ok = codes.filter((c) => c === 200).length
  const limited = codes.filter((c) => c === 429).length
  const failed = codes.filter((c) => c === 0).length
  assert(ok > 0, `không request nào thành công (${failed} hỏng)`)
  assert(
    limited > 0,
    `0/50 request bị chặn dù cấu hình burstCapacity=30 — giới hạn tần suất có thể không hoạt động (${ok} thành công, ${failed} hỏng)`
  )
})

await test('Phản hồi 429 kèm Retry-After', async () => {
  const limited = burstResponses.find((r) => r.status === 429)
  if (!limited) skip('không kích hoạt được giới hạn tần suất')
  assert(limited.headers.get('retry-after'), 'thiếu header Retry-After')
})

await test('Giới hạn tần suất không làm treo request', async () => {
  const failed = burstResponses.filter((r) => r.status === 0).length
  assert(failed === 0, `${failed}/50 request hết thời gian chờ thay vì trả 429`)
})

group('14. Dịch vụ nội bộ không lộ ra ngoài')

await test('Các cổng service nội bộ không truy cập được từ máy thật', async () => {
  const ports = [8081, 8082, 8083, 8084, 8085, 8086, 8087]
  const reachable = []
  for (const port of ports) {
    try {
      const res = await fetch(`http://localhost:${port}/actuator/health`, {
        signal: AbortSignal.timeout(2000)
      })
      if (res.ok) reachable.push(port)
    } catch {
      void 0
    }
  }
  assert(
    reachable.length === 0,
    `các cổng nội bộ mở ra máy thật: ${reachable.join(', ')} - bỏ qua Gateway được`
  )
})

const pass = results.filter((r) => r.status === 'PASS').length
const fail = results.filter((r) => r.status === 'FAIL').length
const skipped = results.filter((r) => r.status === 'SKIP').length

let current = ''
for (const r of results) {
  if (r.suite !== current) {
    current = r.suite
    console.log(`\n${current}`)
  }
  const icon = r.status === 'PASS' ? 'PASS' : r.status === 'SKIP' ? 'SKIP' : 'FAIL'
  console.log(`  [${icon}] ${r.name}${r.message ? `\n         → ${r.message}` : ''}`)
}

console.log(`\n${'='.repeat(70)}`)
console.log(`TỔNG: ${results.length} phép thử | ${pass} đạt | ${fail} hỏng | ${skipped} bỏ qua`)
console.log('='.repeat(70))

if (fail > 0) {
  console.log('\nDANH SÁCH HỎNG:')
  for (const r of results.filter((x) => x.status === 'FAIL')) {
    console.log(`  ${r.suite} → ${r.name}`)
    console.log(`      ${r.message}`)
  }
}

process.exit(fail > 0 ? 1 : 0)
