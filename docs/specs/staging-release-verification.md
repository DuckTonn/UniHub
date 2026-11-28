# Kiểm chứng staging và phát hành

**Trạng thái đề xuất.** UniHub đã có smoke test Compose, diễn tập mất Redis, restore PostgreSQL và rollback. Mốc này chuẩn hóa cách chạy lại trên staging và ghi nhận phản hồi từ người tham gia.

## Môi trường và dữ liệu

- Dùng một staging riêng cho thử nghiệm và diễn tập, với PostgreSQL, Redis và cấu hình thanh toán của chính staging. Dùng tài khoản QA theo từng vai trò cùng workshop gắn nhãn, và ghi mã fixture trong báo cáo.
- Ưu tiên seed giả lập. Khi cần dữ liệu gần với production, khôi phục vào đích cô lập rồi xử lý dữ liệu nhạy cảm trước khi cấp quyền truy cập cho người thử. Giữ bản backup trong vị trí có kiểm soát truy cập.
- Ghi rõ múi giờ của backend, PostgreSQL và trình duyệt cho từng lần chạy. Dùng ca kiểm thử ở biên mở và đóng đăng ký để phát hiện sai khác giữa máy chủ và điện thoại.

## Kịch bản nghiệm thu

1. Sinh viên kích hoạt tài khoản, đăng nhập trên mobile, tìm workshop, đăng ký miễn phí và xem QR từ lịch sử. Kiểm tra thông báo chưa đọc, đánh dấu đã đọc và tải lại trình duyệt.
2. ADMIN tạo workshop có phí. Sinh viên thanh toán sandbox và gửi lại yêu cầu. STAFF check-in, quét lại, rồi gửi lại batch sau khi mất mạng. ADMIN hủy workshop, kiểm tra khoản cần hoàn và xác nhận hoàn sandbox khi chức năng đã được triển khai.
3. Tạm ngắt Redis của staging và ghi phản hồi API, readiness, thời gian phục hồi. Khôi phục PostgreSQL sang đích tạm rồi kiểm tra số bản ghi, Flyway và luồng nghiệp vụ. Triển khai ứng viên và quay lui về image của phiên bản trước. Đối chiếu digest đang chạy và tính tương thích schema theo [runbook](../microservices-phase-6.md).
4. Cho sinh viên và STAFF tự thao tác trên thiết bị của họ. Ghi bước phải dừng, số lần thử lại và lý do. Với mỗi chỉnh sửa UI, chạy lại cùng kịch bản và lưu kết quả. Ghi riêng kết quả UAT do người tham gia thực hiện và kết quả Playwright.

## Bằng chứng bàn giao

- Commit, digest từng image, phiên bản Flyway, môi trường và ngày chạy.
- Kết quả từng kịch bản, lỗi còn mở, cách tái hiện, người phụ trách và bằng chứng cho thao tác quay lui.
- Trạng thái fixture sau thử nghiệm và điểm xác nhận để phát hành. Loại bỏ JWT, QR, thông tin thanh toán và dữ liệu cá nhân khỏi log, ảnh và báo cáo trước khi chia sẻ.
