import { setTimeout as sleep } from 'node:timers/promises'

const DEBUG_PORT = process.env.CDP_PORT ?? 9222
const results = []
let suite = ''

function group(name) {
  suite = name
}

class Skip extends Error {}

async function test(name, fn) {
  const started = Date.now()
  try {
    await fn()
    results.push({ suite, name, status: 'PASS', ms: Date.now() - started })
  } catch (error) {
    results.push({
      suite,
      name,
      status: error instanceof Skip ? 'SKIP' : 'FAIL',
      ms: Date.now() - started,
      message: error.message
    })
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: mong đợi ${JSON.stringify(expected)}, nhận ${JSON.stringify(actual)}`)
  }
}

const targets = await (await fetch(`http://localhost:${DEBUG_PORT}/json`)).json()
const page = targets.find((t) => t.url.startsWith('http://localhost:5173'))
if (!page) {
  console.error('Không tìm thấy cửa sổ renderer. Chạy: npx electron-vite dev -- --remote-debugging-port=9222')
  process.exit(2)
}

const ws = new WebSocket(page.webSocketDebuggerUrl)
let msgId = 1
const pending = new Map()
const consoleErrors = []
const networkFailures = []
const httpErrors = []
const requestUrls = new Map()

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = msgId++
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}

ws.addEventListener('message', (event) => {
  const msg = JSON.parse(event.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(msg.error.message))
    else resolve(msg.result)
    return
  }
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'assert'].includes(msg.params.type)) {
    consoleErrors.push(msg.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    consoleErrors.push(`[exception] ${msg.params.exceptionDetails.text} ${msg.params.exceptionDetails.exception?.description ?? ''}`)
  }
  if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
    consoleErrors.push(`[log] ${msg.params.entry.text}`)
  }
  if (msg.method === 'Network.requestWillBeSent') {
    requestUrls.set(msg.params.requestId, msg.params.request.url)
  }
  if (msg.method === 'Network.responseReceived') {
    const { status, url } = msg.params.response
    if (status >= 400 && url.includes('localhost:8080')) {
      httpErrors.push(`${status} ${url}`)
    }
  }
  if (msg.method === 'Network.loadingFailed') {
    const url = requestUrls.get(msg.params.requestId) ?? '?'
    if (url.includes('localhost:8080') || url.includes('localhost:8090')) {
      networkFailures.push(`${msg.params.errorText} ${url}`)
    }
  }
})

await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve)
  ws.addEventListener('error', reject)
})

await send('Runtime.enable')
await send('Network.enable')
await send('Log.enable')
await send('Page.enable')

async function evaluate(expression) {
  const res = await send('Runtime.evaluate', {
    expression: `(() => { try { return JSON.stringify(${expression}) } catch (e) { return JSON.stringify({ __err: String(e) }) } })()`,
    returnByValue: true,
    awaitPromise: true
  })
  const raw = res.result?.value
  if (raw === undefined) return undefined
  const parsed = JSON.parse(raw)
  if (parsed && parsed.__err) throw new Error(parsed.__err)
  return parsed
}

async function key(k, code, vk, modifiers = 0) {
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers })
}

async function pressEnter() {
  for (const type of ['keyDown', 'char', 'keyUp']) {
    await send('Input.dispatchKeyEvent', {
      type,
      key: 'Enter',
      code: 'Enter',
      windowsVirtualKeyCode: 13,
      nativeVirtualKeyCode: 13,
      text: '\r',
      unmodifiedText: '\r'
    })
  }
}

