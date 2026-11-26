# UniHub Microservices — Phase 4

## Payment

React tiếp tục gọi `POST /api/v1/workshops/{id}/pay` với JWT và `Idempotency-Key` nhận từ đăng ký. Thành công trả cùng `transactionId` và `qrCode` khi gửi lại khóa. Backend kiểm tra người sở hữu và workshop của khóa trước khi dùng kết quả. Lỗi provider tạm thời trả 503 và có thể gửi lại cùng khóa. Provider xác nhận từ chối thì backend lưu kết quả và trả 402.

1. Backend ghi `PROCESSING` và commit trước khi gọi provider. Provider dùng cùng khóa cho mọi lần thử và ghi kết quả trong giao dịch riêng.
2. Khi provider xác nhận đã thu tiền, backend chuyển payment sang `COMPLETED` và registration sang `SUCCESS`, cấp QR và phát sự kiện outbox trong giao dịch xác nhận. Một kết quả provider lặp lại dùng dữ liệu đã lưu.
3. Job đối soát chạy mỗi 30 giây. Với khóa đang xử lý, job tra kết quả provider. Khi hold hết hạn hoặc đăng ký bị hủy, provider đóng khóa chưa từng thu tiền để tránh thu muộn. Nếu khóa đã thu tiền, workshop còn hiệu lực tiếp tục xác nhận đăng ký. Workshop đã hủy chuyển payment sang `REFUND_REQUIRED` để đối soát hoàn tiền và giữ đăng ký đã hủy.

Bộ mô phỏng mặc định ghi vào `mock_payment_operations`. API mô phỏng công khai cũ vẫn chạy trong môi trường phát triển. Cấu hình `PAYMENT_URL` cũ được thay bằng adapter mô phỏng nội bộ. Tích hợp provider thật cần triển khai `PaymentGatewayClient` với idempotency, tra cứu kết quả và khả năng đóng khóa chưa thu tiền. Việc hoàn tiền tự động cần adapter và chính sách của provider.

Đối soát các hàng còn `PROCESSING` và `REFUND_REQUIRED` theo ID payment. Khi workshop bị hủy sau khi payment đã chuyển `COMPLETED`, payment giữ trạng thái đó và cần đối chiếu với workshop đã hủy như Phase 3. Khi provider tạm mất kết nối, các hold đang `PROCESSING` tiếp tục giữ chỗ cho tới khi có kết quả xác định.

## Attendance

API `GET /api/v1/workshops/{id}/attendees` và `POST /api/v1/checkins/sync` giữ nguyên quyền STAFF, QR, payload và các trường kết quả. Mỗi đăng ký hợp lệ có một bản ghi check-in. Quét lại trên cùng máy hoặc gửi từ máy khác trả về duplicate và giữ thời gian của bản ghi mà server nhận đầu tiên. Server trả QR lỗi trong `failures`; lỗi lưu trữ làm cả batch rollback để có thể gửi lại.

Android lên lịch đồng bộ qua WorkManager cả khi đang offline và bảo toàn lượt quét chưa xác nhận khi tải lại danh sách. Sau phản hồi một phần, ứng dụng chỉ xác nhận lượt thành công và duplicate. Lỗi mạng và HTTP 429/5xx được thử lại tối đa bốn lần sau lần đầu. QR bị từ chối vẫn có trên máy để nhân viên kiểm tra và đồng bộ lại thủ công. Phiên bản Android cũ cần cập nhật để nhận cách xử lý phản hồi một phần.

## Triển khai và quay lui

- Tạm dừng luồng thanh toán khi chuyển phiên bản. Phase 3 chưa lưu dấu vết các lần thu tiền có kết quả mơ hồ. Đối chiếu các hold còn tồn với hệ thống thanh toán đang chạy trước khi cho gửi lại.
- Áp dụng Flyway V14 và triển khai đồng bộ các backend ghi payment. Bảng và cột mới là bổ sung. Tại thời điểm triển khai, kiểm tra job đối soát và luồng giữ chỗ. Cập nhật Android khi thuận tiện để nhận xử lý offline mới.
- Để quay lui backend, tạm dừng thanh toán và đối soát hết các mục `PROCESSING` trước. Lưu lại giao dịch `REFUND_REQUIRED` và các kết quả provider để xử lý nghiệp vụ. Phase 3 không hiểu chúng nên không chạy hai phiên bản backend ghi payment đồng thời. Giữ dữ liệu đã thu và QR đã cấp khi quay lui.

## Kiểm chứng

`mvn -f backend/pom.xml verify` kiểm tra concurrent retry, mất xác nhận sau giao dịch provider, đóng hold, hủy workshop và gửi lại batch có QR lỗi. CI chạy build/test Android và smoke test qua gateway cho thanh toán lặp lại, quyền STAFF, đồng bộ một phần và QR gửi lại.
