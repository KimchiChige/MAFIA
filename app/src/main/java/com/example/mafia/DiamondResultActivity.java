package com.example.mafia;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

/**
 * DiamondResultActivity с Premium-бонусом (+30 алмазов за игру).
 */
public class DiamondResultActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diamond_result);

        int diamonds    = getIntent().getIntExtra("diamondsEarned", 0);
        String roomId   = getIntent().getStringExtra("roomId");
        boolean isWinner = getIntent().getBooleanExtra("isWinner", false);

        TextView titleText       = findViewById(R.id.drTitleText);
        TextView subtitleText    = findViewById(R.id.drSubtitleText);
        TextView diamondCount    = findViewById(R.id.drDiamondCount);
        TextView totalLabel      = findViewById(R.id.drTotalLabel);
        TextView totalCount      = findViewById(R.id.drTotalCount);
        // Premium бонус строка
        TextView premiumBonusTv  = findViewById(R.id.drPremiumBonus);
        Button   lobbyButton     = findViewById(R.id.drLobbyButton);

        if (premiumBonusTv != null) premiumBonusTv.setVisibility(View.GONE);

        // ── Маркетинг: если игрок использовал пробную Premium-игру, списываем
        // попытку именно здесь — по факту завершения игры, а не в момент выбора
        // роли. Так пробник не "сгорает", если игра почему-то не состоялась
        // (например, хост отменил комнату до старта).
        String currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (currentUid != null) {
            consumeTrialIfUsed(currentUid);
        }

        if (isWinner) {
            titleText.setText("🏆 ПОБЕДА!");
            titleText.setTextColor(0xFFFFD700);
            subtitleText.setText("Ты на стороне победителей");
        } else if (diamonds == 0) {
            titleText.setText("💀 ИГРА ОКОНЧЕНА");
            titleText.setTextColor(0xFFCC0000);
            subtitleText.setText("В следующий раз повезёт больше");
        } else {
            titleText.setText("⚔️ ИГРА ОКОНЧЕНА");
            subtitleText.setText("Ты сражался до конца");
        }

        View root = findViewById(R.id.drRoot);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(600).start();

        new Handler().postDelayed(() ->
                animateCounter(diamondCount, 0, diamonds, 1200), 400);

        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (uid != null) {
            if (diamonds > 0) {
                awardDiamonds(uid, diamonds, totalLabel, totalCount,
                        premiumBonusTv, roomId);
            } else {
                // Нет стандартных алмазов — всё равно проверяем Premium бонус
                checkAndAwardPremiumBonus(uid, premiumBonusTv, totalLabel, totalCount, 0);
            }
        }

        lobbyButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LobbyActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        TextView diamondEmoji = findViewById(R.id.drDiamondEmoji);
        ValueAnimator pulse = ValueAnimator.ofFloat(1f, 1.2f, 1f);
        pulse.setDuration(1500);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.addUpdateListener(a -> {
            float s = (float) a.getAnimatedValue();
            diamondEmoji.setScaleX(s);
            diamondEmoji.setScaleY(s);
        });
        new Handler().postDelayed(pulse::start, 500);
    }

    private void animateCounter(TextView tv, int from, int to, int duration) {
        ValueAnimator anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(duration);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> tv.setText("+" + a.getAnimatedValue()));
        anim.start();
        tv.setScaleX(0.5f);
        tv.setScaleY(0.5f);
        tv.animate().scaleX(1f).scaleY(1f).setDuration(400)
                .setInterpolator(new OvershootInterpolator()).start();
    }

    private void awardDiamonds(String uid, int amount, TextView totalLabel, TextView totalCount,
                               TextView premiumBonusTv, String roomId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Map<String, Object> upd = new HashMap<>();
        upd.put("diamonds", FieldValue.increment(amount));
        db.collection("users").document(uid).update(upd)
                .addOnSuccessListener(v ->
                        checkAndAwardPremiumBonus(uid, premiumBonusTv, totalLabel, totalCount, amount))
                .addOnFailureListener(e ->
                        checkAndAwardPremiumBonus(uid, premiumBonusTv, totalLabel, totalCount, amount));
    }

    /**
     * Проверяет Premium статус и начисляет +30 алмазов, показывает строку бонуса.
     * @param alreadyAwarded — сколько уже начислено до этого (для финального total)
     */
    private void checkAndAwardPremiumBonus(String uid, TextView premiumBonusTv,
                                           TextView totalLabel, TextView totalCount,
                                           int alreadyAwarded) {
        PremiumManager.awardPremiumGameBonus(
                FirebaseFirestore.getInstance(), uid,
                new PremiumManager.OnBonusResult() {
                    @Override public void onDone(int bonusAdded) {
                        if (bonusAdded > 0 && premiumBonusTv != null) {
                            premiumBonusTv.setVisibility(View.VISIBLE);
                            premiumBonusTv.setText("👑 Premium бонус: +" + bonusAdded + " 💎");
                            // Анимируем появление
                            premiumBonusTv.setAlpha(0f);
                            premiumBonusTv.animate().alpha(1f).setDuration(600)
                                    .setStartDelay(700).start();
                        }
                        loadTotalDiamonds(uid, totalLabel, totalCount);
                    }
                    @Override public void onError() {
                        loadTotalDiamonds(uid, totalLabel, totalCount);
                    }
                });
    }

    private void loadTotalDiamonds(String uid, TextView totalLabel, TextView totalCount) {
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    long total = doc.getLong("diamonds") != null ? doc.getLong("diamonds") : 0L;
                    totalLabel.setVisibility(View.VISIBLE);
                    totalCount.setVisibility(View.VISIBLE);
                    totalCount.setText(total + " 💎");
                    totalLabel.animate().alpha(1f).setDuration(400).start();
                    totalCount.animate().alpha(1f).setDuration(400).setStartDelay(100).start();
                });
    }

    /**
     * Списывает пробную Premium-игру, если игрок реально ей воспользовался
     * (выбрал роль через RoleSelectionActivity в пробном режиме). Проверяем
     * это по факту: trialGameAvailable ещё true, но premiumChosenRole и
     * premiumRoleUsesDate выставлены на сегодня — значит пробник был активирован
     * в этой сессии игры.
     */
    private void consumeTrialIfUsed(String uid) {
        FirebaseFirestore.getInstance().collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    Boolean isPremium = doc.getBoolean("isPremium");
                    if (Boolean.TRUE.equals(isPremium)) return;   // обычный Premium — тут нечего списывать

                    Boolean trialField = doc.getBoolean("trialGameAvailable");
                    boolean trialWasAvailable = trialField == null || trialField;
                    if (!trialWasAvailable) return;   // уже был списан раньше

                    String chosenDate = doc.getString("premiumRoleUsesDate");
                    String today = todayDateStringLocal();
                    if (today.equals(chosenDate)) {
                        // Пробник был активирован сегодня — считаем использованным
                        PremiumManager.consumeTrialGame(FirebaseFirestore.getInstance(), uid);
                    }
                });
    }

    private String todayDateStringLocal() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.MONTH)
                + "-" + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    @Override public void onBackPressed() {}
}