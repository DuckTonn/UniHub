# Lộ trình kỹ thuật UniHub Web

Lộ trình tiếp tục từ cổng API Spring, React/Vite và các màn hình sinh viên hiện có. Mỗi mốc có thể giao trong một hoặc vài PR nhỏ. Dùng [runbook Phase 6](microservices-phase-6.md) cho triển khai và quay lui.

## 1. Củng cố các luồng sinh viên

- Thêm kiểm thử trình duyệt cho đăng nhập, tải lịch workshop, đăng ký miễn phí, thanh toán thử, xem QR và đọc thông báo. Dùng dữ liệu kiểm thử cố định và reset độc lập cho từng bài.
- Tách màu, khoảng cách và kiểu focus đang lặp lại thành biến CSS dùng chung. Kiểm tra ở chiều rộng 320, 390 và 1280 px, bằng bàn phím và với chế độ giảm chuyển động.
- Điều kiện nghiệm thu là CI chạy các luồng chính, trạng thái focus rõ ràng và các trang không cuộn ngang ở 320 px.

### Chạy bộ kiểm thử mốc 1

```sh
cd frontend
npm ci
npx playwright install chromium
npm run test:e2e
```

Playwright chạy bản build React ở cổng 4178 và tạo lại dữ liệu API riêng cho từng bài kiểm thử. Ba cấu hình Chromium kiểm tra chiều rộng 320, 390 và 1280 px. Job `frontend-e2e` lưu báo cáo HTML và trace khi thất bại. Job `compose` kiểm tra tiếp API thật qua gateway, PostgreSQL và RabbitMQ.

Màu, khoảng cách và viền focus dùng chung đặt trong `frontend/src/index.css`. Dùng các biến `--ui-*` khi bổ sung màn hình sinh viên. Giữ hiệu ứng chuyển động tùy thuộc vào `prefers-reduced-motion`.

## 2. Thêm công cụ tìm workshop và quản lý hộp thư

- Bổ sung tìm theo tên hoặc diễn giả, lọc theo ngày, chỗ còn và mức phí ở API danh sách. Đồng bộ bộ lọc vào URL và thực hiện truy vấn trên toàn bộ danh sách đã phân trang.
- Thêm tổng số chưa đọc trên thanh điều hướng và bộ lọc chưa đọc trên máy chủ. Dùng dữ liệu biên nhận theo tài khoản và giữ API phân trang tương thích với Android.
- Điều kiện nghiệm thu là bộ lọc vẫn đúng sau khi tải lại hoặc chia sẻ URL. Mở thông báo trên một thiết bị cập nhật dữ liệu khi thiết bị kia tải lại.

## 3. Đo hiệu năng và tăng độ tin cậy

- Đo các trang quan trọng bằng Lighthouse trên cấu hình mobile cố định. Tách bundle của trang quản trị, biểu đồ và các hộp thoại chỉ được dùng theo nhu cầu. Đặt ngân sách dung lượng cho từng nhóm route từ kết quả đo ban đầu.
- Chuẩn hóa cache và trạng thái tải, lỗi, thử lại cho dữ liệu đọc. Giữ logic ghi như đăng ký, thanh toán và đánh dấu đã đọc theo hợp đồng API hiện có.
- Điều kiện nghiệm thu là báo cáo CI theo dõi kích thước JavaScript, LCP và CLS. Mỗi PR vượt ngân sách cần giải thích bằng số liệu trước và sau.

## 4. Cải thiện vận hành của quản trị viên

- Ưu tiên quy trình kiểm tra workshop, đối soát và check-in theo vai trò. Thêm bộ lọc cho những hồ sơ cần xử lý và hiển thị lỗi của từng giao dịch.
- Liên kết mã yêu cầu với log để truy vết lỗi gateway, backend và worker. Theo dõi các chỉ số tổng hợp và tránh ghi mã QR, JWT hoặc nội dung thông báo vào telemetry.
- Điều kiện nghiệm thu là chạy lại các bài diễn tập lỗi Redis, phục hồi PostgreSQL và rollback image trên staging trước khi phát hành cho người dùng.

Nên bắt đầu với mốc 1 để các thay đổi tìm kiếm, hiệu năng và thao tác quản trị sau đó có kiểm thử hồi quy bao phủ.
