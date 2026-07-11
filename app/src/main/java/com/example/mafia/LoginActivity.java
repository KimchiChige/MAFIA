package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private EditText emailEditText, passwordEditText;
    private Button loginButton, registerButton;
    private ProgressBar progressBar;
    private TextView switchToRegister;
    private TextInputLayout nicknameLayout;
    private TextInputEditText nicknameEditText;

    private boolean isRegisterMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            goToLobby();
            return;
        }

        initViews();
        setupClickListeners();
        showLoginMode();
    }

    private void initViews() {
        emailEditText = findViewById(R.id.emailEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        loginButton = findViewById(R.id.loginButton);
        registerButton = findViewById(R.id.registerButton);
        progressBar = findViewById(R.id.progressBar);
        switchToRegister = findViewById(R.id.switchToRegister);
        nicknameLayout = findViewById(R.id.nicknameLayout);
        nicknameEditText = findViewById(R.id.nicknameEditText);

        android.widget.TextView mafiaTitle = findViewById(R.id.mafiaTitle);
        BloodDripView bloodDrip = findViewById(R.id.bloodDripTitle);
        if (mafiaTitle != null && bloodDrip != null) {
            mafiaTitle.post(() -> {
                bloodDrip.configure(mafiaTitle.getHeight() * 0.85f, mafiaTitle.getWidth());
                bloodDrip.startDripping();
            });
        }
    }

    private void setupClickListeners() {
        loginButton.setOnClickListener(v -> {
            String email = emailEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString().trim();

            if (isRegisterMode) {
                String nickname = nicknameEditText.getText().toString().trim();
                if (validateRegisterInput(email, password, nickname)) {
                    registerUser(email, password, nickname);
                }
            } else {
                if (validateLoginInput(email, password)) {
                    loginUser(email, password);
                }
            }
        });

        registerButton.setOnClickListener(v -> {
            String email = emailEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString().trim();
            String nickname = nicknameEditText.getText().toString().trim();

            if (validateRegisterInput(email, password, nickname)) {
                registerUser(email, password, nickname);
            }
        });

        switchToRegister.setOnClickListener(v -> {
            if (isRegisterMode) {
                showLoginMode();
            } else {
                showRegisterMode();
            }
        });
    }

    private void showLoginMode() {
        isRegisterMode = false;
        nicknameLayout.setVisibility(View.GONE);
        registerButton.setVisibility(View.GONE);
        switchToRegister.setText("Нет аккаунта? Зарегистрироваться");
        loginButton.setText("ВОЙТИ");
    }

    private void showRegisterMode() {
        isRegisterMode = true;
        nicknameLayout.setVisibility(View.VISIBLE);
        registerButton.setVisibility(View.VISIBLE);
        switchToRegister.setText("Уже есть аккаунт? Войти");
        loginButton.setText("СОЗДАТЬ АККАУНТ");
    }

    private boolean validateLoginInput(String email, String password) {
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Введите email");
            emailEditText.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Введите пароль");
            passwordEditText.requestFocus();
            return false;
        }

        if (password.length() < 6) {
            passwordEditText.setError("Пароль должен быть минимум 6 символов");
            passwordEditText.requestFocus();
            return false;
        }

        return true;
    }

    private boolean validateRegisterInput(String email, String password, String nickname) {
        if (TextUtils.isEmpty(email)) {
            emailEditText.setError("Введите email");
            emailEditText.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("Введите пароль");
            passwordEditText.requestFocus();
            return false;
        }

        if (password.length() < 6) {
            passwordEditText.setError("Пароль должен быть минимум 6 символов");
            passwordEditText.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(nickname)) {
            nicknameLayout.setError("Введите никнейм");
            nicknameEditText.requestFocus();
            return false;
        }

        if (nickname.length() < 3) {
            nicknameLayout.setError("Никнейм должен быть минимум 3 символа");
            nicknameEditText.requestFocus();
            return false;
        }

        return true;
    }

    private void loginUser(String email, String password) {
        showLoading(true);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    showLoading(false);

                    if (task.isSuccessful()) {
                        Toast.makeText(LoginActivity.this, "Вход выполнен!", Toast.LENGTH_SHORT).show();
                        goToLobby();
                    } else {
                        Toast.makeText(LoginActivity.this,
                                "Ошибка входа: " + task.getException().getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void registerUser(String email, String password, String nickname) {
        showLoading(true);

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    showLoading(false);

                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        createUserDocument(user.getUid(), email, nickname);

                        Toast.makeText(LoginActivity.this,
                                "Регистрация успешна!", Toast.LENGTH_SHORT).show();
                        goToLobby();
                    } else {
                        Toast.makeText(LoginActivity.this,
                                "Ошибка регистрации: " + task.getException().getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void createUserDocument(String userId, String email, String nickname) {
        Map<String, Object> user = new HashMap<>();
        user.put("email", email);
        user.put("nickname", nickname);
        user.put("createdAt", new Date());
        user.put("wins", 0);
        user.put("totalGames", 0);
        user.put("level", 1);
        user.put("diamonds", 500L);
        user.put("diamondsInitialized", true);
        user.put("perk_shield", 0L);
        user.put("perk_selfheal", 0L);
        user.put("perk_invisible", 0L);
        user.put("isPremium",           false);
        user.put("premiumDailyLastClaim", 0L);
        user.put("premiumRoleUsesLeft",   3L);
        user.put("premiumRoleUsesDate",   "");
        user.put("premiumChosenRole",     "");
        // Кастомизация (по умолчанию)
        user.put("isPremium",           false);
        user.put("premiumDailyLastClaim", 0L);
        user.put("premiumRoleUsesLeft",   3L);
        user.put("premiumRoleUsesDate",   "");
        user.put("premiumChosenRole",     "");
        // Кастомизация (по умолчанию)
        user.put("cardBorderColor",  "#8B0000");
        user.put("avatarBadge",      "👑");       // теперь это сам эмодзи, а не название
        user.put("nicknameColor",    "#FFFFFF");
        user.put("cardBackground",   "#1A1A1A");   // hex цвет ИЛИ "img:<base64>" для фото
        user.put("cardBgOpacity",    100L);        // 0..100, прозрачность фото-фона
        user.put("trialGameAvailable", true);

        db.collection("users").document(userId)
                .set(user)
                .addOnSuccessListener(aVoid ->
                        Log.d("LoginActivity", "Профиль создан для: " + nickname))
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Ошибка создания профиля", Toast.LENGTH_SHORT).show();
                    Log.e("LoginActivity", "Ошибка создания профиля", e);
                });
    }


    private void goToLobby() {
        Intent intent = new Intent(LoginActivity.this, LobbyActivity.class);
        startActivity(intent);
        finish();
    }

    private void showLoading(boolean isLoading) {
        if (isLoading) {
            progressBar.setVisibility(View.VISIBLE);
            loginButton.setEnabled(false);
            registerButton.setEnabled(false);
            switchToRegister.setEnabled(false);
        } else {
            progressBar.setVisibility(View.GONE);
            loginButton.setEnabled(true);
            registerButton.setEnabled(true);
            switchToRegister.setEnabled(true);
        }
    }
}