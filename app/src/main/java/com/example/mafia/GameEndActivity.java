package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import java.util.HashMap;
import java.util.Map;

/**
 * Экран победы/поражения.
 * Получает: winner ("mafia" или "city"), roomId.
 * После показа результата обновляет статистику всех участников игры.
 */
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
        Button    exitButton     = findViewById(R.id.exitButton);

        boolean mafiaWon = "mafia".equals(winner);

        winnerIcon.setImageResource(mafiaWon ? R.drawable.mafia2 : R.drawable.city_win);
        winnerTitle.setText(mafiaWon ? "МАФИЯ ПОБЕДИЛА" : "ГОРОД ПОБЕДИЛ");
        winnerSubtitle.setText(mafiaWon
                ? "Преступники захватили город.\nНикому нельзя доверять."
                : "Мафия уничтожена.\nСправедливость восстановлена.");
        winnerTitle.setTextColor(mafiaWon
                ? getColor(android.R.color.holo_red_light)
                : 0xFF4CAF50);

        // Анимация появления
        View root = findViewById(R.id.gameEndRoot);
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(1000).start();

        // Обновляем статистику игроков
        if (roomId != null) {
            updatePlayerStats(roomId, winner);
        }

        exitButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LobbyActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    /**
     * Читает документ игры, находит всех участников и обновляет:
     *  - totalGames  (+1 всем)
     *  - wins        (+1 победившей команде)
     *  - level       = 1 + floor(totalGames / 5)
     *
     * Защита от двойного начисления: атомарно выставляем флаг statsUpdated = true
     * через транзакцию. Если флаг уже стоит — другой клиент уже обновил, выходим.
     */
    @SuppressWarnings("unchecked")
    private void updatePlayerStats(String roomId, String winner) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        com.google.firebase.firestore.DocumentReference gameRef =
                db.collection("games").document(roomId);

        db.runTransaction(transaction -> {
            com.google.firebase.firestore.DocumentSnapshot snap = transaction.get(gameRef);
            if (!snap.exists()) return null;
            if (Boolean.TRUE.equals(snap.get("statsUpdated"))) return null;
            transaction.update(gameRef, "statsUpdated", true);
            return snap.getData();
        }).addOnSuccessListener(data -> {
            if (data == null) {
                Log.d(TAG, "Статистика уже была обновлена другим клиентом, пропускаем");
                return;
            }
            doUpdateStats(db, (Map<String, Object>) data, winner);
        }).addOnFailureListener(e -> Log.e(TAG, "Ошибка транзакции statsUpdated", e));
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
                if (roleObj != null) roles.put(entry.getKey(), roleObj.toString().trim().toLowerCase());
            }
        }
        if (roles.isEmpty()) return;

        db.collection("users")
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(),
                        new java.util.ArrayList<>(roles.keySet()))
                .get()
                .addOnSuccessListener(usersSnap -> {
                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    for (com.google.firebase.firestore.DocumentSnapshot userDoc : usersSnap.getDocuments()) {
                        String uid  = userDoc.getId();
                        String role = roles.getOrDefault(uid, "");
                        long totalGames = userDoc.getLong("totalGames") != null ? userDoc.getLong("totalGames") : 0L;
                        long wins       = userDoc.getLong("wins")       != null ? userDoc.getLong("wins")       : 0L;
                        totalGames += 1;
                        if (didWin(role, winner)) wins += 1;
                        long level = 1 + (totalGames / 5);
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("totalGames", totalGames);
                        upd.put("wins", wins);
                        upd.put("level", level);
                        batch.update(userDoc.getReference(), upd);
                        Log.d(TAG, "Статистика " + uid + ": игр=" + totalGames + " побед=" + wins);
                    }
                    batch.commit()
                            .addOnSuccessListener(v -> Log.d(TAG, "Статистика обновлена"))
                            .addOnFailureListener(e -> Log.e(TAG, "Ошибка batch", e));
                })
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка чтения пользователей", e));
    }


    /** Возвращает true если роль игрока входит в победившую команду */
    private boolean didWin(String role, String winner) {
        if ("mafia".equals(winner)) {
            return "mafia".equals(role);
        } else {
            // "city" победил — все кроме мафии
            return "civilian".equals(role) || "sheriff".equals(role) || "doctor".equals(role);
        }
    }

    @Override
    public void onBackPressed() {
        // Блокируем кнопку назад на экране конца игры
    }
}