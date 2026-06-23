package com.example.mafia;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class VoteResultActivity extends AppCompatActivity {

    private View fogOverlay;
    private LinearLayout executionLayout, tieLayout, continueLayout;
    private TextView executedNameText, voteCountText, executedRoleText, executedSubtext;
    private TextView continueTimerText;
    private Button continueButton;

    private String gameEndWinner;
    private String roomId;

    private CountDownTimer autoTimer;
    private static final int AUTO_PROCEED_SECONDS = 7;

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

        executedNameText = findViewById(R.id.executedNameText);
        voteCountText    = findViewById(R.id.voteCountText);
        executedRoleText = findViewById(R.id.executedRoleText);
        executedSubtext  = findViewById(R.id.executedSubtext);
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
        boolean wasTie  = intent.getBooleanExtra("wasTie", false);
        int voteCount   = intent.getIntExtra("voteCount", 0);
        int totalVotes  = intent.getIntExtra("totalVotes", 0);

        boolean hasExecution = !wasTie && name != null && !name.trim().isEmpty();

        animateFog(() -> {
            if (hasExecution) {
                showExecution(name, role, voteCount, totalVotes);
                new Handler().postDelayed(() -> revealRole(role), 2000);
                new Handler().postDelayed(this::showContinue, 3500);
            } else {
                showTie();
                new Handler().postDelayed(this::showContinue, 2000);
            }
        });
    }

    private void animateFog(Runnable onComplete) {
        ObjectAnimator anim = ObjectAnimator.ofFloat(fogOverlay, "alpha", 0f, 0.9f);
        anim.setDuration(700);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
        new Handler().postDelayed(onComplete, 700);
    }

    private void showExecution(String name, String role, int voteCount, int totalVotes) {
        executedNameText.setText(name);
        if (voteCount > 0) {
            voteCountText.setText(voteCount + " из " + totalVotes + " голосов");
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