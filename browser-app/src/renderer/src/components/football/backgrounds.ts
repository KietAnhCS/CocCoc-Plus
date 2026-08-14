import bg1 from '../../assets/img_bg_1.jpg'
import bg3 from '../../assets/pitch.jpg'
import bg4 from '../../assets/img_bg_4.jpg'
import bg6 from '../../assets/img_bg_6.jpg'
import bg7 from '../../assets/img_bg_7.jpg'
import bg8 from '../../assets/img_bg_8.jpg'

/**
 * Ảnh nền của từng màn hình — đúng những tấm mà bản iOS dùng, chép thẳng từ
 * `FootballTracker/Resources/Assets/Images`. Giữ nguyên cách đánh số của bản
 * gốc để đối chiếu được: `img_bg_3` chính là `pitch.jpg` đã có sẵn ở đây.
 *
 * Nằm ở tệp riêng chứ không nằm trong `glass.tsx` vì một lý do rất cụ thể: quy
 * tắc `react-refresh/only-export-components` cấm một tệp component xuất ra thứ
 * không phải component (hằng chuỗi thì được, đối tượng thì không), và vi phạm
 * nó làm hỏng hot-reload của cả tệp.
 */
export const BACKGROUNDS = {
  home: bg1,
  teams: bg3,
  profile: bg4,
  players: bg6,
  leagues: bg7,
  fixtures: bg8
} as const
