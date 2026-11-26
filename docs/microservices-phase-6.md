# UniHub Microservices — Phase 6

## CI và đóng gói bản phát hành

`UniHub CI` chạy Maven cho backend và gateway, FastAPI, build/test Android và Compose. Compose build cả React, chạy lại các hợp đồng API từ Phase 1–5, rồi kiểm tra CORS, quyền truy cập, giới hạn tải, Prometheus, mất Redis, restore PostgreSQL và triển khai/quay lui qua registry tạm. Job dependency-audit lưu báo cáo npm và Python. Việc chạy audit thành công xác nhận có báo cáo hợp lệ. Cần đọc số lượng và mức độ advisory trước khi phát hành.

Sau khi CI của đúng commit trên `main` thành công, người phụ trách phát hành chạy workflow thủ công **Publish UniHub release** trên `main`. Workflow build bốn image Linux, chạy lại smoke test trên các image đó, đẩy chúng lên GHCR theo commit SHA và xuất artifact `unihub-release-<sha>` chứa `release.json`. Manifest định danh từng image bằng digest. Workflow phát hành không kết nối máy chủ triển khai. Giữ artifact và quyền pull của GHCR cho cả bản đang chạy và bản quay lui. Thiết lập biến repository `VITE_TELEGRAM_BOT` nếu UI cần tên bot công khai.

## Triển khai

1. Kiểm tra báo cáo audit, xác nhận bản backup PostgreSQL khôi phục được và tải cả hai manifest từ các run đã chọn. Dùng Docker Compose 2.24.4 trở lên, Python 3.11 trở lên và đúng tên project đang chạy. Đăng nhập GHCR trên host bằng tài khoản có quyền pull. Theo dõi không gian đĩa dành cho image và dữ liệu.
2. Chuẩn bị file runtime ngoài Git. Điền `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, hai khóa JWT mạnh và khác nhau, cùng `FRONTEND_URL` là một HTTPS origin. Giữ các khóa đang dùng nếu chúng được quản lý an toàn. Nếu môi trường từng dùng khóa demo, đổi cả hai khóa trước khi mở traffic để vô hiệu token cũ. Đặt mật khẩu Redis và RabbitMQ phù hợp khi sử dụng. Bật `COMPOSE_PROFILES=messaging` cùng cấu hình outbox khi cần. Có thể pin image Redis, RabbitMQ và Prometheus bằng `REDIS_IMAGE`, `RABBITMQ_IMAGE` và `PROMETHEUS_IMAGE`.
3. Chuẩn bị admin thực và vô hiệu các tài khoản demo từ migration V2/V10/V11. Với DB mới, chạy Flyway bằng backend trên mạng riêng khi chưa mở traffic. Lần khởi động production đầu có thể dừng sau migration vì chưa có admin hợp lệ. Dùng vai trò quản trị DB để xem và chạy `ops/disable-demo-users.sql`, rồi tạo tài khoản quản trị với mật khẩu băm bcrypt và trạng thái `ACTIVE`. Giữ các hàng demo có liên kết lịch sử. Profile `prod` chặn khởi động nếu còn tài khoản demo hoạt động hoặc thiếu admin.
4. Cấu hình reverse proxy có TLS trên host, chuyển tiếp vào `127.0.0.1:18080`, giữ Host và ghi đè `X-Forwarded-For` bằng IP client. Đặt `UNIHUB_TRUSTED_TLS_PROXY` thành đúng địa chỉ nguồn của proxy nhìn từ container Nginx để rate limit dùng IP client. Mặc định cấu hình không tin header này từ kết nối TCP. Release overlay chạy backend với profile `prod`. Thanh toán mới trả 503 khi chưa có adapter thanh toán thật. Dùng rollback manifest thuộc dòng release đã hỗ trợ profile production này.

```bash
python ops/release.py --project unihub --env-file /etc/unihub/runtime.env \
  --manifest /srv/unihub/releases/release.json --monitoring --check

python ops/release.py --project unihub --env-file /etc/unihub/runtime.env \
  --manifest /srv/unihub/releases/release.json --monitoring --url https://unihub.example
```

Script kiểm tra cấu hình, pull image theo digest, đợi health check và gọi `/api/test`. Sau đó kiểm tra đăng nhập, trang workshop và cập nhật dashboard bằng tài khoản vận hành. Ghi lại manifest đã áp dụng. Quá trình thay container có thể gián đoạn ngắn trên một host.

## Monitoring và xử lý sự cố

- Prometheus chỉ publish giao diện trên loopback tại cổng `PROMETHEUS_PORT` mặc định 19090. Scrape backend và gateway trên cổng management riêng, cùng FastAPI `/metrics` trong mạng Compose. Xem trang **Targets**, **Alerts** và truy vấn PromQL qua SSH tunnel. Rule có sẵn cho target mất kết nối, 5xx và p95 tại gateway, dữ liệu vận hành cũ, hàng đợi PDF gần đầy và outbox tồn đọng. Chuyển alert ra ngoài cần cấu hình Alertmanager và kênh nhận riêng.
- Nginx sinh `X-Request-ID` và trả lại cho client trên cả phản hồi lỗi. Tìm cùng ID trong access log Nginx và log backend. Access log mới ghi phương thức, trạng thái và thời gian, không ghi token, query hay địa chỉ IP.
- Khi Redis mất kết nối, readiness backend xuống và API trả 503 có `Retry-After`. Kiểm tra kết nối/cache rồi quan sát API phục hồi. Khi FastAPI hỏng, kiểm tra số job đang chờ và báo lỗi trên dashboard. Khi outbox tồn đọng, kiểm tra RabbitMQ và dead-letter queue. Theo dõi số payment `PROCESSING` sau khi chỉnh provider.

## Quay lui và khôi phục dữ liệu

Nếu sự cố thuộc image và schema vẫn tương thích, chạy lại lệnh deploy với manifest trước đó, cùng runtime file và tên project. Kiểm tra lại HTTP, login và các giao dịch đang xử lý. Giữ volume, mạng và PostgreSQL hiện tại. Nếu đã chạy migration không tương thích, tạm dừng ghi và chọn điểm khôi phục theo chính sách PITR của PostgreSQL được quản lý. Khôi phục sang DB riêng trước, đối chiếu schema, bản ghi đăng ký, thanh toán, outbox và AI job, rồi quyết định cách chuyển kết nối theo quy trình vận hành.

CI diễn tập `pg_dump` và `pg_restore` trên DB tạm, so sánh số hàng và trạng thái Flyway. CI triển khai một image ứng viên, quay về digest gốc và kiểm tra cả hai qua HTTP. Hai image diễn tập dùng cùng phiên bản ứng dụng với metadata khác nhau. Việc diễn tập không thay thế kiểm chứng tương thích schema giữa hai phiên bản sản xuất. Bài tải CI gửi 16 yêu cầu đọc catalog với tối đa 8 đồng thời và ngưỡng p95 2,5 giây. Kết quả này là ngưỡng hồi quy tại runner, không phải dự báo sức tải production.
