# Lộ trình kỹ thuật UniHub Web

Lộ trình tiếp tục từ cổng API Spring, React/Vite và các màn hình sinh viên hiện có. Mỗi mốc có thể giao trong một hoặc vài PR nhỏ. Dùng [runbook Phase 6](microservices-phase-6.md) cho triển khai và quay lui.

## 1. Củng cố các luồng sinh viên

- Thêm kiểm thử trình duyệt cho đăng nhập, tải lịch workshop, đăng ký miễn phí, thanh toán thử, xem QR và đọc thông báo. Dùng dữ liệu kiểm thử cố định và reset độc lập cho từng bài.
- Tách màu, khoảng cách và kiểu focus đang lặp lại thành biến CSS dùng chung. Kiểm tra ở chiều rộng 320, 390 và 1280 px, bằng bàn phím và với chế độ giảm chuyển động.
- Điều kiện nghiệm thu là CI chạy các luồng chính, trạng thái focus rõ ràng và các trang không cuộn ngang ở 320 px.

### Chạy bộ kiểm thử mốc 1

Dùng Node.js 22.19 trở lên và npm 11.6.3 trở lên, theo yêu cầu của frontend.

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

### Hợp đồng bộ lọc mốc 2

- `GET /api/v1/workshops` nhận thêm `q` (tên hoặc diễn giả), `from` và `to` (ngày bắt đầu, tính cả hai đầu), `fee=FREE|PAID` và `seatsAvailable=true`. Bộ lọc áp dụng trước phân trang. Trang web giữ các tham số này và `page` trong URL.
- `GET /api/v1/notifications?unreadOnly=true` phân trang các thông báo chưa đọc. `GET /api/v1/notifications/unread-count` trả tổng `unreadCount` cho tài khoản hiện tại. Thanh điều hướng cập nhật tổng sau khi đã đánh dấu thành công hoặc khi cửa sổ nhận lại focus.

## 3. Đo hiệu năng và tăng độ tin cậy

- Đo các trang quan trọng bằng Lighthouse trên cấu hình mobile cố định. Tách bundle của trang quản trị, biểu đồ và các hộp thoại chỉ được dùng theo nhu cầu. Đặt ngân sách dung lượng cho từng nhóm route từ kết quả đo ban đầu.
- Chuẩn hóa cache và trạng thái tải, lỗi, thử lại cho dữ liệu đọc. Giữ logic ghi như đăng ký, thanh toán và đánh dấu đã đọc theo hợp đồng API hiện có.
- Điều kiện nghiệm thu là báo cáo CI theo dõi kích thước JavaScript, LCP và CLS. Mỗi PR vượt ngân sách cần giải thích bằng số liệu trước và sau.

### Đo hiệu năng mốc 3

Trong `frontend`, chạy `npm ci`, `npx playwright install chromium` và `npm run test:performance`. Job `frontend-performance` ghi tóm tắt vào GitHub Actions và lưu báo cáo HTML cùng `summary.json`, kể cả khi vượt ngưỡng.

- Đo bản build production với Lighthouse mobile và Chromium cố định theo lockfile. Mỗi route chạy ba lần với hồ sơ trình duyệt mới. Báo cáo lấy trung vị LCP và CLS, cùng tổng dung lượng gzip lớn nhất của các tệp JavaScript tải trong một lần mở trang.
- Đo đăng nhập, trang chủ sinh viên, workshop, lịch sử đăng ký, thông báo, dashboard quản trị và analytics. Máy chủ thử nghiệm cung cấp dữ liệu cố định và phiên sinh viên hoặc quản trị giả lập. Các phép đo này không cần `.env` cục bộ hoặc backend đang chạy.
- Bản mốc 2 (`776d4da`) dùng 261 KiB JavaScript gzip trên cả bảy route. Lần đo mobile cho LCP khoảng 0,75–4,06 giây, CLS cao nhất 0,312 ở dashboard. Tách route quản trị khỏi luồng sinh viên và nạp biểu đồ, chi tiết workshop, thanh toán, QR cùng các hộp thoại quản trị khi dùng. Các trang chính của sinh viên vẫn có trong bundle khởi tạo để tránh thêm lượt tải trước khi hiển thị nội dung.
- `frontend/performance/budgets.json` lưu ngân sách theo nhóm đăng nhập, sinh viên, quản trị và analytics. Để so sánh một thay đổi, build commit trước với `VITE_API_BASE_URL=/api/v1` và chạy `npm run test:performance -- /duong-dan/den/dist-cu`. Báo cáo ở `frontend/performance-results/baseline/`. Khi đề xuất tăng ngân sách, đính kèm số đo trước và sau từ cùng môi trường cho route bị ảnh hưởng.

