package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.mafia.Adapters.RoomPlayersAdapter;
import com.example.mafia.classes.Player;
import com.example.mafia.classes.Room;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoomActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private String roomId;
    private Room currentRoom;

    private TextView roomNameText, roomCodeText, playersCountText, hintText;
    private RecyclerView playersRecyclerView;
    private Button startGameButton, leaveRoomButton;  // readyButton УДАЛЕН
    private ProgressBar progressBar;

    private RoomPlayersAdapter playersAdapter;
    private List<Player> playersList = new ArrayList<>();
    private ListenerRegistration roomListener;

    private boolean isReady = false;  // Можно оставить для совместимости
    private boolean isHost = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);

        roomId = getIntent().getStringExtra("roomId");
        if (roomId == null) {
            Toast.makeText(this, "Ошибка: комната не найдена", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initFirebase();
        initViews();
        setupRoomListener();
    }

    private void initFirebase() {
        db = FirebaseFirestore.getInstance();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) finish();
    }

    private void initViews() {
        roomNameText = findViewById(R.id.roomNameText);
        roomCodeText = findViewById(R.id.roomCodeText);
        playersCountText = findViewById(R.id.playersCountText);
        hintText = findViewById(R.id.hintText);  // Добавлено
        playersRecyclerView = findViewById(R.id.playersRecyclerView);
        // readyButton = findViewById(R.id.readyButton);  // УДАЛЕНО
        startGameButton = findViewById(R.id.startGameButton);
        leaveRoomButton = findViewById(R.id.leaveRoomButton);
        progressBar = findViewById(R.id.progressBar);

        playersAdapter = new RoomPlayersAdapter(currentUser.getUid());
        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 2);
        playersRecyclerView.setLayoutManager(gridLayoutManager);
        playersRecyclerView.setAdapter(playersAdapter);

        // Обработчик клика на игрока (для отметки готовности)
        playersAdapter.setOnPlayerReadyClickListener(player -> {
            toggleReady();
        });

        // readyButton.setOnClickListener(v -> toggleReady());  // УДАЛЕНО
        startGameButton.setOnClickListener(v -> startGame());
        leaveRoomButton.setOnClickListener(v -> showLeaveConfirmation());
    }

    private void setupRoomListener() {
        showLoading(true);

        roomListener = db.collection("rooms").document(roomId)
                .addSnapshotListener((snapshot, error) -> {
                    showLoading(false);

                    if (error != null) {
                        Log.e("ROOM", "Ошибка слушателя комнаты", error);
                        Toast.makeText(RoomActivity.this, "Ошибка обновления комнаты", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (snapshot != null && snapshot.exists()) {
                        currentRoom = snapshot.toObject(Room.class);
                        if (currentRoom != null) {
                            currentRoom.setId(snapshot.getId());
                            updateUI();
                        }
                    } else {
                        showRoomDeletedDialog();
                    }
                });
    }

    private void updateUI() {
        if (currentRoom == null) return;

        // ==== ПРОВЕРКА НАЧАЛА ИГРЫ ====
        if ("playing".equals(currentRoom.getStatus()) && !isFinishing()) {
            Log.d("ROOM", "🎮 ИГРА НАЧАЛАСЬ! Переходим в GameActivity для игрока: " + currentUser.getUid());

            isHost = currentRoom.getCreatorId() != null &&
                    currentRoom.getCreatorId().equals(currentUser.getUid());

            Intent intent = new Intent(RoomActivity.this, GameActivity.class);
            intent.putExtra("roomId", roomId);
            intent.putExtra("isHost", isHost);
            intent.putExtra("roomName", currentRoom.getName());

            startActivity(intent);
            return;
        }
        // =============================

        roomNameText.setText(currentRoom.getName());
        roomCodeText.setText("Код: " + currentRoom.getCode());
        playersCountText.setText("Игроков: " + currentRoom.getCurrentPlayers() + "/" + currentRoom.getMaxPlayers());

        isHost = currentRoom.getCreatorId() != null &&
                currentRoom.getCreatorId().equals(currentUser.getUid());
        isReady = currentRoom.isReady(currentUser.getUid());
        loadPlayers();
        updateButtons();
    }

    private void loadPlayers() {
        if (currentRoom == null || currentRoom.getParticipants() == null) return;

        Log.d("ROOM_DEBUG", "=== ЗАГРУЗКА ИГРОКОВ ===");
        Log.d("ROOM_DEBUG", "Participants из БД: " + currentRoom.getParticipants());

        playersList.clear();

        for (String userId : currentRoom.getParticipants()) {
            db.collection("users").document(userId)
                    .get()
                    .addOnSuccessListener(documentSnapshot -> {
                        String nickname;
                        if (documentSnapshot.exists()) {
                            nickname = documentSnapshot.getString("nickname");
                            if (nickname == null) nickname = "Игрок";
                        } else {
                            nickname = "Игрок";
                        }

                        String photoBase64 = documentSnapshot.getString("photoBase64");

                        Player player = new Player(userId, nickname);
                        player.setHost(userId.equals(currentRoom.getCreatorId()));
                        player.setReady(currentRoom.isReady(userId));
                        if (photoBase64 != null) player.setPhotoUrl(photoBase64);

                        boolean exists = false;
                        for (Player p : playersList) {
                            if (p.getId().equals(userId)) {
                                exists = true;
                                break;
                            }
                        }

                        if (!exists) {
                            playersList.add(player);
                            Log.d("ROOM_DEBUG", "Добавлен игрок: " + nickname + " (готов: " + player.isReady() + ")");
                        }

                        playersAdapter.setPlayers(playersList);
                        updateButtons();
                    })
                    .addOnFailureListener(e -> {
                        Log.e("ROOM_DEBUG", "Ошибка загрузки игрока " + userId, e);

                        Player player = new Player(userId, "Игрок");
                        player.setHost(userId.equals(currentRoom.getCreatorId()));
                        player.setReady(currentRoom.isReady(userId));

                        boolean exists = false;
                        for (Player p : playersList) {
                            if (p.getId().equals(userId)) {
                                exists = true;
                                break;
                            }
                        }

                        if (!exists) {
                            playersList.add(player);
                        }

                        playersAdapter.setPlayers(playersList);
                        updateButtons();
                    });
        }

        if (currentRoom.getParticipants().isEmpty()) {
            playersList.clear();
            playersAdapter.setPlayers(playersList);
            updateButtons();
        }
    }

    private void updateButtons() {
        // readyButton больше не используется

        if (isHost) {
            // ХОСТ: показываем кнопку "НАЧАТЬ" и компактную кнопку выхода
            startGameButton.setVisibility(View.VISIBLE);

            // Меняем вес кнопок
            LinearLayout.LayoutParams startParams = (LinearLayout.LayoutParams) startGameButton.getLayoutParams();
            startParams.weight = 1.6f; // 80% от 2.0 = 1.6
            startGameButton.setLayoutParams(startParams);

            LinearLayout.LayoutParams leaveParams = (LinearLayout.LayoutParams) leaveRoomButton.getLayoutParams();
            leaveParams.weight = 0.4f; // 20% от 2.0 = 0.4
            leaveRoomButton.setLayoutParams(leaveParams);

            // Обновляем текст кнопки выхода для хоста (только крестик)
            leaveRoomButton.setText("✕");

            // Обновляем состояние кнопки "НАЧАТЬ"
            int playerCount = currentRoom.getParticipants().size();
            int readyCount = 0;
            for (Player player : playersList) {
                if (player.isReady()) readyCount++;
            }

            Log.d("ROOM_DEBUG", "Игроков: " + playerCount);
            Log.d("ROOM_DEBUG", "Готовы: " + readyCount);

            if (playerCount < 2) {
                startGameButton.setText("Нужно минимум 2 игрока (" + playerCount + "/2)");
                startGameButton.setEnabled(false);
            } else if (readyCount < playerCount) {
                startGameButton.setText("Ожидание готовности (" + readyCount + "/" + playerCount + ")");
                startGameButton.setEnabled(false);
            } else {
                startGameButton.setText("НАЧАТЬ");
                startGameButton.setEnabled(true);
            }

        } else {
            // ОБЫЧНЫЙ ИГРОК: только кнопка выхода на всю ширину
            startGameButton.setVisibility(View.GONE);

            // Растягиваем кнопку выхода на всю ширину
            LinearLayout.LayoutParams leaveParams = (LinearLayout.LayoutParams) leaveRoomButton.getLayoutParams();
            leaveParams.weight = 2.0f; // Вся ширина
            leaveRoomButton.setLayoutParams(leaveParams);

            // Обновляем текст кнопки выхода для обычного игрока
            leaveRoomButton.setText("✕ ПОКИНУТЬ КОМНАТУ");
        }
    }

    private void toggleReady() {
        if (currentRoom == null) return;

        showLoading(true);

        boolean newReadyStatus = !isReady;
        currentRoom.setReady(currentUser.getUid(), newReadyStatus);

        db.collection("rooms").document(roomId)
                .update("readyStatus", currentRoom.getReadyStatus())
                .addOnSuccessListener(aVoid -> {
                    showLoading(false);
                    isReady = newReadyStatus;
                    updateButtons();
                    Toast.makeText(this,
                            isReady ? "Вы готовы к игре" : "Вы не готовы",
                            Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Toast.makeText(this, "Ошибка обновления статуса", Toast.LENGTH_SHORT).show();
                    Log.e("ROOM", "Error updating ready status", e);
                });
    }

    private void startGame() {
        if (!isHost || currentRoom == null) return;

        int playerCount = playersList.size();

        if (playerCount < 2) {
            Toast.makeText(this, "Нужно минимум 2 игрока для начала игры", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!currentRoom.areAllPlayersReady()) {
            Toast.makeText(this, "Не все игроки готовы", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);

        // ===== РАСПРЕДЕЛЕНИЕ РОЛЕЙ =====
        List<String> participants = new ArrayList<>(currentRoom.getParticipants());
        Map<String, String> roles = new HashMap<>();
        Map<String, Object> playersMap = new HashMap<>();  // НОВОЕ: для хранения alive статуса

        java.util.Collections.shuffle(participants);

        if (playerCount == 2) {
            roles.put(participants.get(0), "mafia");
            roles.put(participants.get(1), "civilian");

            // НОВОЕ: заполняем playersMap
            for (String userId : participants) {
                Map<String, Object> playerData = new HashMap<>();
                playerData.put("alive", true);
                playerData.put("role", roles.get(userId));
                playerData.put("name", getPlayerNickname(userId));
                String photoBase64a = getPlayerPhotoUrl(userId);
                if (photoBase64a != null) playerData.put("photoBase64", photoBase64a);
                playersMap.put(userId, playerData);
            }

            Log.d("GAME", "2 игрока: мафия и мирный");
        } else {
            int mafiaCount = getMafiaCount(playerCount);
            Log.d("GAME", "Всего игроков: " + playerCount + ", Мафия: " + mafiaCount);

            int index = 0;

            for (int i = 0; i < mafiaCount; i++) {
                roles.put(participants.get(index), "mafia");
                index++;
            }

            if (index < participants.size()) {
                roles.put(participants.get(index), "sheriff");
                index++;
            }

            if (index < participants.size()) {
                roles.put(participants.get(index), "doctor");
                index++;
            }

            for (; index < participants.size(); index++) {
                roles.put(participants.get(index), "civilian");
            }

            // НОВОЕ: заполняем playersMap
            for (String userId : participants) {
                Map<String, Object> playerData = new HashMap<>();
                playerData.put("alive", true);
                playerData.put("role", roles.get(userId));
                playerData.put("name", getPlayerNickname(userId));
                String photoBase64b = getPlayerPhotoUrl(userId);
                if (photoBase64b != null) playerData.put("photoBase64", photoBase64b);
                playersMap.put(userId, playerData);
            }
        }

        Log.d("GAME", "Роли распределены: " + roles.toString());

        // ===== СОЗДАЕМ ПОЛНЫЙ ДОКУМЕНТ ИГРЫ =====
        // ВАЖНО: роль каждого игрока хранится ТОЛЬКО внутри players.<uid>.role.
        // Раньше она дублировалась ещё и в отдельной карте "roles" — это два источника
        // истины, которые могли разойтись. Теперь источник истины один.
        Map<String, Object> gameData = new HashMap<>();
        gameData.put("players", playersMap);                       // Состояние игроков (alive/role/name)
        gameData.put("phase", "night");                            // Текущая фаза
        gameData.put("nightStage", "mafia");                       // Текущая ночная стадия
        gameData.put("dayNumber", 1);                              // Номер дня
        gameData.put("status", "active");                          // Статус игры
        gameData.put("dayVotes", new HashMap<String, String>());

        // Ночные действия (пустые)
        Map<String, Object> nightActions = new HashMap<>();
        nightActions.put("mafia", new HashMap<String, String>());  // Голоса мафии
        nightActions.put("doctor", null);                          // Кого спасает доктор
        nightActions.put("sheriff", null);                         // Кого проверяет шериф
        gameData.put("nightActions", nightActions);

        gameData.put("createdAt", com.google.firebase.Timestamp.now());
        // =======================================

        db.collection("games").document(roomId)
                .set(gameData)
                .addOnSuccessListener(aVoid -> {
                    Log.d("GAME", "✅ Роли сохранены в Firestore");

                    db.collection("rooms").document(roomId)
                            .update("status", "playing")
                            .addOnSuccessListener(aVoid2 -> {
                                showLoading(false);
                                Log.d("GAME", "✅ Игра началась, статус обновлен");

                                Intent intent = new Intent(RoomActivity.this, GameActivity.class);
                                intent.putExtra("roomId", roomId);
                                intent.putExtra("isHost", isHost);
                                startActivity(intent);
                            })
                            .addOnFailureListener(e -> {
                                showLoading(false);
                                Log.e("GAME", "❌ Ошибка обновления статуса", e);
                                Toast.makeText(this, "Ошибка запуска игры", Toast.LENGTH_SHORT).show();
                            });
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Log.e("GAME", "❌ Ошибка сохранения ролей", e);
                    Toast.makeText(this, "Ошибка при создании ролей", Toast.LENGTH_SHORT).show();
                });
    }

    private String getPlayerNickname(String userId) {
        for (Player player : playersList) {
            if (player.getId().equals(userId)) {
                return player.getName();
            }
        }
        return "Игрок";
    }

    private String getPlayerPhotoUrl(String userId) {
        for (Player player : playersList) {
            if (player.getId().equals(userId)) {
                return player.getPhotoUrl();
            }
        }
        return null;
    }


    private int getMafiaCount(int totalPlayers) {
        if (totalPlayers >= 10) return 3;
        if (totalPlayers >= 8) return 2;
        if (totalPlayers >= 6) return 2;
        if (totalPlayers >= 4) return 1;
        return 1;
    }

    private void leaveRoom() {
        if (currentRoom == null || currentUser == null) return;

        showLoading(true);

        currentRoom.removeParticipant(currentUser.getUid());

        if (isHost && currentRoom.getParticipants() != null && !currentRoom.getParticipants().isEmpty()) {
            String newHostId = currentRoom.getParticipants().get(0);
            currentRoom.setCreatorId(newHostId);

            db.collection("users").document(newHostId)
                    .get()
                    .addOnSuccessListener(documentSnapshot -> {
                        String newHostName = documentSnapshot.getString("nickname");
                        if (newHostName == null) newHostName = "Игрок";
                        currentRoom.setCreatorName(newHostName);
                        updateRoomAfterLeave();
                    })
                    .addOnFailureListener(e -> {
                        currentRoom.setCreatorName("Игрок");
                        updateRoomAfterLeave();
                    });
        } else {
            updateRoomAfterLeave();
        }
    }

    private void updateRoomAfterLeave() {
        db.collection("rooms").document(roomId)
                .set(currentRoom)
                .addOnSuccessListener(aVoid -> {
                    showLoading(false);
                    finish();
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Toast.makeText(this, "Ошибка при выходе", Toast.LENGTH_SHORT).show();
                    Log.e("ROOM", "Error leaving room", e);
                });
    }

    private void showLeaveConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Покинуть комнату")
                .setMessage("Вы уверены, что хотите покинуть комнату?")
                .setPositiveButton("Да", (dialog, which) -> leaveRoom())
                .setNegativeButton("Нет", null)
                .show();
    }

    private void showRoomDeletedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Комната удалена")
                .setMessage("Создатель удалил эту комнату")
                .setPositiveButton("ОК", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        // readyButton.setEnabled(!show);  // УДАЛЕНО
        leaveRoomButton.setEnabled(!show);
        if (isHost) {
            startGameButton.setEnabled(!show);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (roomListener != null) {
            roomListener.remove();
        }
    }

    @Override
    public void onBackPressed() {
        showLeaveConfirmation();
    }
}