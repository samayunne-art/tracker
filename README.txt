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
