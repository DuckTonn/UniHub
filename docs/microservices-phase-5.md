# UniHub Microservices — Phase 5

## Xử lý tài liệu AI

Admin tiếp tục tải PDF qua `POST /api/v1/admin/workshops/{id}/ai-summary` và nhận 202 khi job đã được lưu trong PostgreSQL. Dashboard theo dõi cùng đường dẫn bằng GET và hiển thị trạng thái chờ, xử lý, hoàn tất hoặc lỗi. Cả hai route yêu cầu quyền ADMIN. FastAPI vẫn nằm sau mạng Compose và nhận `POST /summarize` với các trường multipart `workshop_id` và `file`. Phản hồi thành công gồm `workshop_id`, `summary` và `speaker`, có thể null.

- Mỗi workshop có một job hiện hành. Tải tài liệu mới thay job cũ. Khi hoàn tất, backend kiểm tra phiên bản job và giữ các trường mô tả hoặc diễn giả đã được quản trị viên sửa trong lúc xử lý.
- Hàng đợi có tối đa 20 job đang hoạt động. Khi đầy, backend trả 503 và `Retry-After`. Mỗi lần xử lý giữ quyền nhận job trong 60 giây. Nếu tiến trình dừng giữa chừng, worker có thể nhận lại job sau hạn đó. Lỗi tạm thời thử lại sau 15 giây với tổng cộng tối đa ba lần xử lý. PDF được gỡ khỏi hàng đợi khi thành công hoặc lỗi cuối cùng. PostgreSQL WAL và bản sao lưu vẫn tuân theo chính sách lưu giữ của hệ thống.
- FastAPI giới hạn PDF 10 MiB, 100 trang và hai upload đang xử lý mỗi tiến trình. Mỗi lần trích xuất chạy trong tiến trình con với giới hạn 8 giây và 256 MiB bộ nhớ địa chỉ trên Linux. Văn bản gửi sang mô hình tối đa 15.000 ký tự. PDF chỉ có ảnh cần OCR riêng. PDF mã hóa, hỏng hoặc không có văn bản trả về lỗi 400 hiện có.
- `LLM_PROVIDER=mock` dùng văn bản tại chỗ trong môi trường test. Gemini dùng cấu hình khóa và model sẵn có, với deadline riêng cho từng yêu cầu. Chỉ gửi tài liệu được phép xử lý qua nhà cung cấp đã cấu hình. Các lỗi provider trả thông báo chung và không ghi văn bản hay khóa vào log.

## Báo cáo và dashboard

Bảng `analytics_reports` giữ bản tổng hợp `global-v1` và thời điểm chụp. Backend dựng lại từ các bảng nghiệp vụ trong giao dịch PostgreSQL `REPEATABLE READ` mỗi 30 giây, dùng khóa để mỗi lần chỉ một backend dựng báo cáo. Khi bản tổng hợp chưa có hoặc quá 60 giây, API đọc trực tiếp trong một giao dịch nhất quán. Đây là mục tiêu độ mới trên mỗi lần đọc. Dashboard hiển thị thời điểm lấy dữ liệu qua trường bổ sung `dataAsOf`.

`GET /api/v1/admin/stats` giữ các trường cũ. Tỷ lệ tham dự dùng số check-in hợp lệ chia cho tổng lượt đăng ký ở workshop đã hoàn thành. Doanh thu vẫn tính từ payment `COMPLETED`. Các báo cáo chi tiết workshop đang dùng endpoint hiện có.

## Triển khai, kiểm chứng và quay lui

Triển khai migration Flyway V15 trước khi dùng tính năng mới. Migration tạo hàng đợi và bảng báo cáo. Mỗi lần dựng báo cáo có thể chạy lại từ dữ liệu gốc. Để quay riêng luồng đọc dashboard về dữ liệu trực tiếp, đặt `ANALYTICS_PROJECTION_ENABLED=false`. Theo dõi trạng thái tài liệu và timestamp trả về để kiểm tra tiến độ và độ mới.

Trước khi quay về Phase 4, ngừng nhận PDF và chờ job đang chờ hoặc đang xử lý hoàn tất. Có thể tắt worker bằng `AI_DOCUMENT_WORKER_ENABLED=false` nếu cần giữ job để xử lý sau. Giữ bảng V15 cho đến khi đối chiếu các job còn tồn. Triển khai lại frontend và backend Phase 4 cùng nhau để dashboard dùng luồng cũ. Tóm tắt đã ghi vào workshop và bản ghi check-in vẫn dùng được trên Phase 4.

Chạy FastAPI bằng `python -m pip install -r requirements-test.txt` và `python -m unittest discover -s tests -v` trong `ai-service`. Maven backend kiểm tra job quá hạn, tài liệu được thay thế, chỉnh sửa thủ công và đối chiếu snapshot với bảng nghiệp vụ. Smoke test Compose tải một PDF qua gateway bằng quyền ADMIN, kiểm tra sinh tóm tắt, quyền truy cập, cấu trúc báo cáo và các hợp đồng Phase 4.
