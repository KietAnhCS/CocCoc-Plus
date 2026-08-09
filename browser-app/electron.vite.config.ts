import { resolve } from 'path'
import { defineConfig } from 'electron-vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  main: {},
  preload: {
    // GÓI dependency vào bundle thay vì để `require` lúc chạy.
    //
    // electron-vite mặc định externalize mọi thứ trong `dependencies`, nên
    // preload build ra `require("@electron-toolkit/preload")`. Khung vỏ giao
    // diện chạy với `sandbox: true` (xem tabManager.ts), mà preload đã sandbox
    // thì `require` CHỈ phân giải được `electron` và vài built-in — không với
    // tới node_modules. Kết quả: preload chết ngay dòng import, `contextBridge`
    // không bao giờ chạy, `window.browser`/`window.win` là undefined, và React
    // đổ ở lần đọc đầu tiên. Cửa sổ vẫn hiện vì `did-finish-load` đã kích hoạt
    // — chỉ là một hình chữ nhật màu `backgroundColor`, không một dòng lỗi nào
    // trong terminal.
    build: { externalizeDeps: false }
  },
  renderer: {
    resolve: {
      alias: {
        '@renderer': resolve('src/renderer/src')
      }
    },
    plugins: [react(), tailwindcss()]
  }
})
