package com.example.mafia;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Экран кастомизации Premium карточки.
 *
 * Поля в Firestore (users/{uid}):
 *   avatarBadge: String       — любой эмодзи, введённый пользователем
 *   cardBorderColor: String   — hex, выбран через ColorWheelView
 *   nicknameColor: String     — hex, выбран через ColorWheelView
 *   cardBackground: String    — hex ИЛИ "img:<base64>" (чёрно-белое фото)
 *   cardBgOpacity: Long       — 0..100, прозрачность фото-фона (только для img:)
 */
public class PremiumCustomizeActivity extends AppCompatActivity {

    private static final int BG_IMAGE_SIZE   = 220;
    private static final int BG_JPEG_QUALITY = 78;

    private FirebaseFirestore db;
    private String uid;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Текущие выбранные значения
    private String selectedBorderColor = "#8B0000";
    private String selectedNickColor   = "#FFFFFF";
    private String selectedBadgeEmoji  = "👑";
    private String selectedCardBg      = "#1A1A1A";   // hex ИЛИ "img:<base64>"
    private int    selectedBgOpacity   = 60;           // 0..100, только для фото

    // Preview
    private FrameLayout previewCard;
    private ImageView   previewAvatar;
    private TextView    previewBadge;
    private TextView    previewNickname;
    private ImageView   previewBgImage;
    private View        previewBorderOverlay;

    // Color wheels
    private ColorWheelView borderColorWheel;
    private ColorWheelView nickColorWheel;
    private ColorWheelView bgColorWheel;

    // Бейдж
    private EditText badgeInput;

    // Фон: секции + прозрачность
    private LinearLayout bgColorSection;
    private LinearLayout bgImageSection;
    private SeekBar      bgOpacitySeek;
    private TextView     bgOpacityLabel;
    private Button       pickBgImageButton;

