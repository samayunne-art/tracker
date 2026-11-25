CÁCH LẤY FILE APK (không cần Android Studio)
1. Tạo tài khoản GitHub, tạo repository mới (Private cũng được).
2. Giải nén file zip này, upload TOÀN BỘ nội dung lên repository
   (nếu không upload được thư mục ẩn .github thì tạo file mới tên
   .github/workflows/build.yml rồi dán nội dung từ build.yml.txt).
3. Vào tab Actions > "Build APK" > chờ ~3-5 phút (hoặc bấm Run workflow).
4. Mở lần chạy xong > mục Artifacts > tải "Trace-APK" > giải nén ra app-debug.apk.
5. Cài APK lên điện thoại (cho phép "cài từ nguồn không rõ").
6. Mở app > bấm "Bật Trợ năng" > Trace overlay > Bật.

Nếu dùng Android Studio: File > Open thư mục này > Build > Build APK(s).

ĐỒNG BỘ ZOOM VỚI APP KHÁC (ibisPaint...) — cần Shizuku
1. Cài Shizuku (Google Play hoặc github.com/RikkaApps/Shizuku), mở nó, làm theo hướng dẫn
   bật "Gỡ lỗi không dây" (Android 11+) rồi bấm Start. Phải Start lại sau mỗi lần khởi động máy.
2. Mở Trace > "Hiện lớp ảnh" > trên bảng nổi bấm "🔗 Đồng bộ zoom" > Cho phép trong hộp thoại Shizuku > bấm lại nút.
3. Sang ibisPaint: 1 ngón vẽ -> ảnh đứng yên. 2 ngón zoom/kéo/xoay -> ảnh đi theo.
Nếu ảnh lệch dần: bấm "✋ Chỉnh ảnh" để khớp lại rồi "✏️ Khoá để vẽ".
