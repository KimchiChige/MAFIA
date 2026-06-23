package com.example.mafia;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;


public class NightResultActivity extends AppCompatActivity {

    private View fogOverlay;
    private LinearLayout deathLayout, savedLayout, sheriffResultLayout, continueLayout;
    private TextView deathIcon, headlineText, killedNameText, killedRoleText, killedSubtitleText;
    private TextView savedTitleText, savedSubtitleText;
    private TextView sheriffResultText;
    private TextView continueTimerText;
    private Button continueButton;

    private boolean isSheriff;
    private String gameEndWinner;
    private String roomId;
    private boolean isHost;

    private CountDownTimer autoTimer;

    // Сколько секунд экран висит до автоперехода
    private static final int AUTO_PROCEED_SECONDS = 8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_night_result);

        initViews();
        parseIntent();
        startSequence();
    }

    private void initViews() {
        fogOverlay = findViewById(R.id.fogOverlay);
        deathLayout = findViewById(R.id.deathLayout);
        savedLayout = findViewById(R.id.savedLayout);
        sheriffResultLayout = findViewById(R.id.sheriffResultLayout);
        continueLayout = findViewById(R.id.continueLayout);

        deathIcon = findViewById(R.id.deathIcon);
        headlineText = findViewById(R.id.headlineText);
        killedNameText = findViewById(R.id.killedNameText);
        killedRoleText = findViewById(R.id.killedRoleText);
        killedSubtitleText = findViewById(R.id.killedSubtitleText);

        savedTitleText = findViewById(R.id.savedTitleText);
        savedSubtitleText = findViewById(R.id.savedSubtitleText);

        sheriffResultText = findViewById(R.id.sheriffResultText);
        continueTimerText = findViewById(R.id.continueTimerText);
        continueButton = findViewById(R.id.continueButton);

        continueButton.setOnClickListener(v -> proceedToGame());
    }

    private void parseIntent() {
        Intent intent = getIntent();
        isSheriff = intent.getBooleanExtra("isSheriff", false);
        gameEndWinner = normalizeWinner(intent.getStringExtra("gameEndWinner"));
        roomId = intent.getStringExtra("roomId");
        isHost = intent.getBooleanExtra("isHost", false);
    }


    private void startSequence() {
        Intent intent = getIntent();
        String killedName = intent.getStringExtra("killedPlayerName");
        String killedRole = intent.getStringExtra("killedPlayerRole");
        boolean wasBlocked = intent.getBooleanExtra("wasKillBlocked", false);
        String sheriffTargetName = intent.getStringExtra("sheriffTargetName");
        String sheriffTargetRole = intent.getStringExtra("sheriffTargetRole");
        boolean hasDeath = killedName != null && !killedName.trim().isEmpty() && !wasBlocked;
        animateFog(() -> {
            if (hasDeath) {
                showDeathMessage(killedName, killedRole);
                new Handler().postDelayed(() -> revealRole(killedRole), 1800);
            } else {
                showSavedMessage(wasBlocked);
            }
            long sheriffDelay = hasDeath ? 3200 : 2000;
            if (isSheriff && sheriffTargetName != null) {
                new Handler().postDelayed(() ->
                        showSheriffMessage(sheriffTargetName, sheriffTargetRole), sheriffDelay);
            }
            long continueDelay = isSheriff && sheriffTargetName != null
                    ? sheriffDelay + 1200
                    : sheriffDelay;
            new Handler().postDelayed(this::showContinueButton, continueDelay);
        });
    }

    private void animateFog(Runnable onComplete) {
        ObjectAnimator fogAnim = ObjectAnimator.ofFloat(fogOverlay, "alpha", 0f, 0.85f);
        fogAnim.setDuration(800);
        fogAnim.setInterpolator(new DecelerateInterpolator());
        fogAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                onComplete.run();
            }
        });
        fogAnim.start();
    }

    private void showDeathMessage(String name, String role) {
        killedNameText.setText(name);
        deathLayout.setVisibility(View.VISIBLE);
        deathLayout.setAlpha(0f);
        deathLayout.animate()
                .alpha(1f)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();
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

    private void showSavedMessage(boolean doctorSaved) {
        savedLayout.setVisibility(View.VISIBLE);
        savedLayout.setAlpha(0f);

        if (doctorSaved) {
            savedSubtitleText.setText("Доктор успел вовремя");
        } else {
            savedSubtitleText.setText("Мафия не смогла договориться");
        }

        savedLayout.animate()
                .alpha(1f)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();
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

        // Таймер автоперехода
        autoTimer = new CountDownTimer(AUTO_PROCEED_SECONDS * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                continueTimerText.setText("Продолжение через " + sec + "с");

                // Показываем кнопку в последние 3 секунды
                if (sec <= 3) {
                    continueButton.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onFinish() {
                proceedToGame();
            }
        }.start();
    }

    private void proceedToGame() {
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

    @Override
    public void onBackPressed() {
        // Блокируем — нельзя пропустить объявление
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (autoTimer != null) autoTimer.cancel();
    }
}