    private final ActivityResultLauncher<String> pickBgImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) processBgImage(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_premium_customize);

        db  = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        initViews();
        loadCurrentSettings();
    }

    private void initViews() {
        previewCard          = findViewById(R.id.previewCard);
        previewAvatar        = findViewById(R.id.previewAvatar);
        previewBadge          = findViewById(R.id.previewBadge);
        previewNickname       = findViewById(R.id.previewNickname);
        previewBgImage        = findViewById(R.id.previewBgImage);
        previewBorderOverlay  = findViewById(R.id.previewBorderOverlay);

        // ── Круглая палитра: РАМКА ─────────────────────────────────────────
        borderColorWheel = findViewById(R.id.borderColorWheel);
        if (borderColorWheel != null) {
            borderColorWheel.setColor(selectedBorderColor);
            borderColorWheel.setOnColorChangeListener(hex -> {
                selectedBorderColor = hex;
                updatePreview();
            });
        }

        // ── Круглая палитра: НИК ────────────────────────────────────────────
        nickColorWheel = findViewById(R.id.nickColorWheel);
        if (nickColorWheel != null) {
            nickColorWheel.setColor(selectedNickColor);
            nickColorWheel.setOnColorChangeListener(hex -> {
                selectedNickColor = hex;
                updatePreview();
            });
        }

        // ── Круглая палитра: ФОН (цветной режим) ────────────────────────────
        bgColorWheel = findViewById(R.id.bgColorWheel);
        if (bgColorWheel != null) {
            bgColorWheel.setOnColorChangeListener(hex -> {
                selectedCardBg = hex;
                updatePreview();
            });
        }

        // ── Бейдж: ввод эмодзи ────────────────────────────────────────────
        badgeInput = findViewById(R.id.badgeEmojiInput);
        if (badgeInput != null) {
            badgeInput.setText(selectedBadgeEmoji);
            badgeInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    String text = s.toString().trim();
                    if (!text.isEmpty()) {
                        selectedBadgeEmoji = getFirstEmoji(text);
                    } else {
                        // Поле очищено пользователем — бейдж убирается с карточки
                        selectedBadgeEmoji = "";
                    }
                    updatePreview();
                }
            });
            badgeInput.setOnClickListener(v -> {
                badgeInput.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(badgeInput, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        // ── Фон карточки: переключатель цвет / фото ──────────────────────────
        bgColorSection    = findViewById(R.id.bgColorSection);
        bgImageSection    = findViewById(R.id.bgImageSection);
        bgOpacitySeek     = findViewById(R.id.bgOpacitySeek);
        bgOpacityLabel    = findViewById(R.id.bgOpacityLabel);
        pickBgImageButton = findViewById(R.id.pickBgImageButton);

        Button btnBgColor = findViewById(R.id.btnBgModeColor);
        Button btnBgImage = findViewById(R.id.btnBgModeImage);
        if (btnBgColor != null) btnBgColor.setOnClickListener(v -> {
            if (bgColorSection != null) bgColorSection.setVisibility(View.VISIBLE);
            if (bgImageSection != null) bgImageSection.setVisibility(View.GONE);
            if (selectedCardBg.startsWith("img:")) {
                selectedCardBg = bgColorWheel != null ? bgColorWheel.getHexColor() : "#1A1A1A";
            }
            updatePreview();
        });
        if (btnBgImage != null) btnBgImage.setOnClickListener(v -> {
            if (bgColorSection != null) bgColorSection.setVisibility(View.GONE);
            if (bgImageSection != null) bgImageSection.setVisibility(View.VISIBLE);
        });

        if (pickBgImageButton != null) {
            pickBgImageButton.setOnClickListener(v -> pickBgImage.launch("image/*"));
        }

        // Ползунок прозрачности фото-фона
        if (bgOpacitySeek != null) {
            bgOpacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    selectedBgOpacity = progress;
                    if (bgOpacityLabel != null) bgOpacityLabel.setText("Прозрачность: " + progress + "%");
                    applyBgOpacityToPreview();
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Сбросить фон
        Button clearBgBtn = findViewById(R.id.clearBgButton);
        if (clearBgBtn != null) clearBgBtn.setOnClickListener(v -> {
            selectedCardBg = "#1A1A1A";
            selectedBgOpacity = 60;
            if (bgColorSection != null) bgColorSection.setVisibility(View.VISIBLE);
            if (bgImageSection != null) bgImageSection.setVisibility(View.GONE);
            if (bgColorWheel != null) bgColorWheel.setColor("#1A1A1A");
            if (bgOpacitySeek != null) bgOpacitySeek.setProgress(60);
            if (pickBgImageButton != null) pickBgImageButton.setText("📁 Выбрать фото из галереи");
            updatePreview();
        });

        // Назад / Сохранить
        View backBtn = findViewById(R.id.backButton);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        Button saveBtn = findViewById(R.id.saveCustomizeButton);
        if (saveBtn != null) saveBtn.setOnClickListener(v -> saveSettings());
    }

    private void loadCurrentSettings() {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) { updatePreview(); return; }

                    String border = doc.getString("cardBorderColor");
                    String nick   = doc.getString("nicknameColor");
                    String badge  = doc.getString("avatarBadge");
                    String bg     = doc.getString("cardBackground");
                    Long   opacity = doc.getLong("cardBgOpacity");

                    if (border != null && border.startsWith("#")) {
                        selectedBorderColor = border;
                        if (borderColorWheel != null) borderColorWheel.setColor(border);
                    }
                    if (nick != null && nick.startsWith("#")) {
                        selectedNickColor = nick;
                        if (nickColorWheel != null) nickColorWheel.setColor(nick);
                    }
                    if (badge != null) {
                        selectedBadgeEmoji = badge.isEmpty() ? "" : normalizeLegacyBadge(badge);
                        if (badgeInput != null) badgeInput.setText(selectedBadgeEmoji);
                    }
                    if (opacity != null) {
                        selectedBgOpacity = (int) (long) opacity;
                        if (bgOpacitySeek != null) bgOpacitySeek.setProgress(selectedBgOpacity);
                        if (bgOpacityLabel != null) bgOpacityLabel.setText("Прозрачность: " + selectedBgOpacity + "%");
                    }
                    if (bg != null && !bg.isEmpty()) {
                        selectedCardBg = bg;
                        if (bg.startsWith("img:")) {
                            if (bgColorSection != null) bgColorSection.setVisibility(View.GONE);
                            if (bgImageSection != null) bgImageSection.setVisibility(View.VISIBLE);
                            if (pickBgImageButton != null) pickBgImageButton.setText("✅ Фото выбрано (нажмите, чтобы заменить)");
                        } else if (bg.startsWith("#")) {
                            if (bgColorWheel != null) bgColorWheel.setColor(bg);
                        }
                    }

                    String nickname = doc.getString("nickname");
                    if (previewNickname != null && nickname != null) previewNickname.setText(nickname);

                    String photo = doc.getString("photoBase64");
                    if (photo != null && !photo.isEmpty() && previewAvatar != null) {
                        try {
                            byte[] bytes = Base64.decode(photo, Base64.DEFAULT);
                            Glide.with(this).load(bytes).transform(new CircleCrop()).into(previewAvatar);
                        } catch (Exception ignored) {}
                    }

                    updatePreview();
                });
    }

    private void updatePreview() {
        // Рамка (тонкий бордер через GradientDrawable у отдельного View-оверлея)
        if (previewBorderOverlay != null) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(Color.TRANSPARENT);
            gd.setCornerRadius(0f);
            try { gd.setStroke(6, Color.parseColor(selectedBorderColor)); }
            catch (Exception e) { gd.setStroke(6, Color.RED); }
            previewBorderOverlay.setBackground(gd);
        }

        // Фон карточки
        if (selectedCardBg.startsWith("img:")) {
            if (previewCard != null) previewCard.setBackgroundColor(0xFF1A1A1A);
            if (previewBgImage != null) {
                previewBgImage.setVisibility(View.VISIBLE);
                try {
                    byte[] bytes = Base64.decode(selectedCardBg.substring(4), Base64.DEFAULT);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) previewBgImage.setImageBitmap(bmp);
                } catch (Exception ignored) {}
                applyBgOpacityToPreview();
            }
        } else {
            if (previewBgImage != null) previewBgImage.setVisibility(View.GONE);
            if (previewCard != null) {
                try { previewCard.setBackgroundColor(Color.parseColor(selectedCardBg)); }
                catch (Exception e) { previewCard.setBackgroundColor(0xFF1A1A1A); }
            }
        }

        // Цвет ника
        if (previewNickname != null) {
            try { previewNickname.setTextColor(Color.parseColor(selectedNickColor)); }
            catch (Exception e) { previewNickname.setTextColor(Color.WHITE); }
        }

        // Бейдж — скрываем полностью, если поле оставлено пустым
        if (previewBadge != null) {
            if (selectedBadgeEmoji != null && !selectedBadgeEmoji.isEmpty()) {
                previewBadge.setText(selectedBadgeEmoji);
                previewBadge.setVisibility(View.VISIBLE);
            } else {
                previewBadge.setVisibility(View.GONE);
            }
        }
    }

    private void applyBgOpacityToPreview() {
        if (previewBgImage != null && previewBgImage.getVisibility() == View.VISIBLE) {
            previewBgImage.setAlpha(selectedBgOpacity / 100f);
        }
    }

    private void processBgImage(Uri uri) {
        if (pickBgImageButton != null) pickBgImageButton.setEnabled(false);

        executor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap original = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                if (original == null) throw new Exception("decode failed");

                // 1. Обрезаем и масштабируем строго до квадрата BG_IMAGE_SIZE×BG_IMAGE_SIZE,
                //    заполняя весь размер (аналог centerCrop) — чтобы фон точно покрывал карточку
                Bitmap squared = cropAndScaleToSquare(original, BG_IMAGE_SIZE);
                original.recycle();

                // 2. Конвертируем в чёрно-белое (grayscale)
                Bitmap grayscale = toGrayscale(squared);
                squared.recycle();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                grayscale.compress(Bitmap.CompressFormat.JPEG, BG_JPEG_QUALITY, baos);
                grayscale.recycle();

                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
                selectedCardBg = "img:" + b64;

                runOnUiThread(() -> {
                    if (pickBgImageButton != null) {
                        pickBgImageButton.setEnabled(true);
                        pickBgImageButton.setText("✅ Фото выбрано (нажмите, чтобы заменить)");
                    }
                    updatePreview();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (pickBgImageButton != null) pickBgImageButton.setEnabled(true);
                    Toast.makeText(this, "Ошибка загрузки фото", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Обрезает изображение по центру до квадрата и масштабирует к targetSize×targetSize. */
    private Bitmap cropAndScaleToSquare(Bitmap src, int targetSize) {
        int w = src.getWidth(), h = src.getHeight();
        int cropSize = Math.min(w, h);
        int x = (w - cropSize) / 2;
        int y = (h - cropSize) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropSize, cropSize);
        if (cropped == src) return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
        cropped.recycle();
        return scaled;
    }

    /** Конвертирует Bitmap в чёрно-белое (grayscale) изображение. */
    private Bitmap toGrayscale(Bitmap src) {
        Bitmap result = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);   // 0 = полностью ч/б
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        canvas.drawBitmap(src, 0, 0, paint);
        return result;
    }

    private void saveSettings() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("cardBorderColor", selectedBorderColor);
        updates.put("nicknameColor",   selectedNickColor);
        updates.put("avatarBadge",     selectedBadgeEmoji);
        updates.put("cardBackground",  selectedCardBg);
        updates.put("cardBgOpacity",   selectedCardBg.startsWith("img:") ? selectedBgOpacity : 100);

        db.collection("users").document(uid).update(updates)
                .addOnSuccessListener(v -> {
                    Toast.makeText(this, "✅ Кастомизация сохранена!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show());
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private String getFirstEmoji(String text) {
        if (text == null || text.isEmpty()) return "";
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 0) return "";
        return new String(codePoints, 0, 1);
    }

    /** Поддержка старых значений (до перехода на свободный ввод эмодзи). */
    private String normalizeLegacyBadge(String badge) {
        switch (badge) {
            case "crown":   return "👑";
            case "diamond": return "💎";
            case "fire":    return "🔥";
            case "skull":   return "💀";
            case "moon":    return "🌙";
            default:        return badge;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}