# Brief: App Android đọc truyện web bằng TTS (tự sang chương)

## Mục tiêu
App Android cá nhân (sideload, không lên Play Store): dán URL một chương truyện trên web → app tách nội dung, đọc to bằng TTS tiếng Việt, đọc hết thì **tự tìm link "chương tiếp" và đọc tiếp liên tục**, kể cả khi tắt màn hình.

## Thiết bị mục tiêu
- HONOR 600 5G, MagicOS (Android) – ROM hay kill app chạy nền, cần xử lý kỹ.
- Máy phụ để test: Samsung Galaxy A05s.
- Cài bằng APK debug/sideload.

## Ngôn ngữ / stack
- Ngôn ngữ: **Kotlin hoặc Java – cần chốt trước khi bắt đầu** (dev chính quen Java).
- Build: Gradle, Android Studio project chuẩn.
- Thư viện dự kiến: Jsoup (parse HTML), Readability4J (fallback tách nội dung), AndroidX Media (MediaSession).

## Luồng chính
1. Người dùng dán URL chương.
2. Tải trang → lấy HTML.
3. Tách nội dung chương + tiêu đề.
4. Tìm URL chương tiếp.
5. Cắt nội dung thành các đoạn → đưa vào hàng đợi TTS.
6. Đọc xong đoạn cuối → chuyển sang chương tiếp (đã prefetch sẵn) → lặp lại.

## Yêu cầu kỹ thuật

### 1. Lấy HTML
- Dùng **WebView ẩn** load trang, rồi `evaluateJavascript("document.documentElement.outerHTML")` để lấy HTML đã render. Lý do: nhiều site dùng Cloudflare hoặc render bằng JS, Jsoup fetch trực tiếp sẽ nhận về trang trống hoặc trang chặn.
- Có thể thử Jsoup fetch trước cho nhanh, lỗi hoặc nội dung rỗng thì fallback sang WebView.

### 2. Tách nội dung
- Ưu tiên **cấu hình selector CSS theo domain** (map domain → selector nội dung, selector tiêu đề, selector nút chương tiếp).
- Không có cấu hình thì fallback sang **Readability4J**.
- Làm sạch: bỏ script/quảng cáo/dòng rác, chuẩn hóa khoảng trắng, giữ ngắt đoạn.

### 3. Tìm chương tiếp
Thứ tự ưu tiên:
1. Selector cấu hình theo domain (nếu có).
2. Quét thẻ `<a>`, khớp text với regex: `(?i)(chương\s*(tiếp|sau)|next|»|›)`; bỏ link trỏ về chính trang hiện tại hoặc `#`/`javascript:`.
3. Đoán theo URL: tìm số chương trong URL (ví dụ `chuong-123`) và tăng lên 1.
- Không tìm thấy thì dừng và báo "hết truyện / không tìm được chương tiếp".

### 4. TTS
- `android.speech.tts.TextToSpeech`, `Locale("vi", "VN")`. Kiểm tra ngôn ngữ có hỗ trợ không; nếu thiếu dữ liệu giọng thì hướng dẫn người dùng mở cài đặt TTS để tải.
- Cắt text theo câu/đoạn, mỗi đoạn < `TextToSpeech.getMaxSpeechInputLength()` (thực tế giữ khoảng 1000–2000 ký tự cho an toàn).
- `speak(..., QUEUE_ADD, params, utteranceId)`, mỗi đoạn một `utteranceId` dạng `ch{index}_p{n}`.
- `UtteranceProgressListener.onDone`: cập nhật vị trí đã đọc; nếu là đoạn cuối của chương thì chuyển sang chương mới.
- Điều chỉnh được tốc độ đọc (`setSpeechRate`) và cao độ.
- **Prefetch**: khi bắt đầu đọc một chương thì tải và parse sẵn chương tiếp theo.

### 5. Chạy nền
- TTS đặt trong **Foreground Service** (type `mediaPlayback`) có notification.
- **MediaSession**: Play/Pause/Next chapter từ notification, màn hình khóa, nút tai nghe.
- Xử lý audio focus (có cuộc gọi hoặc app khác phát nhạc thì tạm dừng).
- Có màn hình hoặc nút hướng dẫn người dùng tắt tối ưu pin cho app (MagicOS: cho phép chạy nền không giới hạn).

### 6. Lưu tiến độ
- Lưu URL chương hiện tại + index đoạn đang đọc (SharedPreferences hoặc DataStore cho bản đầu).
- Mở app thì cho chọn "Đọc tiếp" từ vị trí đã lưu.

## Phạm vi MVP (làm trước)
- [ ] 1 màn hình: ô nhập URL, nút Play/Pause, hiển thị tiêu đề chương đang đọc.
- [ ] Lấy HTML (Jsoup + fallback WebView).
- [ ] Tách nội dung (Readability4J) + tìm chương tiếp (regex + đoán URL).
- [ ] Foreground Service đọc TTS, tự chuyển chương, prefetch.
- [ ] Lưu và khôi phục tiến độ.

## Làm sau (không thuộc MVP)
- MediaSession đầy đủ + điều khiển bằng tai nghe.
- Cấu hình selector theo domain (UI hoặc file JSON).
- Thư viện truyện (Room): danh sách truyện, lịch sử.
- Hẹn giờ tắt (sleep timer).
- Hiển thị text đang đọc + highlight đoạn hiện tại.
- Tùy chọn giọng Edge neural (HoaiMy/NamMinh) qua endpoint kiểu `edge-tts`. **Lưu ý: API không chính thức, có thể bị chặn bất cứ lúc nào**, chỉ làm tùy chọn, Google TTS vẫn là mặc định.

## Rủi ro / lưu ý
- Web truyện hay đổi cấu trúc HTML, nên phần tách nội dung phải dễ sửa và dễ thêm cấu hình.
- Cloudflare challenge có thể chặn cả WebView ẩn; khi đó cần cho WebView hiển thị để người dùng tự qua challenge một lần (giữ cookie).
- MagicOS kill service mạnh tay, cần test đọc liên tục khi tắt màn hình ít nhất 30–60 phút.
- Chỉ dùng cá nhân.

## Việc cần chốt trước khi code
- Kotlin hay Java?
- minSdk (đề xuất 26+).
- Danh sách 2–3 web truyện hay đọc để test và viết selector đầu tiên.
