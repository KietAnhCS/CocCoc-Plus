import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { type FootballTeam } from '../lib/footballApi'

/**
 * Một đội đã ghim, kèm đủ thứ để vẽ nó khi CHƯA gọi mạng lần nào.
 *
 * Bản đầu chỉ lưu mã đội. Đủ cho bảng bên — nơi mã chỉ dùng để đẩy một trận đã
 * tải sẵn lên đầu — nhưng không đủ cho tab "Đội của tôi" của bản iOS, vốn phải
 * vẽ tên, huy hiệu và quốc gia của đội NGAY khi mở, trước cả khi lịch thi đấu
 * về. Với riêng mã số thì màn hình ấy chỉ hiện được "1234".
 */
export interface FavouriteTeam {
  id: string
  name: string
  shortName: string
  emblem: string
  country: string
  /** Giải đang đá — cần để hỏi lịch của đội. Xem `FootballTeam.leagueId`. */
  leagueId: string
}

/** Rút gọn một đội lấy từ API thành bản ghi đem đi ghim. */
export function toFavourite(team: FootballTeam): FavouriteTeam {
  return {
    id: team.id,
    name: team.name,
    shortName: team.shortName || team.name,
    emblem: team.emblem,
    country: team.country ?? '',
    leagueId: team.leagueId ?? ''
  }
}

/**
 * Tuỳ chọn của phần Thể thao.
 *
 * <h3>Vì sao đội yêu thích lưu ở MÁY, không lưu ở máy chủ</h3>
 *
 * Bản gốc FootballTracker có bảng `favorite_teams` khoá theo người dùng, kèm
 * cả một hệ tài khoản riêng (users, refresh_tokens, argon2, JWT). Repo này ĐÃ
 * có hệ tài khoản của nó — `/api/auth/*` với token 12 giờ và bảng phân quyền
 * trong SecurityConfig. Bê thêm một hệ thứ hai vào nghĩa là hai bảng người
 * dùng, hai kiểu token, hai chỗ để rò rỉ.
 *
 * Nên football-service được giữ KHÔNG BIẾT GÌ về người dùng: nó chỉ là một
 * tầng đệm trước API-Football. Danh sách đội yêu thích là tuỳ chọn hiển thị
 * của một máy, và localStorage là đúng chỗ cho nó. Ngày nào cần đồng bộ nhiều
 * máy thì đưa nó vào tài khoản SẴN CÓ của repo, chứ không dựng hệ thứ hai.
 */
interface FootballState {
  /** Các đội được ghim, theo thứ tự ghim. */
  favourites: FavouriteTeam[]
  /** Mã giải đang lọc; chuỗi rỗng = mọi giải. */
  leagueFilter: string
  /**
   * Số ngày lệch so với hôm nay. 0 = hôm nay, -1 = hôm qua, 1 = ngày mai.
   *
   * Không lưu vào localStorage: mở lại trình duyệt vào ngày hôm sau mà vẫn
   * đứng ở "hôm kia" là một trạng thái không ai cố ý chọn.
   */
  dayOffset: number

  toggleFavourite: (team: FavouriteTeam) => void
  removeFavourite: (teamId: string) => void
  /**
   * Cập nhật hồ sơ của một đội ĐÃ ghim, không thêm mới.
   *
   * Dùng để tự vá những bản ghi thiếu thông tin: bản lưu cũ chỉ có mã đội, và
   * lần đầu tab "Đội của tôi" tải được lịch của đội ấy là lần đầu ta biết tên
   * thật của nó. Không có hàm này thì một danh sách ghim từ bản cũ sẽ hiện mã
   * số mãi mãi.
   */
  rememberTeam: (team: FavouriteTeam) => void
  setLeagueFilter: (leagueId: string) => void
  setDayOffset: (offset: number) => void
}

/** Phần trạng thái thật sự nằm trong localStorage — xem `partialize`. */
type PersistedFootballState = Pick<FootballState, 'favourites' | 'leagueFilter'>

/** Đội này đã được ghim chưa. */
export function isFavourite(favourites: FavouriteTeam[], teamId: string): boolean {
  return favourites.some((team) => team.id === teamId)
}

export const useFootballStore = create<FootballState>()(
  persist(
    (set, get) => ({
      favourites: [],
      leagueFilter: '',
      dayOffset: 0,

      toggleFavourite: (team) => {
        const current = get().favourites
        set({
          favourites: isFavourite(current, team.id)
            ? current.filter((item) => item.id !== team.id)
            : [...current, team]
        })
      },

      removeFavourite: (teamId) =>
        set({ favourites: get().favourites.filter((team) => team.id !== teamId) }),

      rememberTeam: (team) => {
        const current = get().favourites
        if (!isFavourite(current, team.id)) {
          return
        }
        set({
          favourites: current.map((item) => (item.id === team.id ? { ...item, ...team } : item))
        })
      },

      setLeagueFilter: (leagueId) => set({ leagueFilter: leagueId }),
      setDayOffset: (offset) => set({ dayOffset: offset })
    }),
    {
      name: 'vnsearch-football',
      version: 2,

      /**
       * Bản 1 lưu `favouriteTeamIds: string[]`. Giữ lại các mã ấy và tạm lấy
       * chính mã làm tên: xoá sạch thì người dùng mất danh sách đã ghim mà
       * không hiểu vì sao, còn giữ lại thì `rememberTeam` sẽ vá tên thật ngay
       * lần đầu tab "Đội của tôi" tải xong lịch của đội đó.
       */
      migrate: (persisted, version) => {
        const state = (persisted ?? {}) as Partial<PersistedFootballState> & {
          favouriteTeamIds?: string[]
        }

        const carried: PersistedFootballState = {
          favourites: state.favourites ?? [],
          leagueFilter: state.leagueFilter ?? ''
        }

        if (version < 2 && Array.isArray(state.favouriteTeamIds)) {
          carried.favourites = state.favouriteTeamIds.map((id) => ({
            id,
            name: id,
            shortName: id,
            emblem: '',
            country: '',
            leagueId: ''
          }))
        }
        return carried
      },

      partialize: (state) => ({
        favourites: state.favourites,
        leagueFilter: state.leagueFilter
      })
    }
  )
)
