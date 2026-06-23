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
     *  - level       = 1 + floor(totalGames / 5)  — новый уровень каждые 5 игр
     */
    @SuppressWarnings("unchecked")
    private void updatePlayerStats(String roomId, String winner) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("games").document(roomId).get()
                .addOnSuccessListener(gameSnap -> {
                    if (!gameSnap.exists()) return;
                    Map<String, Object> data = gameSnap.getData();
                    if (data == null) return;

                    Object playersObj = data.get("players");
                    if (!(playersObj instanceof Map)) return;
                    Map<String, Object> players = (Map<String, Object>) playersObj;

                    // Собираем uid → role для всех игроков
                    Map<String, String> roles = new HashMap<>();
                    for (Map.Entry<String, Object> entry : players.entrySet()) {
                        if (entry.getValue() instanceof Map) {
                            Map<String, Object> p = (Map<String, Object>) entry.getValue();
                            Object roleObj = p.get("role");
                            if (roleObj != null) {
                                roles.put(entry.getKey(), roleObj.toString().trim().toLowerCase());
                            }
                        }
                    }

                    if (roles.isEmpty()) return;

                    // Читаем текущую статистику каждого игрока и пишем батчем
                    db.collection("users")
                            .whereIn(com.google.firebase.firestore.FieldPath.documentId(),
                                    new java.util.ArrayList<>(roles.keySet()))
                            .get()
                            .addOnSuccessListener(usersSnap -> {
                                WriteBatch batch = db.batch();

                                for (DocumentSnapshot userDoc : usersSnap.getDocuments()) {
                                    String uid  = userDoc.getId();
                                    String role = roles.getOrDefault(uid, "");

                                    long totalGames = userDoc.getLong("totalGames") != null
                                            ? userDoc.getLong("totalGames") : 0L;
                                    long wins = userDoc.getLong("wins") != null
                                            ? userDoc.getLong("wins") : 0L;

                                    totalGames += 1;
                                    boolean isWinner = didWin(role, winner);
                                    if (isWinner) wins += 1;

                                    // Уровень: 1 + 1 за каждые 5 игр
                                    long level = 1 + (totalGames / 5);

                                    Map<String, Object> updates = new HashMap<>();
                                    updates.put("totalGames", totalGames);
                                    updates.put("wins", wins);
                                    updates.put("level", level);

                                    batch.update(userDoc.getReference(), updates);
                                    Log.d(TAG, "Статистика " + uid + ": игр=" + totalGames
                                            + " побед=" + wins + " уровень=" + level);
                                }

                                batch.commit()
                                        .addOnSuccessListener(v -> Log.d(TAG, "Статистика обновлена"))
                                        .addOnFailureListener(e -> Log.e(TAG, "Ошибка обновления статистики", e));
                            })
                            .addOnFailureListener(e -> Log.e(TAG, "Ошибка чтения пользователей", e));
                })
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка чтения игры", e));
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
