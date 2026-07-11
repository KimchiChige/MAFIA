package com.example.mafia;

import android.content.Intent;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "PROFILE";
    private static final int AVATAR_SIZE  = 150;
    private static final int JPEG_QUALITY = 80;
    private static final int REQ_CUSTOMIZE = 5001;

    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private ListenerRegistration userListener;

    private ImageView avatarImage;
    private TextView changePhotoText;
    private TextInputEditText nicknameEditText, emailEditText;
    private MaterialButton saveButton;
    private LinearLayout uploadProgressLayout;
    private ProgressBar uploadProgressBar;
    private TextView uploadProgressText;

    // Алмазы
    private TextView diamondsText;

    // Плюшки
    private TextView shieldCountText, selfhealCountText, invisibleCountText;

    // Premium блок
    private LinearLayout premiumBadgeLayout;   // планка "PREMIUM" в шапке
    private Button customizeButton;            // "Кастомизировать аккаунт"
    private Button buyPremiumButton;           // "Купить Premium — 99 ₽"
    private TextView premiumStatusText;
    private boolean currentIsPremium = false;

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
        startUserListener();

        // Ежедневный бонус Premium — проверяем при открытии профиля
        checkAndClaimDailyBonus();
    }

    private void initViews() {
        avatarImage          = findViewById(R.id.avatarImage);
        changePhotoText      = findViewById(R.id.changePhotoText);
        nicknameEditText     = findViewById(R.id.nicknameEditText);
        emailEditText        = findViewById(R.id.emailEditText);
        saveButton           = findViewById(R.id.saveButton);
        uploadProgressLayout = findViewById(R.id.uploadProgressLayout);
        uploadProgressBar    = findViewById(R.id.uploadProgressBar);
        uploadProgressText   = findViewById(R.id.uploadProgressText);
        diamondsText         = findViewById(R.id.profileDiamondsText);

        shieldCountText    = findViewById(R.id.profileShieldCount);
        selfhealCountText  = findViewById(R.id.profileSelfhealCount);
        invisibleCountText = findViewById(R.id.profileInvisibleCount);

        // Premium UI
        premiumBadgeLayout = findViewById(R.id.premiumBadgeLayout);
        premiumStatusText  = findViewById(R.id.premiumStatusText);
        customizeButton    = findViewById(R.id.customizeButton);

        avatarImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        changePhotoText.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        saveButton.setOnClickListener(v -> saveProfile());
        emailEditText.setText(currentUser.getEmail());

        Button shopBtn = findViewById(R.id.profileShopButton);
        if (shopBtn != null) shopBtn.setOnClickListener(v ->
                startActivity(new Intent(this, ShopActivity.class)));

        View backBtn = findViewById(R.id.backButton);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        if (customizeButton != null) {
            customizeButton.setOnClickListener(v -> {
                if (currentIsPremium) {
                    startActivityForResult(
                            new Intent(this, PremiumCustomizeActivity.class), REQ_CUSTOMIZE);
                } else {
                    showBuyPremiumDialog();
                }
            });
        }

        if (buyPremiumButton != null) {
            buyPremiumButton.setOnClickListener(v -> showBuyPremiumDialog());
        }
    }

    private void startUserListener() {
        userListener = db.collection("users").document(currentUser.getUid())
                .addSnapshotListener((doc, e) -> {
                    if (doc == null || !doc.exists()) return;

                    String nickname = doc.getString("nickname");
                    if (nickname != null && nicknameEditText.getText() != null
                            && nicknameEditText.getText().toString().isEmpty()) {
                        nicknameEditText.setText(nickname);
                    }

                    String photoBase64 = doc.getString("photoBase64");
                    if (photoBase64 != null && !photoBase64.isEmpty()) showBase64Avatar(photoBase64);

                    long diamonds = doc.getLong("diamonds") != null ? doc.getLong("diamonds") : 0L;
                    if (diamondsText != null) diamondsText.setText("💎 " + diamonds);

                    long shield    = doc.getLong("perk_shield")    != null ? doc.getLong("perk_shield")    : 0L;
                    long selfheal  = doc.getLong("perk_selfheal")  != null ? doc.getLong("perk_selfheal")  : 0L;
                    long invisible = doc.getLong("perk_invisible") != null ? doc.getLong("perk_invisible") : 0L;

                    if (shieldCountText    != null) shieldCountText.setText("x" + shield);
                    if (selfhealCountText  != null) selfhealCountText.setText("x" + selfheal);
                    if (invisibleCountText != null) invisibleCountText.setText("x" + invisible);

                    // Premium статус
                    currentIsPremium = Boolean.TRUE.equals(doc.getBoolean("isPremium"));
                    updatePremiumUI(currentIsPremium);
                });
    }

    private void updatePremiumUI(boolean isPremium) {
        if (premiumBadgeLayout != null) {
            premiumBadgeLayout.setVisibility(isPremium ? View.VISIBLE : View.GONE);
        }
        if (premiumStatusText != null) {
            if (isPremium) {
                premiumStatusText.setText("👑 PREMIUM АККАУНТ");
                premiumStatusText.setTextColor(0xFFFFD700);
            }
        }
        if (customizeButton != null) {
            customizeButton.setVisibility(View.VISIBLE);
            if (isPremium) {
                customizeButton.setText("🎨 Кастомизировать аккаунт");
                customizeButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF4A0080));
            } else {
                customizeButton.setText("🎨 Кастомизировать аккаунт");
                customizeButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF333333));
            }
        }
        if (buyPremiumButton != null) {
            buyPremiumButton.setVisibility(isPremium ? View.GONE : View.VISIBLE);
        }
    }

    /** Проверяет и начисляет ежедневный Premium-бонус. */
    private void checkAndClaimDailyBonus() {
        PremiumManager.claimDailyBonus(db, currentUser.getUid(),
                new PremiumManager.OnDailyClaimResult() {
                    @Override public void onClaimed(int diamonds) {
                        Toast.makeText(ProfileActivity.this,
                                "👑 Premium бонус: +" + diamonds + " 💎 алмазов!",
                                Toast.LENGTH_LONG).show();
                    }
                    @Override public void onAlreadyClaimed() {
                        // Тихо — бонус уже был сегодня
                    }
                    @Override public void onNotPremium() { /* не Premium — ничего */ }
                    @Override public void onError(String msg) {
                        Log.e(TAG, "Ошибка ежедневного бонуса: " + msg);
                    }
                });
    }

    /** Красивый диалог покупки Premium с красно-чёрным градиентным фоном. */
    private void showBuyPremiumDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_buy_premium);

        // Прозрачный фон окна диалога — чтобы были видны скруглённые углы
        // и красно-чёрный градиент из dialog_buy_premium.xml без белой рамки Android
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        Button confirmBtn = dialog.findViewById(R.id.buyPremiumConfirmButton);
        Button cancelBtn  = dialog.findViewById(R.id.buyPremiumCancelButton);

        if (confirmBtn != null) {
            confirmBtn.setOnClickListener(v -> {
                // Заглушка — здесь будет подключение к платёжной системе
                Toast.makeText(this, "Оплата пока недоступна. Скоро!", Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        }
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    // ── Фото ─────────────────────────────────────────────────────────────────

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
                    showBase64Avatar(base64);
                });
            } catch (Exception ex) {
                Log.e(TAG, "Ошибка сжатия фото", ex);
                mainHandler.post(() -> {
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "Не удалось обработать фото", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String compressToBase64(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        Bitmap original = BitmapFactory.decodeStream(is);
        if (is != null) is.close();
        if (original == null) throw new Exception("Не удалось декодировать изображение");

        int w = original.getWidth(), h = original.getHeight();
        float scale = (float) AVATAR_SIZE / Math.min(w, h);
        int newW = Math.round(w * scale), newH = Math.round(h * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(original, newW, newH, true);
        original.recycle();

        int cropX = (newW - AVATAR_SIZE) / 2, cropY = (newH - AVATAR_SIZE) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, cropX, cropY, AVATAR_SIZE, AVATAR_SIZE);
        scaled.recycle();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        cropped.recycle();
        return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
    }

    private void showBase64Avatar(String base64) {
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        avatarImage.clearColorFilter();
        Glide.with(this).load(bytes).transform(new CircleCrop())
                .placeholder(R.drawable.ic_player_avatar1).into(avatarImage);
    }

    // ── Сохранение ───────────────────────────────────────────────────────────

    private void saveProfile() {
        String nickname = nicknameEditText.getText() != null
                ? nicknameEditText.getText().toString().trim() : "";
        if (nickname.length() < 3) { nicknameEditText.setError("Минимум 3 символа"); return; }

        saveButton.setEnabled(false);
        uploadProgressLayout.setVisibility(View.VISIBLE);
        uploadProgressBar.setIndeterminate(true);
        uploadProgressText.setText("Сохранение...");

        Map<String, Object> updates = new HashMap<>();
        updates.put("nickname", nickname);
        if (pendingPhotoBase64 != null) updates.put("photoBase64", pendingPhotoBase64);

        db.collection("users").document(currentUser.getUid()).update(updates)
                .addOnSuccessListener(v -> {
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    pendingPhotoBase64 = null;
                    Toast.makeText(this, "Профиль сохранён ✓", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .addOnFailureListener(ex -> {
                    uploadProgressLayout.setVisibility(View.GONE);
                    saveButton.setEnabled(true);
                    Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CUSTOMIZE && resultCode == RESULT_OK) {
            Toast.makeText(this, "✨ Кастомизация обновлена!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (userListener != null) userListener.remove();
    }
}