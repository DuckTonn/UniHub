# UniHub Microservices — Phase 3

## Phạm vi và tương thích

Workshop và Registration vẫn chạy trong backend Spring Boot. Các route, payload, JWT và role dùng bởi React và Android vẫn giữ nguyên. PostgreSQL là nguồn dữ liệu duy nhất cho số chỗ hiển thị và việc cấp chỗ. Redis vẫn phục vụ rate limiting và các chức năng khác của backend.

- **Phòng và lịch.** Chỉ workshop đã xuất bản mới chiếm phòng. Draft có thể chuẩn bị song song. Khi xuất bản hoặc sửa workshop đã xuất bản, backend khóa hàng phòng và trả HTTP 409 nếu khung giờ trùng với workshop đã xuất bản khác. Hai workshop có thể nối tiếp nhau tại cùng một mốc giờ. Quy tắc giới hạn sức chứa và chính sách khóa thay đổi sức chứa khi phòng đang dùng tiếp tục áp dụng.
- **Giữ chỗ.** Backend khóa hàng workshop trước khi đếm đăng ký `PENDING` và `SUCCESS`. Việc cập nhật số chỗ và ghi đăng ký cùng nằm trong một giao dịch. Workshop miễn phí vẫn trả QR ngay. Workshop có phí vẫn trả khóa idempotency và giữ chỗ 10 phút. Thanh toán, hủy và hết hạn dùng cùng khóa workshop để một chỗ chỉ được trả một lần. Job dọn hold hết hạn chạy mỗi phút. Thanh toán thành công tiếp tục phát sự kiện Phase 2.
- **Thanh toán.** Kết quả đã hoàn tất được đọc theo khóa idempotency của đúng workshop và người dùng. Hold đã hết hạn không thể thanh toán. Lỗi tạm thời từ nhà cung cấp giữ đăng ký ở trạng thái `PENDING` trong thời hạn. Xử lý trường hợp nhà cung cấp không xác định được giao dịch đã thu tiền thuộc Phase 4.

## Triển khai và quay lui

Flyway V13 tạo index cho lịch và đăng ký đang chiếm chỗ, rồi tính lại `remaining_slots` từ dữ liệu đã lưu. Giữ nguyên đăng ký cũ. Nếu trước đây số đăng ký đã vượt sức chứa, workshop hiển thị hết chỗ và backend từ chối đăng ký mới cho đến khi sức chứa còn lại dương. Lịch trùng đã tồn tại cần được điều chỉnh qua API quản trị. Kiểm tra trước khi triển khai vì migration cập nhật bảng workshop và xây lại index đăng ký.

Triển khai tất cả tiến trình ghi Workshop và Registration cùng phiên bản. Tránh để tiến trình Phase 2 dựa vào bộ đếm Redis trong khi tiến trình Phase 3 ghi số chỗ. Sau triển khai, đối chiếu `remaining_slots` với số đăng ký `PENDING` và `SUCCESS`. Tài liệu [Phase 2](microservices-phase-2.md) mô tả tùy chọn bật RabbitMQ và cách xử lý backlog.

Để quay về Phase 2, dừng các luồng ghi trước. Dùng bản sao lưu nhất quán hoặc quy trình migration ngược đã được kiểm chứng. Cần đối chiếu các bản ghi phát sinh, xử lý trường hợp đăng ký `FAILED` đi cùng một đăng ký mới mà index cũ không hỗ trợ, tái tạo cache Redis theo dữ liệu đã chốt, rồi mới mở lại luồng đăng ký.

## Kiểm chứng

`mvn -f backend/pom.xml verify` chạy các kiểm tra cạnh tranh trên PostgreSQL thật và cần các dịch vụ test như cấu hình CI. Smoke test Compose kiểm tra đăng nhập web/app, đủ chỗ rồi hết chỗ, số chỗ hiển thị và việc nhận một thông báo qua outbox khi bật profile messaging.
