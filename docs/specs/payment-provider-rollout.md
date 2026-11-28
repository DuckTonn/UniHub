# Thanh toán qua nhà cung cấp

**Trạng thái đề xuất.** UniHub hiện dùng provider mô phỏng trong môi trường thử nghiệm. Chọn nhà cung cấp, chính sách hoàn tiền và thời điểm bật giao dịch thực tế trước khi bắt đầu mốc này.

## Thiết kế đề xuất

- Duy trì cấu hình riêng cho mô phỏng, sandbox của nhà cung cấp và production. Tách khóa, tài khoản và địa chỉ webhook theo môi trường. Chỉ bật adapter thực khi cấu hình đầy đủ và có quyết định phát hành.
- Giữ quyền sở hữu đăng ký, số tiền và mã workshop ở backend. Với provider cần người dùng xác nhận, dùng trang thanh toán do nhà cung cấp lưu trữ. Giao diện nhận mã phiên hoặc địa chỉ thanh toán cùng trạng thái để hiển thị tiến độ.
- Chuyển hướng về UniHub giúp người dùng quay lại màn hình đăng ký. Backend xác nhận thanh toán thông qua kết quả đã xác minh từ webhook hoặc truy vấn provider. Trước khi cấp QR, đối chiếu mã giao dịch, đơn vị tiền, số tiền và đăng ký.
- Xác minh chữ ký webhook theo tài liệu của provider. Lưu ID sự kiện duy nhất và xử lý lặp lại hoặc đến sai thứ tự dựa trên trạng thái hiện tại. Worker đối soát các khoản chưa có kết quả và đẩy các trường hợp không khớp vào hàng đợi ADMIN.
- Tích hợp xác nhận hoàn tiền sau khi [spec hoàn tiền sandbox](sandbox-refunds.md) đã được nghiệm thu. Ghi nhận kết quả provider trên bản ghi refund và hiển thị xác nhận cho ADMIN.

## Tương thích và xử lý sự cố

Ghi hợp đồng checkout bổ sung trước khi sửa client. Kiểm tra các luồng giữ chỗ, QR, đồng bộ Android và thanh toán mô phỏng hiện có. Khi hold hết hạn trước khi nhận kết quả, xử lý theo hợp đồng Phase 4. Nếu tiền đã được thu mà đăng ký đã hủy, chuyển vào hàng đợi hoàn tiền.

Nếu provider lỗi, giữ mã giao dịch và khóa idempotency đang xử lý. Khi quay lui, tạm dừng mở phiên mới, giữ dữ liệu giao dịch và tra các kết quả chưa xác định trước khi cho phép gửi lại. Quản trị viên cần thấy mã yêu cầu và mã tham chiếu đã rút gọn để đối soát mà không cần đọc payload webhook hay thông tin thẻ.

## Tiêu chí nghiệm thu

- Sandbox kiểm chứng thanh toán thành công, từ chối, timeout, webhook lặp hoặc đến sai thứ tự, số tiền không khớp và kết quả đến sau khi hold hết hạn.
- Với mỗi tình huống, chỉ cấp QR khi xác nhận thanh toán thành công và đăng ký còn hợp lệ. Một lượt thanh toán hoặc hoàn tiền lặp lại giữ nguyên giao dịch theo khóa tương ứng.
- Staging thử mất kết nối provider và quay lui trong lúc còn giao dịch đang xử lý. So sánh báo cáo UniHub với sổ sandbox, nêu rõ khoản chênh lệch và trạng thái cuối.
- Chỉ mở production sau khi chủ hệ thống duyệt quy trình đối soát, quyền vận hành, luồng hoàn tiền và bằng chứng diễn tập. Sandbox dùng khóa và dữ liệu thử riêng.
