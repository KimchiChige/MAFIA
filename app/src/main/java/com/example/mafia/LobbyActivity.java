package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.example.mafia.Adapters.RoomAdapter;
import com.example.mafia.classes.Room;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LobbyActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private TextView nicknameText, noRoomsText, userStatsText;
    private Button createRoomButton, joinRoomButton;
    private RecyclerView roomsRecyclerView;
    private ProgressBar progressBar;
    private TextView logoutButton;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ImageView userAvatarImage;

    private final ActivityResultLauncher<Intent> profileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                // Перезагружаем данные после возврата из профиля (обновляем ник и фото)
                loadUserData();
            });

    private RoomAdapter roomAdapter;
    private List<Room> roomList = new ArrayList<>();
    private ListenerRegistration roomsListener;
    private static final int REQUEST_CREATE_ROOM = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lobby);

        checkFirebaseConnection();

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            goToLogin();
            return;
        }

        initViews();
        setupClickListeners();
        loadUserData();
        testFirestoreConnection();
    }

    private void checkFirebaseConnection() {
        Log.d("FIREBASE_CONNECTION", "Firebase Auth инициализирован");
    }

    private void testFirestoreConnection() {
        // Убрано: отладочная запись в _test/connection больше не нужна
    }

    private void initViews() {
        nicknameText = findViewById(R.id.nicknameText);
        userStatsText = findViewById(R.id.userStatsText);
        createRoomButton = findViewById(R.id.createRoomButton);
        joinRoomButton = findViewById(R.id.joinRoomButton);
        logoutButton = findViewById(R.id.logoutButton);
        roomsRecyclerView = findViewById(R.id.roomsRecyclerView);
        noRoomsText = findViewById(R.id.noRoomsText);
        progressBar = findViewById(R.id.progressBar);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        if (nicknameText == null) {
            TextView welcomeText = findViewById(R.id.nicknameText);
            if (welcomeText != null) {
                welcomeText.setVisibility(View.VISIBLE);
                nicknameText = welcomeText;
            } else {
                nicknameText = new TextView(this);
                Log.e("LOBBY", "Не найден nicknameText или welcomeText в разметке!");
            }
        }

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> setupRoomsRealtimeListener());
            swipeRefreshLayout.setColorSchemeResources(
                    android.R.color.holo_red_dark,
                    android.R.color.holo_green_dark,
                    android.R.color.holo_blue_dark
            );
        }

        roomAdapter = new RoomAdapter();
        roomsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        roomsRecyclerView.setAdapter(roomAdapter);

        // Клик по кнопке "Присоединиться" в карточке — с проверкой на приватность
        roomAdapter.setOnJoinClickListener(room -> handleRoomJoin(room));
        roomAdapter.setOnRoomDeleteClickListener(room -> deleteRoom(room.getId(), room.getCreatorId()));
    }

    private void setupRoomsRealtimeListener() {
        if (roomsListener != null) {
            roomsListener.remove();
            roomsListener = null;
        }

        showLoading(true);

        roomsListener = db.collection("rooms")
                .whereEqualTo("status", "waiting")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot querySnapshot,
                                        @Nullable FirebaseFirestoreException error) {
                        showLoading(false);
                        if (swipeRefreshLayout != null) {
                            swipeRefreshLayout.setRefreshing(false);
                        }

                        if (error != null) {
                            Log.e("FIREBASE", "Ошибка слушателя комнат", error);
                            Toast.makeText(LobbyActivity.this,
                                    "Ошибка обновления комнат", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (querySnapshot != null) {
                            updateRoomsList(querySnapshot);
                        }
                    }
                });
    }

    private void updateRoomsList(QuerySnapshot querySnapshot) {
        roomList.clear();

        for (QueryDocumentSnapshot document : querySnapshot) {
            try {
                Room room = document.toObject(Room.class);
                room.setId(document.getId());

                if (room.getCreatorName() == null || room.getCreatorName().isEmpty()) {
                    room.setCreatorName("Игрок");
                }

                roomList.add(room);
            } catch (Exception e) {
                Log.e("FIREBASE", "Ошибка парсинга комнаты", e);
            }
        }

        roomAdapter.setRooms(roomList);

        if (roomList.isEmpty()) {
            noRoomsText.setVisibility(View.VISIBLE);
            roomsRecyclerView.setVisibility(View.GONE);
        } else {
            noRoomsText.setVisibility(View.GONE);
            roomsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void joinRoom(String roomId) {
        showLoading(true);

        db.runTransaction(transaction -> {

            DocumentSnapshot snapshot = transaction.get(
                    db.collection("rooms").document(roomId)
            );

            if (!snapshot.exists()) {
                throw new RuntimeException("Комната не найдена");
            }

            Room room = snapshot.toObject(Room.class);

            if (room == null) {
                throw new RuntimeException("Ошибка чтения комнаты");
            }

            if (room.getParticipants().size() >= room.getMaxPlayers()) {
                throw new RuntimeException("Комната заполнена");
            }

            if (room.getParticipants().contains(currentUser.getUid())) {
                return room;
            }

            room.addParticipant(currentUser.getUid());

            transaction.set(
                    db.collection("rooms").document(roomId),
                    room
            );

            return room;

        }).addOnSuccessListener(room -> {

            showLoading(false);

            Intent intent = new Intent(LobbyActivity.this, RoomActivity.class);
            intent.putExtra("roomId", roomId);
            startActivity(intent);

        }).addOnFailureListener(e -> {

            showLoading(false);

            Toast.makeText(
                    LobbyActivity.this,
                    e.getMessage(),
                    Toast.LENGTH_SHORT
            ).show();

            Log.e("JOIN_ROOM", "Transaction error", e);
        });
    }

    private void setupClickListeners() {
        userAvatarImage = findViewById(R.id.userAvatarImage);
        createRoomButton.setOnClickListener(v -> createNewRoom());
        joinRoomButton.setOnClickListener(v -> joinRoomWithCode());
        logoutButton.setOnClickListener(v -> logoutUser());

        // Аватар открывает экран профиля
        if (userAvatarImage != null) {
            userAvatarImage.setOnClickListener(v -> openProfile());
        }
    }

    private void loadUserData() {
        if (currentUser != null) {
            db.collection("users").document(currentUser.getUid())
                    .get()
                    .addOnSuccessListener(documentSnapshot -> {
                        if (documentSnapshot.exists()) {
                            String nickname = documentSnapshot.getString("nickname");
                            String email = documentSnapshot.getString("email");

                            if (email == null) {
                                email = currentUser.getEmail();
                            }

                            String displayName;
                            if (nickname != null && !nickname.trim().isEmpty()) {
                                displayName = nickname;
                                Log.d("NICKNAME", "Используем никнейм из Firestore: " + nickname);
                            } else if (email != null) {
                                displayName = email.split("@")[0];
                                Log.d("NICKNAME", "Используем email как никнейм: " + displayName);
                            } else {
                                displayName = "Игрок";
                            }

                            nicknameText.setText(displayName);
                            loadUserStats(documentSnapshot);

                            // Загружаем фото профиля из Base64
                            String photoBase64 = documentSnapshot.getString("photoBase64");
                            if (photoBase64 != null && !photoBase64.isEmpty() && userAvatarImage != null) {
                                byte[] photoBytes = android.util.Base64.decode(photoBase64, android.util.Base64.DEFAULT);
                                userAvatarImage.clearColorFilter();
                                Glide.with(LobbyActivity.this)
                                        .load(photoBytes)
                                        .transform(new CircleCrop())
                                        .placeholder(R.drawable.ic_player_avatar1)
                                        .error(R.drawable.ic_player_avatar1)
                                        .into(userAvatarImage);
                            }
                        } else {
                            createUserInFirestore();
                            String email = currentUser.getEmail();
                            String tempName = (email != null) ? email.split("@")[0] : "Игрок";
                            nicknameText.setText("Привет, " + tempName + "!");
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e("NICKNAME", "Ошибка загрузки пользователя", e);
                        String email = currentUser.getEmail();
                        String tempName = (email != null) ? email.split("@")[0] : "Игрок";
                        nicknameText.setText("Привет, " + tempName + "!");
                    });

            setupRoomsRealtimeListener();
        }
    }

    private void createUserInFirestore() {
        Map<String, Object> user = new HashMap<>();
        user.put("email", currentUser.getEmail());
        user.put("nickname", "");
        user.put("wins", 0);
        user.put("totalGames", 0);
        user.put("level", 1);
        user.put("createdAt", new Date());

        db.collection("users").document(currentUser.getUid())
                .set(user)
                .addOnSuccessListener(aVoid -> {
                    Log.d("NICKNAME", "Пользователь создан в Firestore");
                })
                .addOnFailureListener(e -> {
                    Log.e("NICKNAME", "Ошибка создания пользователя", e);
                });
    }

    private void loadUserStats(DocumentSnapshot documentSnapshot) {
        if (userStatsText != null) {
            Long wins = documentSnapshot.getLong("wins");
            Long totalGames = documentSnapshot.getLong("totalGames");
            Long level = documentSnapshot.getLong("level");

            if (wins != null && totalGames != null && totalGames > 0) {
                double winRate = (wins * 100.0) / totalGames;
                String stats = String.format("Уровень: %d | Побед: %d/%d (%.1f%%)",
                        level != null ? level : 1, wins, totalGames, winRate);
                userStatsText.setText(stats);
            } else if (wins != null && totalGames != null) {
                String stats = String.format("Уровень: %d | Побед: %d | Игр: %d",
                        level != null ? level : 1, wins, totalGames);
                userStatsText.setText(stats);
            } else {
                userStatsText.setText("Уровень: 1 | Нет статистики");
            }
        }
    }

    private void createNewRoom() {
        showLoading(true);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            showLoading(false);
            Toast.makeText(this, "Пользователь не авторизован", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("users").document(currentUser.getUid())
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    String creatorName;

                    if (documentSnapshot.exists()) {
                        String nickname = documentSnapshot.getString("nickname");
                        String email = documentSnapshot.getString("email");

                        if (nickname != null && !nickname.trim().isEmpty()) {
                            creatorName = nickname;
                        } else if (email != null) {
                            creatorName = email.split("@")[0];
                        } else {
                            creatorName = currentUser.getEmail() != null ?
                                    currentUser.getEmail().split("@")[0] : "Игрок";
                        }
                    } else {
                        creatorName = currentUser.getEmail() != null ?
                                currentUser.getEmail().split("@")[0] : "Игрок";
                    }

                    createRoomWithName(creatorName);
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    String creatorName = currentUser.getEmail() != null ?
                            currentUser.getEmail().split("@")[0] : "Игрок";
                    createRoomWithName(creatorName);
                });
    }

    private void createRoomWithName(String creatorName) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            showLoading(false);
            return;
        }

        Intent intent = new Intent(LobbyActivity.this, CreateRoomActivity.class);
        intent.putExtra("creatorName", creatorName);
        startActivityForResult(intent, REQUEST_CREATE_ROOM);
    }

    private void showLoading(boolean show) {
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        if (createRoomButton != null) {
            createRoomButton.setEnabled(!show);
        }
    }

    /** Нижняя кнопка "Присоединиться" — стилизованный диалог ввода кода */
    private void joinRoomWithCode() {
        showJoinCodeDialog("ВОЙТИ В КОМНАТУ", "Введите код публичной или приватной комнаты", null, null);
    }

    /**
     * Универсальный стилизованный диалог ввода кода.
     * @param title      Заголовок диалога
     * @param subtitle   Подсказка под заголовком
     * @param expectedCode  Если != null — проверяем введённый код против этого значения (для приватных комнат)
     * @param roomId     Если != null — joinRoom(roomId) при совпадении кода; иначе findAndJoinByCode(code)
     */
    private void showJoinCodeDialog(String title, String subtitle, String expectedCode, String roomId) {
        android.view.View dialogView = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_join_room, null);

        android.widget.TextView titleView    = dialogView.findViewById(R.id.dialogTitlee);
        android.widget.TextView subtitleView = dialogView.findViewById(R.id.dialogSubtitlee);
        com.google.android.material.textfield.TextInputEditText codeInput = dialogView.findViewById(R.id.codeInputt);
        com.google.android.material.button.MaterialButton confirmBtn = dialogView.findViewById(R.id.confirmButtonn);
        com.google.android.material.button.MaterialButton cancelBtn  = dialogView.findViewById(R.id.cancelButtonn);

        titleView.setText(title);
        subtitleView.setText(subtitle);

        // Диалог с прозрачным фоном, чтобы наш кастомный background отображался без белой рамки
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.MafiaDialog)
                .setView(dialogView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        confirmBtn.setOnClickListener(v -> {
            String code = codeInput.getText() != null
                    ? codeInput.getText().toString().trim().toUpperCase() : "";
            if (code.isEmpty()) {
                codeInput.setError("Введите код");
                return;
            }
            dialog.dismiss();

            if (expectedCode != null) {
                // Режим проверки кода приватной комнаты
                if (code.equals(expectedCode)) {
                    joinRoom(roomId);
                } else {
                    Toast.makeText(this, "❌ Неверный код доступа", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Режим поиска комнаты по коду
                findAndJoinByCode(code);
            }
        });

        // Автофокус на поле ввода
        dialog.setOnShowListener(d -> {
            codeInput.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(codeInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });

        dialog.show();
    }

    /** Ищет комнату по коду в Firestore и присоединяется */
    private void findAndJoinByCode(String code) {
        showLoading(true);
        db.collection("rooms")
                .whereEqualTo("code", code)
                .whereEqualTo("status", "waiting")
                .limit(1)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    showLoading(false);
                    if (querySnapshot.isEmpty()) {
                        Toast.makeText(this, "Комната с кодом" + code + "не найдена", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.google.firebase.firestore.DocumentSnapshot doc = querySnapshot.getDocuments().get(0);
                    com.example.mafia.classes.Room room = doc.toObject(com.example.mafia.classes.Room.class);
                    if (room == null) return;
                    room.setId(doc.getId());
                    joinRoom(room.getId());
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Toast.makeText(this, "Ошибка поиска: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    /** Вступление в комнату из карточки — с проверкой на приватность */
    private void handleRoomJoin(com.example.mafia.classes.Room room) {
        if (room.isPrivate()) {
            showJoinCodeDialog(
                    "🔒 ЗАКРЫТАЯ КОМНАТА",
                    "Код доступа для «" + room.getName() + "»",
                    room.getCode(),
                    room.getId()
            );
        } else {
            joinRoom(room.getId());
        }
    }

    private void logoutUser() {
        mAuth.signOut();
        Toast.makeText(this, "Выход выполнен", Toast.LENGTH_SHORT).show();
        goToLogin();
    }

    private void goToLogin() {
        Intent intent = new Intent(LobbyActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (roomsListener != null) {
            roomsListener.remove();
        }
    }

    private void deleteRoom(String roomId, String creatorId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || !user.getUid().equals(creatorId)) {
            Toast.makeText(this, "Только автор может удалить комнату", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Удаление комнаты");
        builder.setMessage("Вы уверены, что хотите удалить эту комнату?");

        builder.setPositiveButton("Удалить", (dialog, which) -> {
            FirebaseFirestore.getInstance()
                    .collection("rooms")
                    .document(roomId)
                    .delete()
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Комната удалена", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Ошибка удаления: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        Log.e("DELETE_ROOM", "Error: ", e);
                    });
        });

        builder.setNegativeButton("Отмена", (dialog, which) -> {
            dialog.dismiss();
        });

        builder.create().show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CREATE_ROOM) {
            showLoading(false);

            if (resultCode == RESULT_OK && data != null) {
                String roomId = data.getStringExtra("roomId");
                joinRoom(roomId);
            }
        }
    }

    private void openProfile() {
        Intent intent = new Intent(LobbyActivity.this, ProfileActivity.class);
        profileLauncher.launch(intent);
    }

}