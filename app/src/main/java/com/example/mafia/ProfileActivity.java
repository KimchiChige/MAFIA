package com.example.mafia;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "PROFILE";

    // Размер стороны аватара в пикселях. 150x150 px при JPEG 80% = ~10-15 КБ,
    // что отлично умещается в Firestore (лимит документа 1 МБ).
    private static final int AVATAR_SIZE = 150;
    private static final int JPEG_QUALITY = 80;

    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private ImageView avatarImage;
    private TextView changePhotoText;
    private TextInputEditText nicknameEditText;
    private TextInputEditText emailEditText;
    private MaterialButton saveButton;
    private LinearLayout uploadProgressLayout;
    private ProgressBar uploadProgressBar;
    private TextView uploadProgressText;

    // Сжатое фото в Base64 — готово к сохранению в Firestore
    private String pendingPhotoBase64 = null;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) compressAndPreview(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        db = FirebaseFirestore.getInstance();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) { finish(); return; }

        initViews();
        loadUserData();
    }

    private void initViews() {
        avatarImage        = findViewById(R.id.avatarImage);
        changePhotoText    = findViewById(R.id.changePhotoText);
        nicknameEditText   = findViewById(R.id.nicknameEditText);
        emailEditText      = findViewById(R.id.emailEditText);
        saveButton         = findViewById(R.id.saveButton);
        uploadProgressLayout = findViewById(R.id.uploadProgressLayout);
        uploadProgressBar  = findViewById(R.id.uploadProgressBar);
        uploadProgressText = findViewById(R.id.uploadProgressText);

        avatarImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        changePhotoText.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        saveButton.setOnClickListener(v -> saveProfile());
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
    }

    private void loadUserData() {
        emailEditText.setText(currentUser.getEmail());

        db.collection("users").document(currentUser.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;

                    String nickname = doc.getString("nickname");
                    if (nickname != null) nicknameEditText.setText(nickname);

                    // Загружаем фото из Base64 — просто декодируем и отдаём Glide
                    String photoBase64 = doc.getString("photoBase64");
                    if (photoBase64 != null && !photoBase64.isEmpty()) {
                        showBase64Avatar(photoBase64);
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка загрузки профиля", e));
    }

    // ── Сжатие фото в фоновом потоке ─────────────────────────────────────────

    private void compressAndPreview(Uri uri) {
        uploadProgressLayout.setVisibility(View.VISIBLE);
        uploadProgressBar.setIndeterminate(true);
        uploadProgressText.setText("Обработка фото...");
        saveButton.setEnabled(false);

        executor.execute(() -> {
            try {
                String base64 = compressToBase64(uri);
                mainHandler.post(() -> {
                    pendingPhotoBase64 = base64;
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    // Показываем превью сразу из Base64
                    showBase64Avatar(base64);
                });
            } catch (Exception e) {
                Log.e(TAG, "Ошибка сжатия фото", e);
                mainHandler.post(() -> {
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "Не удалось обработать фото", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String compressToBase64(Uri uri) throws Exception {
        // 1. Читаем оригинал
        InputStream is = getContentResolver().openInputStream(uri);
        Bitmap original = BitmapFactory.decodeStream(is);
        if (is != null) is.close();
        if (original == null) throw new Exception("Не удалось декодировать изображение");

        // 2. Масштабируем до AVATAR_SIZE x AVATAR_SIZE с сохранением пропорций
        int w = original.getWidth();
        int h = original.getHeight();
        float scale = (float) AVATAR_SIZE / Math.min(w, h);
        int newW = Math.round(w * scale);
        int newH = Math.round(h * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(original, newW, newH, true);
        original.recycle();

        // 3. Обрезаем по центру до квадрата AVATAR_SIZE x AVATAR_SIZE
        int cropX = (newW - AVATAR_SIZE) / 2;
        int cropY = (newH - AVATAR_SIZE) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, cropX, cropY, AVATAR_SIZE, AVATAR_SIZE);
        scaled.recycle();

        // 4. Сжимаем в JPEG и кодируем в Base64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        cropped.recycle();

        byte[] bytes = baos.toByteArray();
        Log.d(TAG, "Размер сжатого фото: " + bytes.length / 1024 + " КБ");
        return Base64.encodeToString(bytes, Base64.DEFAULT);
    }

    private void showBase64Avatar(String base64) {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        avatarImage.clearColorFilter();
        Glide.with(this)
                .load(bytes)
                .transform(new CircleCrop())
                .placeholder(R.drawable.ic_player_avatar1)
                .into(avatarImage);
    }

    // ── Сохранение профиля ────────────────────────────────────────────────────

    private void saveProfile() {
        String nickname = nicknameEditText.getText() != null
                ? nicknameEditText.getText().toString().trim() : "";

        if (nickname.length() < 3) {
            nicknameEditText.setError("Минимум 3 символа");
            return;
        }

        saveButton.setEnabled(false);
        uploadProgressLayout.setVisibility(View.VISIBLE);
        uploadProgressBar.setIndeterminate(true);
        uploadProgressText.setText("Сохранение...");

        Map<String, Object> updates = new HashMap<>();
        updates.put("nickname", nickname);
        if (pendingPhotoBase64 != null) {
            updates.put("photoBase64", pendingPhotoBase64);
        }

        db.collection("users").document(currentUser.getUid())
                .update(updates)
                .addOnSuccessListener(v -> {
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    pendingPhotoBase64 = null;
                    Toast.makeText(this, "Профиль сохранён ✓", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .addOnFailureListener(e -> {
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Ошибка сохранения профиля", e);
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}