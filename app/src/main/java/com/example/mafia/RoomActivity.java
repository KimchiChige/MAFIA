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

    private static final String TAG = "ROOM_ROLE_DEBUG";

    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private String roomId;
    private Room currentRoom;

    private TextView roomNameText, roomCodeText, playersCountText, hintText;
    private RecyclerView playersRecyclerView;
    private Button startGameButton, leaveRoomButton;
    private ProgressBar progressBar;
    private Button selectRoleButton;
    private boolean myIsPremiumInRoom = false;
    private boolean myTrialAvailableInRoom = false;
    private static final int REQ_ROLE_SELECT = 4002;

    private RoomPlayersAdapter playersAdapter;
    private List<Player> playersList = new ArrayList<>();
    private ListenerRegistration roomListener;
    private final List<ListenerRegistration> playerListeners = new ArrayList<>();

    private boolean isReady = false;
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
        hintText = findViewById(R.id.hintText);
        playersRecyclerView = findViewById(R.id.playersRecyclerView);
        startGameButton = findViewById(R.id.startGameButton);
        leaveRoomButton = findViewById(R.id.leaveRoomButton);
        progressBar = findViewById(R.id.progressBar);

        selectRoleButton = findViewById(R.id.selectRoleButton);
        if (selectRoleButton != null) {
            selectRoleButton.setVisibility(View.VISIBLE);   // видна всем
            updateSelectRoleButtonState();                   // текст/поведение зависят от статуса

            selectRoleButton.setOnClickListener(v -> {
                if (myIsPremiumInRoom || myTrialAvailableInRoom) {
                    startActivityForResult(
                            new Intent(RoomActivity.this, RoleSelectionActivity.class),
                            REQ_ROLE_SELECT);
                } else {
                    // Не Premium и пробник уже использован — показываем витрину покупки
                    showBuyPremiumFromRoom();
                }
            });
        }

        // Проверяем Premium статус и доступность пробного периода
        db.collection("users").document(currentUser.getUid()).get()
                .addOnSuccessListener(doc -> {
                    myIsPremiumInRoom = doc.exists() && Boolean.TRUE.equals(doc.getBoolean("isPremium"));

                    PremiumManager.hasTrialAvailable(db, currentUser.getUid(), available -> {
                        myTrialAvailableInRoom = available;
                        updateSelectRoleButtonState();
                    });
                });

        playersAdapter = new RoomPlayersAdapter(currentUser.getUid());
        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 2);
        playersRecyclerView.setLayoutManager(gridLayoutManager);
        playersRecyclerView.setAdapter(playersAdapter);

        playersAdapter.setOnPlayerReadyClickListener(player -> {
            toggleReady();
        });

        startGameButton.setOnClickListener(v -> startGame());
        leaveRoomButton.setOnClickListener(v -> showLeaveConfirmation());
    }

    private void updateSelectRoleButtonState() {
        if (selectRoleButton == null) return;

        if (myIsPremiumInRoom) {
            selectRoleButton.setText("👑 Выбрать роль заранее");
            selectRoleButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4A0080));
        } else if (myTrialAvailableInRoom) {
            selectRoleButton.setText("🎁 Выбрать роль (пробная попытка)");
            selectRoleButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1B7A3D));
        } else {
            selectRoleButton.setText("🔒 Выбрать роль заранее (Premium)");
            selectRoleButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF4A0080));
        }
    }

    /** Показывает витрину Premium прямо из комнаты ожидания. */
    private void showBuyPremiumFromRoom() {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_buy_premium);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        Button confirmBtn = dialog.findViewById(R.id.buyPremiumConfirmButton);
        Button cancelBtn  = dialog.findViewById(R.id.buyPremiumCancelButton);
        if (confirmBtn != null) {
            confirmBtn.setOnClickListener(v -> {
                Toast.makeText(this, "Оплата пока недоступна. Скоро!", Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        }
        if (cancelBtn != null) cancelBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
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

        for (ListenerRegistration reg : playerListeners) reg.remove();
        playerListeners.clear();
        playersList.clear();

        for (String userId : currentRoom.getParticipants()) {
            ListenerRegistration reg = db.collection("users").document(userId)
                    .addSnapshotListener((documentSnapshot, error) -> {
                        if (error != null || documentSnapshot == null) {
                            ensurePlayerInList(userId, "Игрок", null, false, null, null, null, null, 100,
                                    userId.equals(currentRoom.getCreatorId()),
                                    currentRoom.isReady(userId));
                            return;
                        }

                        String nickname = documentSnapshot.getString("nickname");
                        if (nickname == null || nickname.isEmpty()) nickname = "Игрок";
                        String photoBase64 = documentSnapshot.getString("photoBase64");

                        boolean isPremium = Boolean.TRUE.equals(documentSnapshot.getBoolean("isPremium"));
                        String border    = isPremium ? documentSnapshot.getString("cardBorderColor") : null;
                        String badge     = isPremium ? documentSnapshot.getString("avatarBadge")     : null;
                        String nickColor = isPremium ? documentSnapshot.getString("nicknameColor")   : null;
                        String cardBg    = isPremium ? documentSnapshot.getString("cardBackground")  : null;
                        Long   bgOpacL   = isPremium ? documentSnapshot.getLong("cardBgOpacity")      : null;
                        int    bgOpacity = bgOpacL != null ? bgOpacL.intValue() : 100;

                        ensurePlayerInList(userId, nickname, photoBase64, isPremium,
                                border, badge, nickColor, cardBg, bgOpacity,
                                userId.equals(currentRoom.getCreatorId()),
                                currentRoom.isReady(userId));
                    });
            playerListeners.add(reg);
        }

        if (currentRoom.getParticipants().isEmpty()) {
            playersList.clear();
            playersAdapter.setPlayers(playersList);
            updateButtons();
        }
    }

    /** Обновляет или добавляет игрока в список и уведомляет адаптер. */
    private void ensurePlayerInList(String userId, String nickname, String photoBase64,
                                    boolean isPremium, String border, String badge,
                                    String nickColor, String cardBg, int bgOpacity,
                                    boolean isHost, boolean isReady) {
        Player existing = null;
        for (Player p : playersList) {
            if (p.getId().equals(userId)) { existing = p; break; }
        }

        Player player = existing != null ? existing : new Player(userId, nickname);
        player.setName(nickname);
        player.setHost(isHost);
        player.setReady(isReady);
        if (photoBase64 != null) player.setPhotoUrl(photoBase64);
        player.setPremium(isPremium);
        if (border    != null && !border.isEmpty())    player.setCardBorderColor(border);
        if (badge     != null && !badge.isEmpty())     player.setAvatarBadge(badge);
        if (nickColor != null && !nickColor.isEmpty()) player.setNicknameColor(nickColor);
        if (cardBg    != null && !cardBg.isEmpty())    player.setCardBackground(cardBg);
        player.setCardBgOpacity(bgOpacity);

        if (existing == null) {
            playersList.add(player);
            Log.d("ROOM_DEBUG", "Добавлен: " + nickname + " premium=" + isPremium);
        }

        playersAdapter.setPlayers(new ArrayList<>(playersList));
        updateButtons();
    }

    private void updateButtons() {
        if (isHost) {
            startGameButton.setVisibility(View.VISIBLE);

            LinearLayout.LayoutParams startParams = (LinearLayout.LayoutParams) startGameButton.getLayoutParams();
            startParams.weight = 1;
            startGameButton.setLayoutParams(startParams);

            LinearLayout.LayoutParams leaveParams = (LinearLayout.LayoutParams) leaveRoomButton.getLayoutParams();
            leaveParams.weight = 1;
            leaveRoomButton.setLayoutParams(leaveParams);

            leaveRoomButton.setText("✕ ПОКИНУТЬ");

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
            startGameButton.setVisibility(View.GONE);

            LinearLayout.LayoutParams leaveParams = (LinearLayout.LayoutParams) leaveRoomButton.getLayoutParams();
            leaveParams.weight = 1;
            leaveRoomButton.setLayoutParams(leaveParams);

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

        List<String> participants = new ArrayList<>(currentRoom.getParticipants());
        Log.d(TAG, "startGame(): участники = " + participants);

        // ── Шаг 1: подгружаем СВЕЖИЕ профили всех участников напрямую с сервера ──
        // Source.SERVER гарантирует, что мы не попадём на локальный кэш Firestore,
        // который мог не успеть обновиться сразу после того как игрок сохранил
        // выбор роли (RoleSelectionActivity) — критично для быстрых стартов игры.
        List<com.google.android.gms.tasks.Task<com.google.firebase.firestore.DocumentSnapshot>> tasks =
                new ArrayList<>();
        for (String uid : participants) {
            tasks.add(db.collection("users").document(uid)
                    .get(com.google.firebase.firestore.Source.SERVER));
        }

        com.google.android.gms.tasks.Tasks.whenAllSuccess(tasks)
                .addOnSuccessListener(results -> {
                    Map<String, com.google.firebase.firestore.DocumentSnapshot> userDocs = new HashMap<>();
                    for (int i = 0; i < participants.size(); i++) {
                        com.google.firebase.firestore.DocumentSnapshot doc =
                                (com.google.firebase.firestore.DocumentSnapshot) results.get(i);
                        userDocs.put(participants.get(i), doc);

                        String uid = participants.get(i);
                        Boolean prem = doc.getBoolean("isPremium");
                        String chosenRole = doc.getString("premiumChosenRole");
                        String chosenDate = doc.getString("premiumRoleUsesDate");
                        Log.d(TAG, "Игрок " + uid
                                + " | isPremium=" + prem
                                + " | premiumChosenRole=" + chosenRole
                                + " | premiumRoleUsesDate=" + chosenDate
                                + " | today=" + todayDateString());
                    }
                    proceedStartGame(participants, playerCount, userDocs);
                })
                .addOnFailureListener(e -> {
                    Log.e("GAME", "Ошибка загрузки профилей перед стартом", e);
                    // Резервный путь: стартуем без Premium-данных, чтобы игра не сломалась
                    proceedStartGame(participants, playerCount, new HashMap<>());
                });
    }

    @SuppressWarnings("unchecked")
    private void proceedStartGame(List<String> participants, int playerCount,
                                  Map<String, com.google.firebase.firestore.DocumentSnapshot> userDocs) {

        Map<String, String> roles = new HashMap<>();
        Map<String, Object> playersMap = new HashMap<>();

        String today = todayDateString();

        // ── Шаг 2: сначала закрепляем premiumChosenRole за теми, у кого он есть ──
        // (только если premiumRoleUsesDate == today, т.е. выбор сделан сегодня —
        //  выбор роли действует один день, а не бессрочно)
        java.util.Set<String> reservedMafia   = new java.util.HashSet<>();
        java.util.Set<String> reservedDoctor  = new java.util.HashSet<>();
        java.util.Set<String> reservedSheriff = new java.util.HashSet<>();
        Map<String, String> chosenRoles = new HashMap<>();

        for (String uid : participants) {
            com.google.firebase.firestore.DocumentSnapshot doc = userDocs.get(uid);
            if (doc == null || !doc.exists()) {
                Log.d(TAG, "Игрок " + uid + ": документ не найден, пропускаем premium-выбор");
                continue;
            }

            String chosenDate = doc.getString("premiumRoleUsesDate");
            String chosenRole = doc.getString("premiumChosenRole");
            if (chosenRole == null || chosenRole.isEmpty()) {
                Log.d(TAG, "Игрок " + uid + ": роль не выбрана (пусто)");
                continue;
            }
            if (!today.equals(chosenDate)) {
                Log.d(TAG, "Игрок " + uid + ": дата не совпадает! today=" + today + " chosenDate=" + chosenDate);
                continue;
            }

            // ── ИСПРАВЛЕНО: раньше здесь была проверка isPremium == true, которая
            // полностью игнорировала пользователей с ПРОБНЫМ выбором роли
            // (useTrialRoleSelection пишет premiumChosenRole точно так же, как
            // обычный Premium-выбор, но isPremium у таких игроков остаётся false).
            // Сам факт, что chosenDate == today, уже подтверждает легитимность
            // выбора — он был сохранён через транзакцию PremiumManager, неважно
            // какую именно (useRoleSelection ИЛИ useTrialRoleSelection).

            Log.d(TAG, "Игрок " + uid + ": ЗАКРЕПЛЯЕМ роль " + chosenRole);
            chosenRoles.put(uid, chosenRole);
            if ("mafia".equals(chosenRole))   reservedMafia.add(uid);
            if ("doctor".equals(chosenRole))  reservedDoctor.add(uid);
            if ("sheriff".equals(chosenRole)) reservedSheriff.add(uid);
        }

        List<String> remaining = new ArrayList<>(participants);
        remaining.removeAll(chosenRoles.keySet());
        java.util.Collections.shuffle(remaining);

        int mafiaCount        = playerCount == 2 ? 1 : getMafiaCount(playerCount);
        int mafiaAssigned     = reservedMafia.size();
        boolean sheriffAssigned = !reservedSheriff.isEmpty();
        boolean doctorAssigned  = !reservedDoctor.isEmpty();

        roles.putAll(chosenRoles);

        int idx = 0;
        // Добиваем мафию случайными игроками из оставшихся
        while (mafiaAssigned < mafiaCount && idx < remaining.size()) {
            roles.put(remaining.get(idx), "mafia");
            mafiaAssigned++;
            idx++;
        }
        // Шериф (если ещё не назначен через premium)
        if (!sheriffAssigned && idx < remaining.size() && playerCount > 2) {
            roles.put(remaining.get(idx), "sheriff");
            idx++;
        }
        // Доктор (если ещё не назначен через premium)
        if (!doctorAssigned && idx < remaining.size() && playerCount > 2) {
            roles.put(remaining.get(idx), "doctor");
            idx++;
        }
        // Любовница (6+ игроков, 1 штука)
        if (idx < remaining.size() && playerCount >= 6) {
            roles.put(remaining.get(idx), "lover");
            idx++;
        }
        // Остальные — мирные
        for (; idx < remaining.size(); idx++) {
            roles.put(remaining.get(idx), "civilian");
        }

        // Если 2 игрока и кто-то выбрал роль через premium, второй автоматически мирный
        if (playerCount == 2) {
            for (String uid : participants) {
                if (!roles.containsKey(uid)) roles.put(uid, "civilian");
            }
        }

        Log.d(TAG, "ИТОГОВЫЕ роли: " + roles.toString());

        // ── Шаг 3: собираем players-карту с customization ────────────────────
        for (String userId : participants) {
            Map<String, Object> playerData = new HashMap<>();
            playerData.put("alive", true);
            playerData.put("role", roles.get(userId));
            playerData.put("name", getPlayerNickname(userId));
            String photoBase64 = getPlayerPhotoUrl(userId);
            if (photoBase64 != null) playerData.put("photoBase64", photoBase64);
            playerData.put("activePerk_shield", false);
            playerData.put("activePerk_selfheal", false);
            playerData.put("activePerk_invisible", false);
            playerData.put("resurrected", false);
            playerData.put("lastHeartbeat", System.currentTimeMillis());

            com.google.firebase.firestore.DocumentSnapshot doc = userDocs.get(userId);
            boolean isPremium = doc != null && doc.exists()
                    && Boolean.TRUE.equals(doc.getBoolean("isPremium"));
            playerData.put("isPremium", isPremium);
            if (isPremium && doc != null) {
                String border  = doc.getString("cardBorderColor");
                String badge   = doc.getString("avatarBadge");
                String nick    = doc.getString("nicknameColor");
                String cardBg  = doc.getString("cardBackground");
                Long   bgOpac  = doc.getLong("cardBgOpacity");
                playerData.put("cardBorderColor", border != null ? border : "#8B0000");
                // avatarBadge может быть намеренно пустой строкой (пользователь убрал бейдж) —
                // в этом случае НЕ подставляем корону, оставляем пусто
                playerData.put("avatarBadge",      badge  != null ? badge  : "");
                playerData.put("nicknameColor",     nick  != null ? nick  : "#FFFFFF");
                playerData.put("cardBackground",    cardBg != null ? cardBg : "#1A1A1A");
                playerData.put("cardBgOpacity",     bgOpac != null ? bgOpac : 100L);
            }

            playersMap.put(userId, playerData);
        }

        // ===== СОЗДАЕМ ПОЛНЫЙ ДОКУМЕНТ ИГРЫ =====
        Map<String, Object> gameData = new HashMap<>();
        gameData.put("players", playersMap);
        gameData.put("phase", "night");
        gameData.put("nightStage", "mafia");
        gameData.put("dayNumber", 1);
        gameData.put("status", "active");
        gameData.put("dayVotes", new HashMap<String, String>());

        Map<String, Object> nightActions = new HashMap<>();
        nightActions.put("mafia", new HashMap<String, String>());
        nightActions.put("doctor", null);
        nightActions.put("sheriff", null);
        nightActions.put("lover", null);
        gameData.put("nightActions", nightActions);

        gameData.put("createdAt", com.google.firebase.Timestamp.now());
        gameData.put("phaseStartAt", System.currentTimeMillis());
        gameData.put("votingManagerId", currentUser.getUid());

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
                    Toast.makeText(this, "Ошибка запуска игры", Toast.LENGTH_SHORT).show();
                });
    }

    private String todayDateString() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.MONTH)
                + "-" + c.get(java.util.Calendar.DAY_OF_MONTH);
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
        MafiaDialogs.confirm(this,
                "Покинуть комнату",
                "Вы уверены, что хотите покинуть комнату?",
                "ДА", "НЕТ",
                () -> leaveRoom(), null);
    }

    private void showRoomDeletedDialog() {
        AlertDialog dialog = MafiaDialogs.alert(this,
                "Комната удалена",
                "Создатель удалил эту комнату",
                "ОК",
                () -> finish());
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        leaveRoomButton.setEnabled(!show);
        if (isHost) {
            startGameButton.setEnabled(!show);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ROLE_SELECT && resultCode == RESULT_OK && data != null) {
            String chosenRole = data.getStringExtra("chosenRole");
            if (chosenRole != null && selectRoleButton != null) {
                selectRoleButton.setText("✅ Роль выбрана: " + roleLabel(chosenRole));
            }
            Log.d(TAG, "onActivityResult: выбрана роль на клиенте = " + chosenRole);
        }
    }

    private String roleLabel(String role) {
        switch (role) {
            case "mafia":    return "Мафия 🔫";
            case "doctor":   return "Доктор 💉";
            case "sheriff":  return "Шериф 🕵️";
            case "lover":    return "Любовница 💕";
            default:         return "Мирный 👤";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (roomListener != null) roomListener.remove();
        for (ListenerRegistration reg : playerListeners) reg.remove();
        playerListeners.clear();
    }

    @Override
    public void onBackPressed() {
        showLeaveConfirmation();
    }
}