async function clickBySelector(selector) {
  return evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)})
    if (!el) return { clicked: false }
    el.click()
    return { clicked: true }
  })()`)
}

async function bodyText() {
  return evaluate('document.body.innerText')
}

const before = consoleErrors.length

group('1. Khởi động ứng dụng')

await test('Cửa sổ renderer nạp được React', async () => {
  const info = await evaluate(`({
    rootLen: document.getElementById('root')?.innerHTML.length ?? -1,
    title: document.title
  })`)
  assert(info.rootLen > 1000, `cây React quá nhỏ (${info.rootLen} ký tự)`)
  assertEqual(info.title, 'VnSearch Browser', 'tiêu đề cửa sổ')
})

await test('Không có lỗi JavaScript lúc khởi động', async () => {
  assert(consoleErrors.length === before, `lỗi console: ${consoleErrors.slice(before).join(' | ')}`)
})

await test('Thanh công cụ hiện đủ nút chính', async () => {
  const found = await evaluate(`(() => {
    const labels = [...document.querySelectorAll('button[aria-label]')].map(b => b.getAttribute('aria-label'))
    return { labels, count: labels.length }
  })()`)
  assert(found.count > 5, `chỉ có ${found.count} nút`)
  const joined = found.labels.join('|')
  for (const need of ['Tải xuống']) {
    assert(joined.includes(need), `thiếu nút "${need}". Có: ${joined}`)
  }
})

group('2. Trang chủ và bảng tin')

await test('Trang chủ gọi /api/feed thành công', async () => {
  const failed = networkFailures.filter((f) => f.includes('/api/feed'))
  const errors = httpErrors.filter((e) => e.includes('/api/feed'))
  assert(failed.length === 0, `gọi thất bại: ${failed.join(', ')}`)
  assert(errors.length === 0, `lỗi HTTP: ${errors.join(', ')}`)
})

await test('Bảng tin hiện nội dung', async () => {
  const text = await bodyText()
  assert(text.length > 200, `trang chủ gần như trống (${text.length} ký tự)`)
})

group('3. Tìm kiếm')

const OMNIBOX = 'input[aria-label="Ô địa chỉ và tìm kiếm"]'

await test('Ô địa chỉ nhận được văn bản người dùng gõ', async () => {
  const focused = await evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(OMNIBOX)})
    if (!el) return { found: false }
    el.focus()
    return { found: true }
  })()`)
  assert(focused.found, 'không tìm thấy ô địa chỉ')
  await send('Input.insertText', { text: 'hà nội' })
  await sleep(400)
  const value = await evaluate(`document.querySelector(${JSON.stringify(OMNIBOX)})?.value ?? null`)
  assertEqual(value, 'hà nội', 'giá trị ô địa chỉ')
})

await sleep(1500)
await pressEnter()
await sleep(3500)

await test('Gõ vào ô địa chỉ gọi được /api/suggest', async () => {
  const suggestCalls = [...requestUrls.values()].filter((u) => u.includes('/api/suggest'))
  assert(suggestCalls.length > 0, 'giao diện không gọi /api/suggest khi gõ')
  const bad = httpErrors.filter((e) => e.includes('/api/suggest'))
  assert(
    bad.length === 0,
    `gợi ý trả lỗi: ${bad.join(', ')} — đã gọi: ${suggestCalls[suggestCalls.length - 1]}`
  )
})

await test('Enter thực hiện tìm kiếm', async () => {
  const searchCalls = [...requestUrls.values()].filter((u) => u.includes('/api/search'))
  assert(searchCalls.length > 0, 'không gọi /api/search sau khi bấm Enter')
  const bad = httpErrors.filter((e) => e.includes('/api/search'))
  assert(bad.length === 0, `tìm kiếm trả lỗi: ${bad.join(', ')}`)
})

await test('Một lần bấm Enter không gọi /api/search quá 2 lần', async () => {
  const searchCalls = [...requestUrls.values()].filter((u) => u.includes('/api/search'))
  for (const url of new Set(searchCalls)) {
    const count = searchCalls.filter((u) => u === url).length
    assert(
      count <= 2,
      `gọi ${count} lần cùng một URL: ${url} (2 lần là bình thường ở chế độ dev vì React StrictMode)`
    )
  }
})

await test('Kết quả tìm kiếm hiển thị trên màn hình', async () => {
  const text = await bodyText()
  assert(
    /kết quả|results|\d+\s*(kết quả|ms)/i.test(text) || text.length > 500,
    `không thấy dấu hiệu kết quả (${text.length} ký tự)`
  )
})

