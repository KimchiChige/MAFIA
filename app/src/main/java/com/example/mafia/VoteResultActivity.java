package com.example.mafia;

import android.animation.ObjectAnimator;
import android.content.Intent;
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

public class VoteResultActivity extends AppCompatActivity {

    private View fogOverlay;
    private LinearLayout executionLayout, tieLayout, continueLayout;
    private TextView executedNameText, voteCountText, executedRoleText, executedSubtext;
    private TextView continueTimerText;
    private Button continueButton;
    private ImageView executedPlayerPhoto;
    private View crossHorizontal, crossVertical;

    private String gameEndWinner;
    private String roomId;

    private CountDownTimer autoTimer;
    private static final int AUTO_PROCEED_SECONDS = 8;
    private boolean alreadyProceeded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vote_result);

        initViews();
        startSequence();
    }

    private void initViews() {
        fogOverlay      = findViewById(R.id.fogOverlay);
        executionLayout = findViewById(R.id.executionLayout);
        tieLayout       = findViewById(R.id.tieLayout);
        continueLayout  = findViewById(R.id.continueLayout);

        executedNameText  = findViewById(R.id.executedNameText);
        voteCountText     = findViewById(R.id.voteCountText);
        executedRoleText  = findViewById(R.id.executedRoleText);
        executedSubtext   = findViewById(R.id.executedSubtext);
        executedPlayerPhoto = findViewById(R.id.executedPlayerPhoto);
        crossHorizontal   = findViewById(R.id.crossHorizontal);
        crossVertical     = findViewById(R.id.crossVertical);
        continueTimerText = findViewById(R.id.continueTimerText);
        continueButton    = findViewById(R.id.continueButton);

        gameEndWinner = normalizeWinner(getIntent().getStringExtra("gameEndWinner"));
        roomId        = getIntent().getStringExtra("roomId");

        continueButton.setOnClickListener(v -> proceedNext());
    }

    private void startSequence() {
        Intent intent   = getIntent();
        String name     = intent.getStringExtra("executedPlayerName");
        String role     = intent.getStringExtra("executedPlayerRole");
        String photoB64 = intent.getStringExtra("executedPlayerPhoto");
        boolean wasTie  = intent.getBooleanExtra("wasTie", false);
        int voteCount   = intent.getIntExtra("voteCount", 0);
        int totalVotes  = intent.getIntExtra("totalVotes", 0);

        boolean hasExecution = !wasTie && name != null && !name.trim().isEmpty();

        if (hasExecution) {
            playKillSound();
        }

        animateFog(() -> {
            if (hasExecution) {
                showExecution(name, role, photoB64, voteCount, totalVotes);
                new Handler().postDelayed(() -> revealRole(role), 1500);
                new Handler().postDelayed(this::animateCross, 2200);
                new Handler().postDelayed(this::showContinue, 4000);
            } else {
                showTie();
                new Handler().postDelayed(this::showContinue, 2000);
            }
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
        ObjectAnimator anim = ObjectAnimator.ofFloat(fogOverlay, "alpha", 0f, 0.9f);
        anim.setDuration(700);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
        new Handler().postDelayed(onComplete, 700);
    }

    private void showExecution(String name, String role, String photoB64, int voteCount, int totalVotes) {
        executedNameText.setText(name);
        if (voteCount > 0) {
            voteCountText.setText(voteCount + " из " + totalVotes + " голосов");
        }

        // Загрузить фото
        if (photoB64 != null && !photoB64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(photoB64, Base64.DEFAULT);
                Glide.with(this)
                        .load(bytes)
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.ic_player_avatar1)
                        .into(executedPlayerPhoto);
            } catch (Exception e) {
                executedPlayerPhoto.setImageResource(R.drawable.ic_player_avatar1);
            }
        } else {
            executedPlayerPhoto.setImageResource(R.drawable.ic_player_avatar1);
        }

        executionLayout.setVisibility(View.VISIBLE);
        executionLayout.setAlpha(0f);
        executionLayout.animate().alpha(1f).setDuration(600)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void revealRole(String role) {
        executedRoleText.setText(getRoleDisplay(role));
        executedRoleText.setVisibility(View.VISIBLE);
        executedRoleText.setAlpha(0f);
        executedRoleText.animate().alpha(1f).setDuration(500).start();

        executedSubtext.setVisibility(View.VISIBLE);
        executedSubtext.setAlpha(0f);
        executedSubtext.animate().alpha(1f).setDuration(500).setStartDelay(250).start();
    }

    /**
     * Крест падает сверху — такой же как в NightResultActivity.
     */
    private void animateCross() {
        int size = (int)(200 * getResources().getDisplayMetrics().density);

        crossHorizontal.getLayoutParams().width = size;
        crossHorizontal.requestLayout();
        crossHorizontal.setAlpha(0.95f);
        crossHorizontal.setTranslationY(-size);
        crossHorizontal.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new AccelerateInterpolator())
                .start();

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

    private void showTie() {
        tieLayout.setVisibility(View.VISIBLE);
        tieLayout.setAlpha(0f);
        tieLayout.animate().alpha(1f).setDuration(600).start();
    }

    private void showContinue() {
        continueLayout.setVisibility(View.VISIBLE);
        continueLayout.setAlpha(0f);
        continueLayout.animate().alpha(1f).setDuration(400).start();

        autoTimer = new CountDownTimer(AUTO_PROCEED_SECONDS * 1000L, 1000) {
            @Override public void onTick(long ms) {
                int sec = (int)(ms / 1000);
                continueTimerText.setText("Ночь наступит через " + sec + "с");
                if (sec <= 3) continueButton.setVisibility(View.VISIBLE);
            }
            @Override public void onFinish() { proceedNext(); }
        }.start();
    }

    private void proceedNext() {
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
        if (role == null || role.trim().isEmpty()) return "Роль неизвестна";
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