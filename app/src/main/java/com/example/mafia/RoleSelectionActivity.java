package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Экран выбора роли — работает в двух режимах:
 *  1) Premium-пользователь → обычный ежедневный лимит (3 раза в день)
 *  2) Пользователь с доступным пробным периодом (isPremium == false,
 *     trialGameAvailable == true) → одна разовая пробная попытка,
 *     маркетинговый ход для привлечения к покупке Premium
 *
 * Режим определяется автоматически при открытии экрана — RoomActivity
 * ничего специального передавать не должен.
 */
public class RoleSelectionActivity extends AppCompatActivity {

    public static final int REQUEST_CODE = 4002;

    private FirebaseFirestore db;
    private String uid;
    private TextView usesLeftText;

    private CardView roleMafiaCard, roleDoctorCard, roleSheriffCard, roleCivilianCard;

    private boolean isPremiumMode = false;   // true = обычный Premium, false = пробный период
    private boolean isTrialMode   = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_selection);

        db  = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        usesLeftText = findViewById(R.id.roleUsesLeftText);

        setupCards();
        determineModeAndLoadUses();

        View backBtn = findViewById(R.id.backButton);
        if (backBtn != null) backBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    /** Определяет, Premium это или пробный период, и обновляет счётчик попыток. */
    private void determineModeAndLoadUses() {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        showNotEligible();
                        return;
                    }
                    boolean premium = Boolean.TRUE.equals(doc.getBoolean("isPremium"));

                    if (premium) {
                        isPremiumMode = true;
                        isTrialMode   = false;
                        loadPremiumUsesLeft();
                    } else {
                        PremiumManager.hasTrialAvailable(db, uid, available -> {
                            if (available) {
                                isPremiumMode = false;
                                isTrialMode   = true;
                                showTrialModeUi();
                            } else {
                                showNotEligible();
                            }
                        });
                    }
                })
                .addOnFailureListener(e -> showNotEligible());
    }

    private void loadPremiumUsesLeft() {
        PremiumManager.getRoleUsesLeft(db, uid, new PremiumManager.OnIntCallback() {
            @Override
            public void onResult(int uses) {
                if (usesLeftText == null) return;
                if (uses <= 0) {
                    usesLeftText.setText("Лимит исчерпан на сегодня (0/3)");
                    usesLeftText.setTextColor(0xFFCC0000);
                    disableRoleCards();
                } else {
                    usesLeftText.setText("Осталось попыток сегодня: " + uses + "/3");
                    usesLeftText.setTextColor(0xFFFFD700);
                }
            }
        });
    }

    private void showTrialModeUi() {
        if (usesLeftText != null) {
            usesLeftText.setText("🎁 Пробная попытка — попробуй Premium бесплатно!");
            usesLeftText.setTextColor(0xFF55FF88);
        }
    }

    /** Ни Premium, ни пробника нет — сюда попадать не должны (кнопка в RoomActivity
     *  для этого случая открывает витрину покупки, а не этот экран), но на всякий
     *  случай подстраховываемся. */
    private void showNotEligible() {
        Toast.makeText(this, "Функция доступна только с Premium", Toast.LENGTH_SHORT).show();
        setResult(RESULT_CANCELED);
        finish();
    }

    private void setupCards() {
        roleMafiaCard    = findViewById(R.id.roleMafiaButton);
        roleDoctorCard   = findViewById(R.id.roleDoctorButton);
        roleSheriffCard  = findViewById(R.id.roleSheriffButton);
        roleCivilianCard = findViewById(R.id.roleCivilianButton);
        Button skipBtn   = findViewById(R.id.roleSkipButton);

        if (roleMafiaCard    != null) roleMafiaCard.setOnClickListener(v    -> selectRole("mafia"));
        if (roleDoctorCard   != null) roleDoctorCard.setOnClickListener(v   -> selectRole("doctor"));
        if (roleSheriffCard  != null) roleSheriffCard.setOnClickListener(v  -> selectRole("sheriff"));
        if (roleCivilianCard != null) roleCivilianCard.setOnClickListener(v -> selectRole("civilian"));
        if (skipBtn != null) skipBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private void selectRole(String role) {
        PremiumManager.OnRoleSelectResult callback = new PremiumManager.OnRoleSelectResult() {
            @Override
            public void onSuccess(int usesLeft) {
                String message = isTrialMode
                        ? getRoleEmoji(role) + " Роль выбрана! (пробная попытка использована)"
                        : getRoleEmoji(role) + " Роль выбрана! Осталось попыток: " + usesLeft;
                Toast.makeText(RoleSelectionActivity.this, message, Toast.LENGTH_SHORT).show();
                Intent result = new Intent();
                result.putExtra("chosenRole", role);
                setResult(RESULT_OK, result);
                finish();
            }
            @Override
            public void onLimitReached() {
                String message = isTrialMode
                        ? "Пробная попытка уже использована"
                        : "Лимит выбора роли исчерпан (3/3 в день)";
                Toast.makeText(RoleSelectionActivity.this, message, Toast.LENGTH_SHORT).show();
                disableRoleCards();
                if (usesLeftText != null) usesLeftText.setText(message);
            }
            @Override
            public void onNotPremium() {
                Toast.makeText(RoleSelectionActivity.this,
                        "Только для Premium!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
            @Override
            public void onError() {
                Toast.makeText(RoleSelectionActivity.this,
                        "Ошибка, попробуйте позже", Toast.LENGTH_SHORT).show();
            }
        };

        if (isPremiumMode) {
            PremiumManager.useRoleSelection(db, uid, role, callback);
        } else if (isTrialMode) {
            PremiumManager.useTrialRoleSelection(db, uid, role, callback);
        } else {
            showNotEligible();
        }
    }

    /** Визуально "гасит" карточки и блокирует клики, когда лимит выборов исчерпан. */
    private void disableRoleCards() {
        CardView[] cards = { roleMafiaCard, roleDoctorCard, roleSheriffCard, roleCivilianCard };
        for (CardView card : cards) {
            if (card == null) continue;
            card.setAlpha(0.4f);
            card.setClickable(false);
            card.setOnClickListener(null);
        }
    }

    private String getRoleEmoji(String role) {
        switch (role) {
            case "mafia":   return "🔫";
            case "doctor":  return "💉";
            case "sheriff": return "🕵️";
            default:        return "👤";
        }
    }
}