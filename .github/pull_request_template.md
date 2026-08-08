<!--
  Mẫu này tự xuất hiện khi mở pull request.

  Nó tồn tại vì một lý do cụ thể: repo này từng có bốn commit liên tiếp đặt tên
  "minor". Không ai — kể cả người viết, sau ba tuần — nói được chúng đổi cái gì.
  Vài dòng mô tả lúc mở PR rẻ hơn nhiều so với việc đọc lại diff sáu tháng sau.
-->

## Thay đổi gì

<!-- Một hoặc hai câu. Viết cho người CHƯA biết bối cảnh. -->

## Vì sao

<!-- Vấn đề đang được giải quyết là gì? Nếu có issue, ghi "Closes #123".

     Phần này quan trọng hơn phần trên. Diff đã nói được "cái gì"; chỉ có bạn,
     ngay lúc này, mới nói được "vì sao". -->

## Đã kiểm chứng thế nào

<!-- Cụ thể vào. "Đã test" là chưa đủ.
     Ví dụ: "./mvnw verify → 399 test xanh" hoặc "chạy thật, gõ 3 truy vấn" -->

- [ ] `cd search-engine && ./mvnw -B clean verify` chạy xanh cục bộ
- [ ] `cd browser-app && npm run typecheck && npm run lint` chạy xanh (nếu có sửa frontend)

## Tự rà trước khi nhờ người khác đọc

- [ ] Tiêu đề PR theo Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `perf:`, `refactor:`, `build:`, `ci:`, `chore:`)
- [ ] Mã mới có test đi kèm, hoặc giải thích được vì sao không cần
- [ ] Không có bí mật nào bị commit (khoá, mật khẩu, token)
- [ ] Chú thích tiếng Việt viết **có dấu đầy đủ**
- [ ] Tài liệu đã cập nhật nếu thay đổi làm sai lệch nội dung đang có
