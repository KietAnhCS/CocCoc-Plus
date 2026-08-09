/**
 * Cấu hình Vitest cho browser-app.
 *
 * VÌ SAO CÓ TỆP NÀY. Trước nó, hơn 5.000 dòng TypeScript không có một bài test
 * nào — trong khi backend có 521 bài. Sự lệch đó không phải chuyện thẩm mỹ:
 * `browser-app/src/main` chứa RANH GIỚI BẢO MẬT của ứng dụng (chính sách điều
 * hướng, ràng buộc IPC), và phần đó trước đây không có gì canh.
 *
 * VÌ SAO KHÔNG DÙNG `electron.vite.config.ts`. Tệp đó mô tả ba bó riêng biệt
 * (main / preload / renderer) và mỗi bó có một môi trường khác nhau —
 * `electron-vite` không phơi ra một cấu hình `test` dùng chung. Một tệp riêng
 * cũng nói rõ hơn: đây là cấu hình để CHẠY TEST, không phải để đóng gói.
 *
 * MÔI TRƯỜNG `node`, không phải `jsdom`. Mọi thứ được kiểm ở đây là logic
 * thuần: chính sách URL, cấu trúc dữ liệu, tầng gọi API. Không có bài nào dựng
 * component React, nên kéo cả một DOM giả vào chỉ làm chậm mà không kiểm thêm
 * được gì. `fetch`, `URL` và `AbortSignal.timeout` đều có sẵn trong Node 18+.
 */
import { defineConfig } from 'vitest/config'
import { resolve } from 'path'

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    // KHÔNG bật `globals`. Import tường minh `describe`/`it`/`expect` từ
    // 'vitest' thì trình kiểm kiểu thấy được chúng mà không phải nhét
    // `vitest/globals` vào `types` của cả hai tsconfig — nơi nó sẽ rò rỉ vào
    // cả mã sản phẩm.
    globals: false
  },
  resolve: {
    alias: {
      '@renderer': resolve(__dirname, 'src/renderer/src')
    }
  }
})
