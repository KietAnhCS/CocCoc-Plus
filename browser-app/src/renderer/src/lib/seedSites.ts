/**
 * Sáu báo điện tử là seed của bộ crawl (xem MultiDomainCrawlRunner.java bên
 * backend). Toàn bộ nội dung tìm kiếm được đều bắt nguồn từ đây, nên đây
 * cũng là danh sách hợp lý nhất để làm dấu trang và lối tắt mặc định.
 *
 * Một nguồn duy nhất cho ba chỗ dùng: dấu trang khởi tạo (store/bookmarkStore.ts),
 * hàng lối tắt ở trang chủ (components/NewTabPage.tsx) và thanh dấu trang.
 */
export interface SeedSite {
  name: string
  url: string
}

export const SEED_SITES: SeedSite[] = [
  { name: 'VnExpress', url: 'https://vnexpress.net/' },
  { name: 'Tuổi Trẻ', url: 'https://tuoitre.vn/' },
  { name: 'Dân Trí', url: 'https://dantri.com.vn/' },
  { name: 'Thanh Niên', url: 'https://thanhnien.vn/' },
  { name: 'VietnamNet', url: 'https://vietnamnet.vn/' },
  { name: 'Nhân Dân', url: 'https://nhandan.vn/' }
]
