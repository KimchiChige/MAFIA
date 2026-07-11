package com.example.mafia;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;

public class NightResultActivity extends AppCompatActivity {

    private View fogOverlay;
    private LinearLayout deathLayout, savedLayout, sheriffResultLayout, continueLayout;
    private TextView headlineText, killedNameText, killedRoleText, killedSubtitleText;
    private TextView savedTitleText, savedSubtitleText;
    private TextView sheriffResultText;
    private TextView continueTimerText;
    private Button continueButton;
    private ImageView killedPlayerPhoto;
    private View crossHorizontal, crossVertical;

    private boolean isSheriff;
    private String gameEndWinner;
    private String roomId;
    private boolean isHost;

    private CountDownTimer autoTimer;
    private static final int AUTO_PROCEED_SECONDS = 10;
    private boolean alreadyProceeded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_night_result);

        initViews();
        parseIntent();
        startSequence();
    }

    private void initViews() {
        fogOverlay          = findViewById(R.id.fogOverlay);
        deathLayout         = findViewById(R.id.deathLayout);
        savedLayout         = findViewById(R.id.savedLayout);
        sheriffResultLayout = findViewById(R.id.sheriffResultLayout);
        continueLayout      = findViewById(R.id.continueLayout);

        headlineText        = findViewById(R.id.headlineText);
        killedNameText      = findViewById(R.id.killedNameText);
        killedRoleText      = findViewById(R.id.killedRoleText);
        killedSubtitleText  = findViewById(R.id.killedSubtitleText);
        killedPlayerPhoto   = findViewById(R.id.killedPlayerPhoto);
        crossHorizontal     = findViewById(R.id.crossHorizontal);
        crossVertical       = findViewById(R.id.crossVertical);

        savedTitleText      = findViewById(R.id.savedTitleText);
        savedSubtitleText   = findViewById(R.id.savedSubtitleText);
        sheriffResultText   = findViewById(R.id.sheriffResultText);
        continueTimerText   = findViewById(R.id.continueTimerText);
        continueButton      = findViewById(R.id.continueButton);

        continueButton.setOnClickListener(v -> proceedToGame());
    }

    private void parseIntent() {
        Intent intent = getIntent();
        isSheriff     = intent.getBooleanExtra("isSheriff", false);
        gameEndWinner = normalizeWinner(intent.getStringExtra("gameEndWinner"));
        roomId        = intent.getStringExtra("roomId");
        isHost        = intent.getBooleanExtra("isHost", false);
    }

    private void startSequence() {
        Intent intent = getIntent();
        String killedName       = intent.getStringExtra("killedPlayerName");
        String killedRole       = intent.getStringExtra("killedPlayerRole");
        String killedPhotoB64   = intent.getStringExtra("killedPlayerPhoto");
        boolean wasBlocked      = intent.getBooleanExtra("wasKillBlocked", false);
        String sheriffTargetName = intent.getStringExtra("sheriffTargetName");
        String sheriffTargetRole = intent.getStringExtra("sheriffTargetRole");

        boolean hasDeath = killedName != null && !killedName.trim().isEmpty() && !wasBlocked;

        // Играем звук только один раз здесь
        if (hasDeath) {
            playKillSound();
        }

        animateFog(() -> {
            if (hasDeath) {
                showDeathMessage(killedName, killedRole, killedPhotoB64);
                new Handler().postDelayed(() -> revealRole(killedRole), 1200);
                new Handler().postDelayed(this::animateCross, 2000);
            } else {
                showSavedMessage(wasBlocked);
            }

            long sheriffDelay = hasDeath ? 4000L : 2000L;
            if (isSheriff && sheriffTargetName != null) {
                new Handler().postDelayed(() ->
                        showSheriffMessage(sheriffTargetName, sheriffTargetRole), sheriffDelay);
            }
            long continueDelay = isSheriff && sheriffTargetName != null
                    ? sheriffDelay + 1200L : sheriffDelay;
            new Handler().postDelayed(this::showContinueButton, continueDelay);
        });
    }

    private void playKillSound() {
        try {
            MediaPlayer mp = MediaPlayer.create(this, R.raw.sound_for_kill);
            if (mp != null) {
                mp.start();
                mp.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception ignored) {}
    }

    private void animateFog(Runnable onComplete) {
        ObjectAnimator fogAnim = ObjectAnimator.ofFloat(fogOverlay, "alpha", 0f, 0.85f);
        fogAnim.setDuration(800);
        fogAnim.setInterpolator(new DecelerateInterpolator());
        fogAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { onComplete.run(); }
        });
        fogAnim.start();
    }

    private void showDeathMessage(String name, String role, String photoBase64) {
        killedNameText.setText(name);
        deathLayout.setVisibility(View.VISIBLE);
        deathLayout.setAlpha(0f);
        deathLayout.animate()
                .alpha(1f).setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        if (photoBase64 != null && !photoBase64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(photoBase64, Base64.DEFAULT);
                Glide.with(this)
                        .load(bytes)
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.ic_player_avatar1)
                        .into(killedPlayerPhoto);
            } catch (Exception e) {
                killedPlayerPhoto.setImageResource(R.drawable.ic_player_avatar1);
            }
        } else {
            killedPlayerPhoto.setImageResource(R.drawable.ic_player_avatar1);
        }
    }

    private void revealRole(String role) {
        killedRoleText.setText(getRoleDisplay(role));
        killedRoleText.setVisibility(View.VISIBLE);
        killedRoleText.setAlpha(0f);
        killedRoleText.animate().alpha(1f).setDuration(500).start();

        killedSubtitleText.setVisibility(View.VISIBLE);
        killedSubtitleText.setAlpha(0f);
        killedSubtitleText.animate().alpha(1f).setDuration(500).setStartDelay(300).start();
    }

    /**
     * Анимация красного креста: обе линии падают сверху на карточку (translateY от -300 до 0),
     * полностью видимые сразу — эффект "падает с лица игрока на экран".
     */
    private void animateCross() {
        int size = (int)(200 * getResources().getDisplayMetrics().density);

        // Горизонтальная диагональ — сразу полной длины, падает сверху
        crossHorizontal.getLayoutParams().width = size;
        crossHorizontal.requestLayout();
        crossHorizontal.setAlpha(0.95f);
        crossHorizontal.setTranslationY(-size);
        crossHorizontal.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new AccelerateInterpolator())
                .start();

        // Вертикальная диагональ — с небольшой задержкой
        new Handler().postDelayed(() -> {
            crossVertical.getLayoutParams().height = size;
            crossVertical.requestLayout();
            crossVertical.setAlpha(0.95f);
            crossVertical.setTranslationY(-size);
            crossVertical.animate()
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        }, 150);
    }

    private void showSavedMessage(boolean doctorSaved) {
        savedLayout.setVisibility(View.VISIBLE);
        savedLayout.setAlpha(0f);
        if (doctorSaved) {
            savedSubtitleText.setText("Доктор успел вовремя");
        } else {
            savedSubtitleText.setText("Мафия не смогла договориться");
        }
        savedLayout.animate().alpha(1f).setDuration(600)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void showSheriffMessage(String targetName, String targetRole) {
        boolean isMafia = "mafia".equals(targetRole);
        String roleLabel = isMafia ? "— МАФИЯ 🔴" : "— МИРНЫЙ ✅";
        sheriffResultText.setText(targetName + " " + roleLabel);
        sheriffResultText.setTextColor(isMafia
                ? getColor(android.R.color.holo_red_light)
                : getColor(android.R.color.holo_green_light));
        sheriffResultLayout.setVisibility(View.VISIBLE);
        sheriffResultLayout.setAlpha(0f);
        sheriffResultLayout.animate().alpha(1f).setDuration(500).start();
    }

    private void showContinueButton() {
        continueLayout.setVisibility(View.VISIBLE);
        continueLayout.setAlpha(0f);
        continueLayout.animate().alpha(1f).setDuration(400).start();

        autoTimer = new CountDownTimer(AUTO_PROCEED_SECONDS * 1000L, 1000) {
            @Override public void onTick(long millisUntilFinished) {
                int sec = (int)(millisUntilFinished / 1000);
                continueTimerText.setText("Продолжение через " + sec + "с");
                if (sec <= 3) continueButton.setVisibility(View.VISIBLE);
            }
            @Override public void onFinish() { proceedToGame(); }
        }.start();
    }

    private void proceedToGame() {
        if (alreadyProceeded) return;
        alreadyProceeded = true;
        if (autoTimer != null) autoTimer.cancel();
        if (isValidWinner(gameEndWinner)) {
            Intent intent = new Intent(this, GameEndActivity.class);
            intent.putExtra("winner", gameEndWinner);
            intent.putExtra("roomId", roomId);
            startActivity(intent);
        } else {
            finish();
        }
    }

    private static String normalizeWinner(String winner) {
        if (winner == null || winner.trim().isEmpty()) return null;
        return winner.trim();
    }

    private static boolean isValidWinner(String winner) {
        return "city".equals(winner) || "mafia".equals(winner);
    }

    private String getRoleDisplay(String role) {
        if (role == null) return "Роль неизвестна";
        switch (role) {
            case "mafia":    return "🔫 МАФИЯ";
            case "sheriff":  return "🕵️ ШЕРИФ";
            case "doctor":   return "💉 ДОКТОР";
            case "civilian": return "👤 МИРНЫЙ";
            default:         return role.toUpperCase();
        }
    }

    @Override public void onBackPressed() { }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (autoTimer != null) autoTimer.cancel();
    }
}