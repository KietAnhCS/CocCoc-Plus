import { create } from 'zustand'
import type { FootballLeague, FootballPlayer } from '../lib/footballApi'
import type { FavouriteTeam } from './footballStore'

/**
 * Năm tab của trang bóng đá — đúng năm tab của `MainTabView` bản iOS:
 * Home · Leagues · Teams · Players · Profile.
 */
export type FootballTab = 'home' | 'leagues' | 'teams' | 'players' | 'profile'

/**
 * Một màn hình chi tiết đang mở chồng lên tab.
 *
 * Bản iOS đặt mỗi tab trong một `NavigationStack` riêng, nên mỗi tab nhớ được
 * vị trí của mình. Ở đây chỉ có MỘT ngăn xếp và nó bị xoá khi đổi tab — vì
 * trên máy tính, thanh tab nằm ngay trước mắt suốt cả phiên, và một cú bấm vào
 * tab đang mở dở nửa chừng thì người bấm mong nó về đầu, không phải quay lại
 * đúng chỗ đã rời đi ba phút trước.
 */
export type FootballRoute =
  | { kind: 'league'; league: FootballLeague }
  | { kind: 'team'; team: FavouriteTeam }
  | { kind: 'player'; player: FootballPlayer }

interface FootballAppState {
  open: boolean
  tab: FootballTab
  stack: FootballRoute[]

  openApp: (tab?: FootballTab) => void
  closeApp: () => void
  setTab: (tab: FootballTab) => void
  push: (route: FootballRoute) => void
  pop: () => void
}

/**
 * Trạng thái của TRANG bóng đá toàn màn hình.
 *
 * Tách khỏi `footballStore` (ngày, giải, mùa, đội yêu thích) có chủ ý: cái kia
 * là TUỲ CHỌN của người dùng và được lưu xuống localStorage, còn cái này là vị
 * trí điều hướng — mở lại trình duyệt mà rơi thẳng vào trang bóng đá thay vì
 * trang chủ là điều không ai chọn.
 */
export const useFootballAppStore = create<FootballAppState>((set, get) => ({
  open: false,
  tab: 'home',
  stack: [],

  openApp: (tab) => set(tab ? { open: true, tab, stack: [] } : { open: true }),
  closeApp: () => set({ open: false }),
  setTab: (tab) => set({ tab, stack: [] }),

  push: (route) => set({ stack: [...get().stack, route] }),
  pop: () => set({ stack: get().stack.slice(0, -1) })
}))
