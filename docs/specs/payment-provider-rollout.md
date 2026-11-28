# Thanh toán qua Stripe Checkout sandbox

UniHub hỗ trợ Stripe Checkout ở chế độ test cho thanh toán workshop bằng thẻ. Cấu hình mặc định dùng bộ mô phỏng `MOCK`. Adapter Stripe chỉ nhận khóa test, kiểm tra `livemode=false` và không nạp trong profile `prod`.

## Checkout và xác nhận

- Sinh viên giữ chỗ như trước. `POST /api/v1/workshops/{id}/pay` dùng JWT và `Idempotency-Key` cấp khi đăng ký. Backend kiểm tra chủ đăng ký, workshop và số tiền VND. Flyway V18 lưu UUID của lần checkout và ràng buộc mỗi payment có tối đa một lần checkout. Backend dùng UUID này làm tham chiếu và khóa idempotency phía Stripe. [Stripe Checkout Sessions](https://docs.stripe.com/api/checkout/sessions/create) · [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests)
- Với Stripe, API trả `202` cùng `checkoutStatus`. `OPEN` kèm `checkoutUrl` trên `https://checkout.stripe.com`. `VERIFYING`, `EXPIRED` và `REVIEW_REQUIRED` không kèm URL. Sau khi xác nhận thành công, API tiếp tục trả `200` với mã giao dịch và QR như luồng `MOCK`. API đăng ký, lịch sử và check-in Android giữ hợp đồng hiện có.
- Trang checkout quay về `/registration-history`. React dùng tham số URL để hiển thị hướng dẫn và đọc lại lịch sử. Chuyển hướng của trình duyệt không thay đổi trạng thái payment.
- `POST /api/v1/payment-webhooks/stripe` kiểm tra chữ ký trên byte gốc và cửa sổ 5 phút. Backend truy vấn Checkout Session trực tiếp từ Stripe và so khớp chế độ test, ID phiên, tham chiếu nội bộ, loại `payment`, số tiền, đơn vị tiền và PaymentIntent trước khi hoàn tất. ID sự kiện được lưu duy nhất trong cùng giao dịch với kết quả, để lần gửi lại an toàn. Webhook đến sai thứ tự vẫn đọc trạng thái hiện tại của phiên. [Stripe webhook và chữ ký](https://docs.stripe.com/webhooks)

## Đối soát và giữ chỗ

- Worker truy vấn các phiên đang chờ, thử lại sau khi mất kết nối và xác nhận phiên hết hạn để đóng giao dịch chưa thu. Stripe mở checkout trong khoảng thời gian riêng. UniHub vẫn dùng hold 10 phút và yêu cầu Stripe đóng phiên còn mở sau khi hold hết hạn. [Expire a Checkout Session](https://docs.stripe.com/api/checkout/sessions/expire)
- Nếu Stripe xác nhận đã thu sau khi hold hết hạn hoặc đăng ký bị hủy, payment chuyển `REFUND_REQUIRED` và đăng ký không được cấp QR. Xác nhận đến trễ được xử lý thận trọng theo trạng thái hold tại lúc UniHub xác minh.
- Nếu yêu cầu mở phiên mất phản hồi, sinh viên có thể thử lại với khóa cũ trong thời gian giữ chỗ. Sau đó worker giải phóng chỗ, đưa phiên mất tham chiếu vào `SESSION_UNKNOWN` và chờ webhook đã xác minh hoặc ADMIN đối chiếu trong Stripe. Khoản sai lệch xuất hiện với mã `PROVIDER_MISMATCH`. Phiên hoàn tất chưa có bằng chứng đã thu chuyển sang `PAYMENT_UNCONFIRMED`. Màn **Payment reconciliation** hiển thị nhà cung cấp, trạng thái và phần cuối mã phiên.
- [Hoàn tiền sandbox nội bộ](sandbox-refunds.md) tiếp tục dành cho giao dịch `MOCK`. Giao dịch Stripe cần được đối chiếu và xử lý trong Stripe Dashboard ở chế độ test. Adapter xác nhận hoàn trực tiếp với Stripe cần triển khai riêng trước khi mở production.

## Chạy trên staging cô lập

1. Dùng PostgreSQL staging riêng và bật Flyway V18. Đặt `PAYMENT_PROVIDER=STRIPE_SANDBOX` và `STRIPE_CHECKOUT_ENABLED=true`. Truyền `STRIPE_TEST_API_KEY` và `STRIPE_TEST_WEBHOOK_SECRET` qua cấu hình riêng. `FRONTEND_URL` phải là HTTPS của staging hoặc HTTP loopback khi chạy local.
2. Đăng ký webhook trong Stripe sandbox hoặc forward qua Stripe CLI tới `/api/v1/payment-webhooks/stripe`. Bật các sự kiện `checkout.session.completed`, `checkout.session.expired`, `checkout.session.async_payment_succeeded` và `checkout.session.async_payment_failed`. Dùng secret của đúng endpoint hoặc phiên Stripe CLI. [Stripe CLI](https://docs.stripe.com/stripe-cli/use-cli)
3. Tạo workshop và tài khoản QA. Kiểm tra thẻ test thành công và bị từ chối, hủy checkout, gửi webhook lặp và đối chiếu tham chiếu Stripe với màn ADMIN. Dùng cùng khóa đăng ký khi thử lại. Ghi bằng chứng QR chỉ xuất hiện sau xác minh. [Thẻ và tình huống kiểm thử Stripe](https://docs.stripe.com/testing)
4. Diễn tập gián đoạn kết nối và rollback khi có khoản đang chờ. Test tự động dùng máy chủ HTTP giả lập Stripe, PostgreSQL và Playwright. Cần chạy thêm quy trình với tài khoản Stripe sandbox để nghiệm thu tích hợp ngoài UniHub.

## Quay lui

Tắt `STRIPE_CHECKOUT_ENABLED` để dừng mở checkout, giữ `PAYMENT_PROVIDER=STRIPE_SANDBOX`, webhook và worker cho tới khi đối soát xong các phiên đang chờ. So sánh khoản đã thu với Stripe Dashboard và kiểm tra từng khoản có cảnh báo hoặc `REFUND_REQUIRED` trước khi đổi sang `MOCK` hoặc quay lui image. Giữ các bảng V18 và nhật ký sự kiện. Production cần nghiệm thu tích hợp thật, quy trình hoàn tiền và phê duyệt phát hành riêng.
