package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.mafia.classes.Room;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import android.util.Log;

public class CreateRoomActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private EditText roomNameInput;
    private Button createButton;
    private ImageButton privacyToggle;
    private TextView privacyLabel;
    private ProgressBar progressBar;

    private String creatorName;
    private boolean isRoomPrivate = false; // false = публичная, true = приватная

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.create_room_activity);

        creatorName = getIntent().getStringExtra("creatorName");

        if (creatorName == null || creatorName.isEmpty()) {
            finish();
            return;
        }

        initFirebase();
        initViews();
        setupClickListeners();
    }

    private void initFirebase() {
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            finish();
        }
    }

    private void initViews() {
        roomNameInput = findViewById(R.id.roomNameInput);
        createButton = findViewById(R.id.createButton);
        privacyToggle = findViewById(R.id.privacyToggle);
        privacyLabel = findViewById(R.id.privacyLabel);
        progressBar = findViewById(R.id.progressBar);

        // Настройка EditText
        roomNameInput.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(25)
        });

        // Автофокус
        roomNameInput.requestFocus();

        // Устанавливаем начальное состояние
        updatePrivacyUI();
    }

    private void setupClickListeners() {
        // Кнопка "Создать комнату"
        createButton.setOnClickListener(v -> {
            String roomName = roomNameInput.getText().toString().trim();
            if (validateRoomName(roomName)) {
                createRoom(roomName);
            }
        });

        // Переключатель приватности
        privacyToggle.setOnClickListener(v -> {
            isRoomPrivate = !isRoomPrivate;
            updatePrivacyUI();
        });
    }

    private void updatePrivacyUI() {
        if (isRoomPrivate) {
            // Приватная комната
            privacyToggle.setImageResource(R.drawable.ic_close);
            privacyLabel.setText("Приватная комната");

            // Можно добавить небольшую анимацию или изменить цвет текста
            privacyLabel.setTextColor(getResources().getColor(R.color.light_text));

            Toast.makeText(this, "Комната будет приватной", Toast.LENGTH_SHORT).show();
        } else {
            // Публичная комната
            privacyToggle.setImageResource(R.drawable.lock_open_ic);
            privacyLabel.setText("Публичная комната");
            privacyLabel.setTextColor(getResources().getColor(R.color.light_text));

            //Toast.makeText(this, "Комната будет публичной", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean validateRoomName(String roomName) {
        if (roomName.isEmpty()) {
            showError("Название комнаты не может быть пустым");
            roomNameInput.setError("Введите название");
            return false;
        }

        if (roomName.length() < 3) {
            showError("Название должно быть от 3 символов");
            roomNameInput.setError("Слишком короткое название");
            return false;
        }

        if (roomName.length() > 25) {
            showError("Название не должно превышать 25 символов");
            roomNameInput.setError("Слишком длинное название");
            return false;
        }

        return true;
    }

    private void createRoom(String roomName) {
        showLoading(true);

        // Создаем комнату с учетом приватности
        Room newRoom = Room.createNewRoom(
                roomName,
                currentUser.getUid(),
                creatorName,
                10,  // Макс игроков
                isRoomPrivate  // Добавляем параметр приватности
        );

        // Если комната приватная, можно добавить пароль (опционально)
        if (isRoomPrivate) {
            // Здесь можно добавить логику для запроса пароля
            // newRoom.setPassword("пароль");
        }

        db.collection("rooms")
                .add(newRoom)
                .addOnSuccessListener(documentReference -> {
                    newRoom.setId(documentReference.getId());
                    showLoading(false);


                    // Возвращаем результат для LobbyActivity (onActivityResult)
                    Intent result = new Intent();
                    result.putExtra("roomId", documentReference.getId());
                    setResult(RESULT_OK, result);

                    String privacyText = isRoomPrivate ? "приватная" : "публичная";
                    Toast.makeText(this, String.format("Комната \"%s\" (%s) создана!", roomName, privacyText),
                            Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    showError("Ошибка при создании комнаты: " + e.getMessage());
                    Log.e("CREATE_ROOM", "Error: ", e);
                });
    }

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        createButton.setEnabled(!show);
        roomNameInput.setEnabled(!show);
        privacyToggle.setEnabled(!show);

        if (show) {
            createButton.setText("Создание...");
        } else {
            createButton.setText("СОЗДАТЬ КОМНАТУ");
        }
    }

    private void showError(String message) {
        Toast.makeText(this, "✗ " + message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}