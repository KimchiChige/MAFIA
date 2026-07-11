package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class GameEndActivity extends AppCompatActivity {

    private static final String TAG = "GAME_END";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_end);

        String winner = getIntent().getStringExtra("winner");
        String roomId = getIntent().getStringExtra("roomId");

        ImageView winnerIcon     = findViewById(R.id.winnerIcon);
        TextView  winnerTitle    = findViewById(R.id.winnerTitle);
        TextView  winnerSubtitle = findViewById(R.id.winnerSubtitle);

        View exitButton = findViewById(R.id.exitButton);
        if (exitButton != null) exitButton.setVisibility(View.GONE);

        boolean mafiaWon = "mafia".equals(winner);

        winnerIcon.setImageResource(mafiaWon ? R.drawable.mafia2 : R.drawable.city_win);
        winnerTitle.setText(mafiaWon ? "МАФИЯ ПОБЕДИЛА" : "ГОРОД ПОБЕДИЛ");
        winnerSubtitle.setText(mafiaWon
                ? "Преступники захватили город.\nНикому нельзя доверять."
                : "Мафия уничтожена.\nСправедливость восстановлена.");
        winnerTitle.setTextColor(mafiaWon
                ? getColor(android.R.color.holo_red_light)
                : 0xFF4CAF50);

        View root = findViewById(R.id.gameEndRoot);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(1000).start();

        if (roomId != null) {
            updatePlayerStatsAndProceed(roomId, winner);
        } else {
            proceedToDiamondScreen(0, false);
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePlayerStatsAndProceed(String roomId, String winner) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        DocumentReference gameRef = db.collection("games").document(roomId);

        db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(gameRef);
            if (!snap.exists()) return null;
            if (Boolean.TRUE.equals(snap.getBoolean("statsUpdated"))) return null;
            transaction.update(gameRef, "statsUpdated", true);
            return snap.getData();
        }).addOnSuccessListener(data -> {
            if (data == null) {
                Log.d(TAG, "Статистика уже была обновлена другим клиентом");
                if (uid != null) loadMyDiamondsAndProceed(db, roomId, winner, uid);
                else proceedToDiamondScreen(0, false);
                return;
            }
            doUpdateStats(db, (Map<String, Object>) data, winner);
            if (uid != null) loadMyDiamondsAndProceed(db, roomId, winner, uid);
            else proceedToDiamondScreen(0, false);
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Ошибка транзакции statsUpdated", e);
            proceedToDiamondScreen(0, false);
        });
    }

    @SuppressWarnings("unchecked")
    private void loadMyDiamondsAndProceed(FirebaseFirestore db, String roomId, String winner, String uid) {
        db.collection("games").document(roomId).get()
                .addOnSuccessListener(gameSnap -> {
                    int diamonds = 0;
                    boolean isWinner = false;

                    if (gameSnap.exists()) {
                        Map<String, Object> players = NightResultProcessor.asMap(gameSnap.get("players"));
                        if (players != null && players.containsKey(uid)) {
                            Map<String, Object> me = NightResultProcessor.asMap(players.get(uid));
                            if (me != null) {
                                String myRole = me.get("role") != null
                                        ? me.get("role").toString().trim().toLowerCase() : "";
                                isWinner = didWin(myRole, winner);

                                if (isWinner) {
                                    diamonds = (int) DiamondManager.WINNER_REWARD;
                                } else {
                                    Object deathPosObj = me.get("deathPosition");
                                    int deathPos = 0;
                                    if (deathPosObj instanceof Number)
                                        deathPos = ((Number) deathPosObj).intValue();
                                    diamonds = DiamondManager.calcDiamondsForPosition(deathPos, false);
                                }
                            }
                        }
                    }

                    consumePerksAfterGame(db, uid);

                    final int finalDiamonds = diamonds;
                    final boolean finalIsWinner = isWinner;
                    new android.os.Handler().postDelayed(() ->
                            proceedToDiamondScreen(finalDiamonds, finalIsWinner), 2500);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Ошибка загрузки игры для алмазов", e);
                    proceedToDiamondScreen(0, false);
                });
    }

    private void consumePerksAfterGame(FirebaseFirestore db, String uid) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    for (String perk : new String[]{"shield", "selfheal", "invisible"}) {
                        String perkField  = DiamondManager.getPerkField(perk);
                        // поле называется activePerk_perk_shield и т.д.
                        Boolean active = doc.getBoolean("activePerk_" + perkField);
                        if (Boolean.TRUE.equals(active)) {
                            DiamondManager.consumeActivePerk(db, uid, perk);
                        }
                    }
                });
    }

    private void proceedToDiamondScreen(int diamonds, boolean isWinner) {
        String roomId = getIntent().getStringExtra("roomId");
        String winner = getIntent().getStringExtra("winner");

        Intent intent = new Intent(this, DiamondResultActivity.class);
        intent.putExtra("diamondsEarned", diamonds);
        intent.putExtra("roomId", roomId);
        intent.putExtra("isWinner", isWinner);
        intent.putExtra("winner", winner);
        startActivity(intent);
        finish();
    }

    @SuppressWarnings("unchecked")
    private void doUpdateStats(FirebaseFirestore db, Map<String, Object> gameData, String winner) {
        Object playersObj = gameData.get("players");
        if (!(playersObj instanceof Map)) return;
        Map<String, Object> players = (Map<String, Object>) playersObj;

        Map<String, String> roles = new HashMap<>();
        for (Map.Entry<String, Object> entry : players.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> p = (Map<String, Object>) entry.getValue();
                Object roleObj = p.get("role");
                if (roleObj != null)
                    roles.put(entry.getKey(), roleObj.toString().trim().toLowerCase());
            }
        }
        if (roles.isEmpty()) return;

        db.collection("users")
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(),
                        new ArrayList<>(roles.keySet()))
                .get()
                .addOnSuccessListener(usersSnap -> {
                    WriteBatch batch = db.batch();
                    for (QueryDocumentSnapshot userDoc : usersSnap) {
                        String uid  = userDoc.getId();
                        String role = roles.containsKey(uid) ? roles.get(uid) : "";
                        long totalGames = userDoc.getLong("totalGames") != null
                                ? userDoc.getLong("totalGames") : 0L;
                        long wins = userDoc.getLong("wins") != null
                                ? userDoc.getLong("wins") : 0L;
                        totalGames += 1;
                        if (didWin(role, winner)) wins += 1;
                        long level = 1 + (totalGames / 5);
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("totalGames", totalGames);
                        upd.put("wins", wins);
                        upd.put("level", level);
                        batch.update(userDoc.getReference(), upd);
                    }
                    batch.commit()
                            .addOnSuccessListener(v -> Log.d(TAG, "Статистика обновлена"))
                            .addOnFailureListener(e -> Log.e(TAG, "Ошибка batch", e));
                });
    }

    private boolean didWin(String role, String winner) {
        if ("mafia".equals(winner)) return "mafia".equals(role);
        return "civilian".equals(role) || "sheriff".equals(role) || "doctor".equals(role);
    }

    @Override public void onBackPressed() { }
}