Cache dữ liệu đọc dùng bộ nhớ riêng cho mỗi tài khoản, dùng lại kết quả trong 30 giây khi quay lại màn hình và gộp yêu cầu đang chạy trùng nhau. Thao tác ghi tiếp tục gọi API hiện có và xóa các mục cache bị ảnh hưởng. Các màn dùng cache có nút thử lại khi tải lỗi. Tổng chưa đọc trên thanh điều hướng tiếp tục đồng bộ sau thao tác đọc hoặc khi cửa sổ nhận lại focus.

## 4. Cải thiện vận hành của quản trị viên

- Ưu tiên quy trình kiểm tra workshop, đối soát và check-in theo vai trò. Thêm bộ lọc cho những hồ sơ cần xử lý và hiển thị lỗi của từng giao dịch.
- Liên kết mã yêu cầu với log để truy vết lỗi gateway, backend và worker. Theo dõi các chỉ số tổng hợp và tránh ghi mã QR, JWT hoặc nội dung thông báo vào telemetry.
- Điều kiện nghiệm thu là chạy lại các bài diễn tập lỗi Redis, phục hồi PostgreSQL và rollback image trên staging trước khi phát hành cho người dùng.

### Vận hành mốc 4

- ADMIN lọc trạng thái workshop trước khi phân trang, dùng **Drafts to review** để xem bản nháp và mở **View Attendances** để tra cứu tên, mã sinh viên, email hoặc trạng thái check-in trên toàn bộ danh sách.
- ADMIN mở **Review payments** từ dashboard hoặc một workshop có phí. Bộ lọc **Needs review** gồm giao dịch đang xử lý, thất bại, yêu cầu hoàn tiền và giao dịch đã thu tiền của workshop bị hủy. Mã tham chiếu và trạng thái gốc giúp đối chiếu với nhà cung cấp. Thao tác đối soát chỉ đọc. Xác nhận và ghi nhận việc hoàn tiền theo quy trình của nhà cung cấp.
- STAFF chọn **Sign in as check-in staff**, tìm và chọn workshop rồi quét bằng thiết bị nhập như bàn phím hoặc dán mã. Web gửi trực tuyến qua API đồng bộ idempotent của Android và phân biệt thành công, trùng và bị từ chối. Android tiếp tục hỗ trợ đồng bộ ngoại tuyến. Giờ quét web dùng múi giờ campus `Asia/Ho_Chi_Minh`.
- Tìm **Request ID** hiển thị khi thao tác lỗi trong log Nginx, gateway và backend. Từ log backend nối sang worker bằng mã thanh toán, UUID sự kiện thông báo hoặc cặp mã workshop và generation AI. Các bản ghi này dùng định danh vận hành và không ghi payload, QR, JWT hay nội dung thông báo. Prometheus theo dõi số giao dịch `PROCESSING` và `REFUND_REQUIRED` trong sổ cái.
- Job Compose chạy smoke qua gateway và diễn tập lỗi Redis, phục hồi PostgreSQL, rollback image trên hạ tầng CI tạm. Trước khi phát hành, chạy lại trên staging theo [runbook Phase 6](microservices-phase-6.md) và lưu kết quả của từng bài diễn tập.

Nên bắt đầu với mốc 1 để các thay đổi tìm kiếm, hiệu năng và thao tác quản trị sau đó có kiểm thử hồi quy bao phủ.
