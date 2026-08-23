/**
 * Kiểm tra dữ liệu biểu mẫu tài khoản, **ở phía giao diện**.
 *
 * ĐÂY KHÔNG PHẢI LỚP BẢO VỆ — nói rõ trước khi nói bất cứ điều gì khác. Máy chủ
 * kiểm lại toàn bộ những luật này trong `UserService`, và nó là nơi duy nhất
 * quyết định. Một request `curl` bỏ qua hoàn toàn tệp này.
 *
 * VẬY TỆP NÀY ĐỂ LÀM GÌ. Để người dùng biết mình gõ sai **ngay khi gõ**, thay
 * vì bấm "Đăng ký", chờ một vòng mạng, rồi mới đọc được "mật khẩu phải dài ít
 * nhất 8 ký tự". Khoảng cách giữa lúc gõ sai và lúc biết mình sai càng ngắn thì
 * biểu mẫu càng dễ dùng.
 *
 * LUẬT PHẢI KHỚP VỚI MÁY CHỦ. Chặt hơn máy chủ thì chặn oan những giá trị hợp
 * lệ; lỏng hơn thì lời hứa "gõ thế này là được" bị máy chủ bác bỏ — trường hợp
 * sau tệ hơn, vì nó dạy người dùng đừng tin thông báo của giao diện. Nguồn
 * tham chiếu: `UserService.USERNAME_PATTERN`, `MIN_PASSWORD_LENGTH`,
 * `MAX_PASSWORD_LENGTH`.
 */

/** Giống `UserService.USERNAME_PATTERN`. */
const USERNAME_PATTERN = /^[a-zA-Z0-9._-]{3,32}$/

export const MIN_PASSWORD_LENGTH = 8
export const MAX_PASSWORD_LENGTH = 200

/**
 * @returns thông báo lỗi, hoặc `null` khi hợp lệ.
 *
 * Trả về chuỗi thay vì `boolean` vì mỗi cách sai cần một câu khác nhau: "quá
 * ngắn" và "có ký tự lạ" dẫn tới hai hành động sửa hoàn toàn khác.
 */
export function validateUsername(username: string): string | null {
  const trimmed = username.trim()
  if (!trimmed) {
    return 'Hãy nhập tên tài khoản.'
  }
  if (trimmed.length < 3) {
    return 'Tên tài khoản cần ít nhất 3 ký tự.'
  }
  if (trimmed.length > 32) {
    return 'Tên tài khoản tối đa 32 ký tự.'
  }
  if (!USERNAME_PATTERN.test(trimmed)) {
    return 'Chỉ dùng chữ không dấu, số, dấu chấm, gạch ngang và gạch dưới.'
  }
  return null
}

export function validatePassword(password: string): string | null {
  if (!password) {
    return 'Hãy nhập mật khẩu.'
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    return `Mật khẩu cần ít nhất ${MIN_PASSWORD_LENGTH} ký tự (đang có ${password.length}).`
  }
  if (password.length > MAX_PASSWORD_LENGTH) {
    return `Mật khẩu tối đa ${MAX_PASSWORD_LENGTH} ký tự.`
  }
  return null
}

/** Ô "nhập lại mật khẩu". Chưa gõ gì thì chưa báo lỗi — chỉ báo khi đã lệch. */
export function validateConfirmation(password: string, confirmation: string): string | null {
  if (!confirmation) {
    return null
  }
  return password === confirmation ? null : 'Hai lần nhập mật khẩu không khớp.'
}

/**
 * Độ mạnh mật khẩu, chỉ để **gợi ý**, không dùng để chặn.
 *
 * Chặn theo thang này sẽ tái lập đúng thứ mà luật mật khẩu ở máy chủ cố tình
 * tránh: ép người dùng vào một khuôn dễ đoán (`Password1!`). Ở đây nó chỉ nói
 * cho họ biết mật khẩu vừa gõ đang ở mức nào và cách làm nó tốt hơn — và cách
 * tốt nhất luôn là **dài hơn**, nên độ dài chiếm phần lớn điểm.
 */
export interface PasswordStrength {
  score: 0 | 1 | 2 | 3
  label: string
  hint: string
}

export function passwordStrength(password: string): PasswordStrength {
  if (password.length < MIN_PASSWORD_LENGTH) {
    return { score: 0, label: 'Quá ngắn', hint: `Cần ít nhất ${MIN_PASSWORD_LENGTH} ký tự.` }
  }

  // Điểm theo ĐỘ DÀI trước, mới tới sự đa dạng ký tự — đúng thứ tự ảnh hưởng
  // thật tới thời gian phá mật khẩu.
  let score = 0
  if (password.length >= 12) score++
  if (password.length >= 16) score++
  const variety = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^a-zA-Z0-9]/].filter((re) =>
    re.test(password)
  ).length
  if (variety >= 3) score++

  const capped = Math.min(3, score) as 0 | 1 | 2 | 3
  const labels = ['Yếu', 'Tạm được', 'Khá', 'Mạnh']
  const hints = [
    'Một cụm nhiều từ ghép lại sẽ mạnh hơn hẳn một từ có thêm ký tự lạ.',
    'Thêm vài ký tự nữa là tốt hơn nhiều so với thêm ký tự đặc biệt.',
    'Đã ổn. Dài hơn nữa vẫn tốt hơn nữa.',
    'Tốt.'
  ]
  return { score: capped, label: labels[capped], hint: hints[capped] }
}