group('4. Nhật ký')

await test('Ctrl+H mở được trang Nhật ký', async () => {
  await key('h', 'KeyH', 72, 2)
  await sleep(1200)
  const text = await bodyText()
  assert(text.includes('Nhật ký'), 'không thấy tiêu đề "Nhật ký"')
})

await test('Nhật ký hiện trạng thái đúng khi chưa đăng nhập', async () => {
  const text = await bodyText()
  assert(
    text.includes('Đăng nhập để xem nhật ký') || text.includes('Nhật ký còn trống'),
    `trạng thái lạ: ${text.slice(0, 200)}`
  )
})

await test('Nhật ký không gây lỗi mạng', async () => {
  const bad = httpErrors.filter((e) => e.includes('/api/history'))
  const unauthorized = bad.filter((e) => e.startsWith('401'))
  const other = bad.filter((e) => !e.startsWith('401'))
  assert(other.length === 0, `lỗi ngoài 401: ${other.join(', ')}`)
})

await test('Esc đóng được Nhật ký', async () => {
  await key('Escape', 'Escape', 27)
  await sleep(800)
  const stillOpen = await evaluate(`!!document.querySelector('.absolute.inset-0.z-30')`)
  assert(!stillOpen, 'lớp phủ Nhật ký vẫn mở sau khi bấm Esc')
})

group('5. Tải xuống')

await test('Nút Tải xuống tồn tại và bấm được', async () => {
  const res = await clickBySelector('button[aria-label="Tải xuống"]')
  assert(res.clicked, 'không tìm thấy nút Tải xuống')
  await sleep(1000)
})

await test('Bảng Tải xuống hiện nội dung', async () => {
  const text = await bodyText()
  assert(
    text.includes('Tải xuống') || text.includes('Chưa có tệp nào'),
    `không thấy bảng Tải xuống: ${text.slice(-300)}`
  )
})

await test('Bảng Tải xuống không gây lỗi ngoài 401', async () => {
  const bad = httpErrors.filter((e) => e.includes('/api/downloads') && !e.startsWith('401'))
  assert(bad.length === 0, `lỗi: ${bad.join(', ')}`)
})

group('6. Cài đặt')

await test('Nút Cài đặt trên thanh bên mở được trang Cài đặt', async () => {
  const res = await clickBySelector('button[aria-label="Cài đặt"]')
  assert(res.clicked, 'không tìm thấy nút Cài đặt trên thanh bên')
  await sleep(1200)
  const opened = await evaluate(`(() => {
    const overlay = document.querySelector('.absolute.inset-0.z-30')
    return { open: !!overlay, text: overlay ? overlay.innerText.slice(0, 120) : null }
  })()`)
  assert(
    opened.open,
    'bấm nút Cài đặt không mở gì cả — nút không có onClick (SideRail.tsx:152) dù SettingsPage đã có sẵn'
  )
})

await test('Nút Dịch trang có phản hồi khi bấm', async () => {
  const snapshotBefore = await evaluate('document.body.innerHTML.length')
  await clickBySelector('button[aria-label="Dịch trang"]')
  await sleep(800)
  const snapshotAfter = await evaluate('document.body.innerHTML.length')
  assert(
    snapshotBefore !== snapshotAfter,
    'bấm nút Dịch trang không làm gì cả — nút không có onClick (SideRail.tsx:128)'
  )
})

await test('Nút Ứng dụng trên thanh dấu trang có phản hồi khi bấm', async () => {
  const snapshotBefore = await evaluate('document.body.innerHTML.length')
  const res = await clickBySelector('button[aria-label="Ứng dụng"]')
  if (!res.clicked) skipTest('không tìm thấy nút Ứng dụng')
  await sleep(800)
  const snapshotAfter = await evaluate('document.body.innerHTML.length')
  assert(
    snapshotBefore !== snapshotAfter,
    'bấm nút Ứng dụng không làm gì cả — nút không có onClick (BookmarksBar.tsx:69)'
  )
})

