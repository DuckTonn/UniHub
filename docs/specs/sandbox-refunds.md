# Hoàn tiền sandbox và chống hoàn trùng

UniHub hỗ trợ hoàn tiền toàn phần qua bộ mô phỏng nội bộ sau khi workshop bị hủy hoặc giao dịch ở trạng thái `REFUND_REQUIRED`. Trang đối soát giữ mã giao dịch gốc và hiển thị riêng trạng thái cùng mã tham chiếu hoàn tiền.

## Phạm vi

- Cho phép ADMIN xác nhận hoàn toàn bộ một giao dịch đã thu tiền đủ điều kiện, qua bộ mô phỏng nội bộ.
- Giữ giao dịch thu tiền và lịch sử check-in thành các bản ghi riêng. Tách trạng thái hoàn tiền khỏi trạng thái thu tiền.
- Giai đoạn này giới hạn từng payment ở một khoản hoàn toàn phần. Hoàn một phần, hoàn nhiều lần và hoàn tiền thật thuộc mốc [tích hợp nhà cung cấp](payment-provider-rollout.md).

## Hợp đồng API

- `POST /api/v1/admin/payments/{paymentId}/refunds` yêu cầu JWT ADMIN và header `Idempotency-Key`. Backend lấy số tiền và mã giao dịch gốc từ payment. Màn xác nhận hiển thị workshop, số tiền và chế độ sandbox trước khi gửi.
- Chấp nhận payment có số tiền dương, mã giao dịch và số tiền trùng với sổ thu mô phỏng, nếu payment ở `REFUND_REQUIRED` hoặc ở `COMPLETED` và workshop đã hủy. Lịch sử vẫn lưu check-in đã thực hiện trước lúc hủy.
- Yêu cầu được nhận trả `202` với mã refund và trạng thái. Gửi lại cùng khóa cho cùng payment trả cùng mã refund và trạng thái hiện tại. Một khóa dùng lại cho payment khác hoặc một khóa mới cho payment đã có refund trả `409`. Tài khoản thiếu quyền bị từ chối trước khi gọi bộ mô phỏng.
- `GET /api/v1/admin/payments` giữ các trường hiện có và có thể thêm trạng thái refund cùng mã tham chiếu hoàn tiền. Bộ lọc **Refund required** bỏ các khoản đã `SUCCEEDED`. Khoản `PENDING`, `PROCESSING` hoặc `FAILED` vẫn có trạng thái rõ ràng để ADMIN theo dõi.

## Lưu trạng thái và xử lý lỗi

Tạo bảng refund bằng migration Flyway mới. Lưu liên kết payment, số tiền, đơn vị tiền, khóa yêu cầu, khóa gửi provider ổn định, trạng thái, mã tham chiếu hoàn tiền, ADMIN khởi tạo và thời điểm xử lý. Đặt ràng buộc duy nhất trên payment và khóa yêu cầu. Tạo refund `PENDING` trong một giao dịch có khóa trên payment, rồi commit trước khi gọi bộ mô phỏng.

Worker lấy mục đang chờ bằng lease có thời hạn, dùng cùng khóa provider cho mọi lần tra cứu hoặc thử lại và ghi kết quả trong giao dịch riêng. Timeout sau khi gửi giữ trạng thái `PROCESSING` cho tới khi tra được kết quả. Từ chối dứt khoát chuyển sang `FAILED` để ADMIN kiểm tra. Giới hạn số lần tự thử lại và giữ mã lỗi đã chuẩn hóa để đối soát. Bộ mô phỏng có sổ hoàn tiền riêng với ràng buộc duy nhất theo giao dịch thu và khóa provider.

Refund tồn tại độc lập với payment gốc. Hủy workshop vẫn vô hiệu QR qua trạng thái đăng ký. Metrics đếm tổng refund theo trạng thái. Log dùng ID refund, ID payment và request ID để truy vết.

## Tiêu chí nghiệm thu

- Hai ADMIN gửi đồng thời hoặc gửi lại sau timeout chỉ tạo một refund và một kết quả trong sổ mô phỏng.
- Khởi động lại worker sau khi mô phỏng đã hoàn nhưng backend chưa lưu kết quả vẫn ghi đúng một refund `SUCCEEDED`.
- Payment chưa thu tiền, workshop chưa hủy trong trường hợp `COMPLETED`, khóa bị dùng sai phạm vi và người dùng thiếu quyền đều bị từ chối.
- Sau khi hoàn sandbox, payment thu tiền giữ mã tham chiếu và số tiền gốc. Hàng đợi cập nhật, QR cũ bị từ chối và số bản ghi check-in cũ được giữ.
- CI chạy test PostgreSQL có cạnh tranh, smoke test qua gateway và E2E cho ADMIN. Tạo fixture riêng để chạy lặp lại trên staging.

## Triển khai và quay lui

Flyway V17 tạo hai bảng `payment_refunds` và `mock_refund_operations`. Xác nhận sandbox mặc định tắt và chỉ hoạt động với `PAYMENT_PROVIDER=MOCK` bên ngoài profile `prod`.

- Đặt `SANDBOX_REFUNDS_ENABLED=true` để nhận yêu cầu. Dùng `SANDBOX_REFUNDS_WORKSHOP_ID=18` để giới hạn cả API tạo refund và worker vào QA #18. Giá trị mặc định `0` cho phép mọi workshop đủ điều kiện trong môi trường sandbox.
- Khi chạy bản QA riêng, đặt `SCHEDULED_MAINTENANCE_ENABLED=false`, tắt notification outbox, email, Telegram, AI worker, analytics projection và Redis keyspace listener.
- Khi quay lui, đặt `SANDBOX_REFUNDS_ENABLED=false` và giữ `SANDBOX_REFUNDS_WORKER_ENABLED=true` để xử lý các mục đã nhận. Kiểm tra các mục `PROCESSING` có mã `REVIEW_REQUIRED` với sổ mô phỏng trước khi dừng worker. Giữ hai bảng V17 để đối soát.
- Theo dõi gauge `unihub.refunds.sandbox` theo trạng thái. Mục chưa xử lý quá số lần thử cần được kiểm tra bằng ID refund và số lần thử, dựa trên log và trạng thái lưu trong cơ sở dữ liệu.
