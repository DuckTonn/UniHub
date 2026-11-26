# UniHub Microservices — Phase 2

## Phạm vi

Phase 2 chuyển thông báo in-app cho sự kiện đăng ký workshop thành công, bao gồm workshop miễn phí và thanh toán thành công, sang transactional outbox và RabbitMQ. Backend hiện tại vẫn sở hữu dữ liệu đăng ký, template và bản ghi thông báo. Email, Telegram, các loại thông báo khác và retry hiện có của chúng tiếp tục dùng luồng cũ.

Identity vẫn ở backend. Gateway, endpoint `/api/v1/auth/**`, JWT access và refresh, role, user ID và cấu trúc phản hồi không đổi. Giữ nguyên các khóa JWT đang dùng khi triển khai. CI kiểm tra đăng nhập web của student và đăng nhập app của staff, refresh, quyền truy cập và token từ cấu hình Phase 1 qua Nginx và gateway.

## Luồng đăng ký thành công

1. Listener đồng bộ ghi vào bảng `registration_notification_outbox` trong cùng giao dịch PostgreSQL với đăng ký hoặc kết quả thanh toán. Nếu giao dịch rollback, cả thay đổi đăng ký và outbox đều rollback. Mỗi đăng ký có tối đa một sự kiện cho luồng này.
2. Relay lấy từng bản ghi chưa publish bằng `FOR UPDATE SKIP LOCKED`, gửi UUID sự kiện tới exchange `unihub.notifications` với routing key `registration.succeeded.in-app.v1`, rồi ghi `published_at` sau broker confirm với mandatory routing. Bản ghi chưa confirm được thử lại. Payload chỉ chứa ID, không có email, chat ID, mã QR hoặc token.
3. Consumer khóa bản ghi theo ID, dùng template đăng ký hiện có để tạo thông báo, rồi ghi `delivered_at` trong cùng giao dịch. RabbitMQ có thể giao lại sau lỗi, kể cả khi database đã commit. Consumer bỏ qua ID đã xử lý để API `GET /api/v1/notifications` trả đúng một bản ghi.
4. Sau khi hết số lần thử của consumer, message được đưa vào `unihub.notifications.registration.in-app.v1.dead`. Xem nguyên nhân trước khi đưa lại nguyên event ID vào queue. Độ bền phụ thuộc vào PostgreSQL và volume của RabbitMQ. Outbox cũng giữ dấu đã giao để xử lý replay.

## Chạy và kiểm tra

Dùng file `.env.production` riêng cho môi trường đang chạy. Để bật luồng mới, đặt `NOTIFICATION_OUTBOX_ENABLED=true`, `RABBITMQ_USER=unihub` và đặt `RABBITMQ_PASSWORD` riêng. RabbitMQ dùng profile `messaging`, mạng nội bộ và volume `rabbitmq-data`.

```sh
docker compose --env-file .env.production --profile messaging config --quiet
docker compose --env-file .env.production --profile messaging up -d --build --wait
docker compose --env-file .env.production --profile messaging exec --user rabbitmq rabbitmq \
  rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

Backend vẫn nhận đăng ký khi RabbitMQ tạm vắng mặt. Thông báo in-app chờ trong outbox cho đến khi broker có thể nhận. Theo dõi các bản ghi `published_at IS NULL`, độ tuổi bản ghi lâu nhất và độ sâu của dead-letter queue. Dùng broker riêng, PostgreSQL riêng và Redis riêng khi chạy bộ tích hợp. Đặt `RUN_NOTIFICATION_OUTBOX_IT=true` và các biến kết nối tương ứng trước khi chạy `mvn -f backend/pom.xml verify`.

## Rollback

Tránh chạy song song các instance có cờ outbox khác nhau trong cùng đợt chuyển đổi. Tạm dừng nhận đăng ký mới khi đổi chế độ để chỉ một luồng ghi thông báo in-app. Nếu có thể, đợi relay và consumer xử lý hết bản ghi đang chờ, kiểm tra dead-letter queue, đặt `NOTIFICATION_OUTBOX_ENABLED=false` và khởi động lại backend. Luồng in-app cũ phục vụ các đăng ký mới.

Nếu broker đang lỗi, giữ bảng outbox và volume. Ghi lại backlog, rồi xử lý bằng một instance cấu hình outbox khi đã lên lịch khôi phục. Giữ migration V12 trên database khi rollback bằng cờ. Việc quay về image Phase 1 cần xác minh tương thích Flyway vì image cũ chưa có V12.