await test('Cài đặt không gây lỗi ngoài 401', async () => {
  const bad = httpErrors.filter((e) => e.includes('/api/settings') && !e.startsWith('401'))
  assert(bad.length === 0, `lỗi: ${bad.join(', ')}`)
})

group('7. Bóng đá')

await test('Mở được tab Bóng đá', async () => {
  await key('Escape', 'Escape', 27)
  await sleep(400)
  const res = await clickBySelector('button[aria-label="Bóng đá"]')
  if (!res.clicked) skipTest('không tìm thấy nút Bóng đá')
  await sleep(2500)
})

await test('Bóng đá gọi được football-service', async () => {
  const calls = [...requestUrls.values()].filter(
    (u) => u.includes(':8090') || u.includes('/api/football')
  )
  assert(calls.length > 0, 'giao diện không gọi football-service')
  const failed = networkFailures.filter((f) => f.includes(':8090') || f.includes('football'))
  assert(
    failed.length === 0,
    `không kết nối được: ${failed[0]} — giao diện gọi thẳng ${calls[0]} thay vì đi qua Gateway, mà docker-compose không mở cổng 8090`
  )
})

await test('Bóng đá đi qua Gateway thay vì gọi thẳng cổng 8090', async () => {
  const direct = [...requestUrls.values()].filter((u) => u.includes('localhost:8090'))
  assert(
    direct.length === 0,
    `${direct.length} lời gọi đi thẳng cổng 8090, bỏ qua Gateway (tuyến /api/football/** đã có trong bảng tuyến nhưng không được dùng)`
  )
})

group('8. Tổng hợp lỗi thời gian chạy')

await test('Không có lời gọi mạng thất bại tới backend', async () => {
  assert(networkFailures.length === 0, networkFailures.slice(0, 5).join(' | '))
})

await test('Không có lỗi HTTP 5xx từ backend', async () => {
  const server = httpErrors.filter((e) => /^5\d\d/.test(e))
  assert(server.length === 0, server.join(' | '))
})

await test('Không có lỗi CORS trong console', async () => {
  const cors = consoleErrors.filter((e) => /CORS|Access-Control/i.test(e))
  assert(cors.length === 0, cors.slice(0, 3).join(' | '))
})

await test('Không có ngoại lệ JavaScript chưa bắt', async () => {
  const exceptions = consoleErrors.filter((e) => e.startsWith('[exception]'))
  assert(exceptions.length === 0, exceptions.slice(0, 3).join(' | '))
})

function skipTest(reason) {
  throw new Skip(reason)
}

const pass = results.filter((r) => r.status === 'PASS').length
const fail = results.filter((r) => r.status === 'FAIL').length
const skipped = results.filter((r) => r.status === 'SKIP').length

let current = ''
for (const r of results) {
  if (r.suite !== current) {
    current = r.suite
    console.log(`\n${current}`)
  }
  console.log(`  [${r.status}] ${r.name}${r.message ? `\n         → ${r.message}` : ''}`)
}

console.log(`\n${'='.repeat(70)}`)
console.log(`TỔNG E2E: ${results.length} phép thử | ${pass} đạt | ${fail} hỏng | ${skipped} bỏ qua`)
console.log('='.repeat(70))

if (consoleErrors.length > 0) {
  console.log(`\nLỖI CONSOLE (${consoleErrors.length}):`)
  for (const e of consoleErrors.slice(0, 15)) console.log(`  - ${e.slice(0, 220)}`)
}
if (httpErrors.length > 0) {
  console.log(`\nLỖI HTTP TỚI BACKEND (${httpErrors.length}):`)
  for (const e of [...new Set(httpErrors)].slice(0, 15)) console.log(`  - ${e}`)
}
if (networkFailures.length > 0) {
  console.log(`\nGỌI MẠNG THẤT BẠI (${networkFailures.length}):`)
  for (const e of [...new Set(networkFailures)].slice(0, 15)) console.log(`  - ${e}`)
}

ws.close()
process.exit(fail > 0 ? 1 : 0)
