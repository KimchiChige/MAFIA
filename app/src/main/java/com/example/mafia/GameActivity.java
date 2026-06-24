package com.example.mafia;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.mafia.Adapters.ChatAdapter;
import com.example.mafia.Adapters.PlayerActionAdapter;
import com.example.mafia.classes.ChatMessage;
import com.example.mafia.classes.Player;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GameActivity extends AppCompatActivity {

    private static final String TAG = "GAME";

    // ── Длительности фаз (сек) ──────────────────────────────────────
    private static final int MAFIA_STAGE_SECONDS = 30;
    private static final int DOCTOR_STAGE_SECONDS = 25;
    private static final int SHERIFF_STAGE_SECONDS = 25;
    private static final int DAY_DISCUSSION_SECONDS = 60;
    private static final int VOTING_SECONDS = 60;

    // ── Firebase ──────────────────────────────────────────────────
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private ListenerRegistration gameListener;

    // ── Состояние игры ────────────────────────────────────────────
    private String roomId;
    private String myRole;
    private boolean isHost;
    private boolean isAlive = true;
    private String currentPhase = "";
    private String nightStage = "mafia";
    private int currentDay = 1;
    private Map<String, Object> playersMap;

    // ── UI ───────────────────────────────────────────────────────
    private TextView roleText;
    private TextView phaseText;
    private TextView infoText;
    private TextView timerText;
    private TextView roleDisplayText;
    private RecyclerView playersRecyclerView;
    private Button actionButton;
    private Button startVotingButton;
    private ProgressBar progressBar;
    private LinearLayout gameControlsLayout;
    private LinearLayout roleRevealLayout;
    private LinearLayout nightWaitingLayout;
    private TextView nightWaitingText;
    private BloodDripView bloodDripRole;
    private BloodDripView bloodDripPhase;

    // ── Адаптер ───────────────────────────────────────────────────
    private PlayerActionAdapter playersAdapter;
    private List<Player> playersList = new ArrayList<>();

    // ── Чат ──────────────────────────────────────────────────────
    private android.widget.LinearLayout chatPanel;
    private com.google.android.material.floatingactionbutton.FloatingActionButton openChatButton;
    private androidx.recyclerview.widget.RecyclerView chatRecyclerView;
    private com.google.android.material.textfield.TextInputEditText chatInput;
    private ChatAdapter chatAdapter;
    private com.google.firebase.firestore.ListenerRegistration chatListener;
    private String myName = "Игрок";
    private String myPhotoBase64 = null;

    // ── Действие ──────────────────────────────────────────────────
    private String selectedTarget = null;
    private boolean hasActed = false;
    private CountDownTimer phaseTimer;

    // ── Флаги ─────────────────────────────────────────────────────
    private boolean nightResultShown = false;
    private boolean voteResultShown = false;
    private boolean gameEndHandled = false;
    private boolean votingTimerStarted = false;
    private boolean dayTimerStarted = false;
    private String timerForStage = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);

        roomId = getIntent().getStringExtra("roomId");
        isHost = getIntent().getBooleanExtra("isHost", false);

        if (roomId == null) {
            Toast.makeText(this, "Ошибка: игра не найдена", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        db = FirebaseFirestore.getInstance();
        currentUser = FirebaseAuth.getInstance().getCurrentUser();

        initViews();
        setupGameListener();
    }

    private void initViews() {
        roleText = findViewById(R.id.roleText);
        phaseText = findViewById(R.id.phaseText);
        infoText = findViewById(R.id.infoText);
        timerText = findViewById(R.id.timerText);
        roleDisplayText = findViewById(R.id.roleDisplayText);
        playersRecyclerView = findViewById(R.id.playersRecyclerView);
        actionButton = findViewById(R.id.actionButton);
        startVotingButton = findViewById(R.id.startVotingButton);
        progressBar = findViewById(R.id.progressBar);
        gameControlsLayout = findViewById(R.id.gameControlsLayout);
        roleRevealLayout = findViewById(R.id.roleRevealLayout);
        nightWaitingLayout = findViewById(R.id.nightWaitingLayout);
        nightWaitingText = findViewById(R.id.nightWaitingText);
        bloodDripRole = findViewById(R.id.bloodDripRole);
        bloodDripPhase = findViewById(R.id.bloodDripPhase);

        playersAdapter = new PlayerActionAdapter(currentUser.getUid());
        playersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        playersRecyclerView.setAdapter(playersAdapter);

        playersAdapter.setOnPlayerClickListener(player -> {
            if (!isAlive || hasActed || !canActInCurrentPhase()) return;
            selectedTarget = player.getId();
            playersAdapter.setSelectedPlayer(selectedTarget);
            infoText.setText("Выбран: " + player.getName());
            actionButton.setEnabled(true);
        });

        actionButton.setOnClickListener(v -> performAction());
        startVotingButton.setOnClickListener(v -> startVotingButtonClick());

        // ── Чат ──────────────────────────────────────────────────
        chatPanel = findViewById(R.id.chatPanel);
        openChatButton = findViewById(R.id.openChatButton);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        chatInput = findViewById(R.id.chatInput);

        chatAdapter = new ChatAdapter(currentUser.getUid());
        chatRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        chatRecyclerView.setAdapter(chatAdapter);

        openChatButton.setOnClickListener(v -> {
            chatPanel.setVisibility(View.VISIBLE);
            openChatButton.setVisibility(View.GONE);
            chatRecyclerView.scrollToPosition(chatAdapter.getItemCount() - 1);
        });

        findViewById(R.id.chatCloseButton).setOnClickListener(v -> {
            chatPanel.setVisibility(View.GONE);
            openChatButton.setVisibility(View.VISIBLE);
        });

        findViewById(R.id.chatSendButton).setOnClickListener(v -> sendChatMessage());

        // Загружаем имя и фото текущего игрока для чата
        db.collection("users").document(currentUser.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String nick = doc.getString("nickname");
                        myName = (nick != null && !nick.isEmpty()) ? nick : "Игрок";
                        myPhotoBase64 = doc.getString("photoBase64");
                    }
                });
    }

    private void startVotingButtonClick() {
        startVotingButton.setEnabled(false);
        GameTransactions.forceStartVoting(db, roomId, new GameTransactions.OnTxComplete() {
            @Override public void onSuccess() { /* UI обновится через слушатель */ }
            @Override public void onError(Exception e) {
                startVotingButton.setEnabled(true);
                Toast.makeText(GameActivity.this, "Ошибка запуска голосования", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // FIRESTORE LISTENER
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void setupGameListener() {
        progressBar.setVisibility(View.VISIBLE);

        gameListener = db.collection("games").document(roomId)
                .addSnapshotListener((snapshot, error) -> {
                    progressBar.setVisibility(View.GONE);

                    if (error != null) {
                        Log.e(TAG, "Ошибка слушателя игры", error);
                        return;
                    }
                    if (snapshot == null || !snapshot.exists()) return;

                    Map<String, Object> data = snapshot.getData();
                    if (data == null) return;

                    applyGameState(data);
                });
    }

    @SuppressWarnings("unchecked")
    private void applyGameState(Map<String, Object> data) {
        playersMap = (Map<String, Object>) data.get("players");

        // Роль читаем из единственного источника правды — players.<uid>.role
        String newMyRole = getRoleFromPlayers(playersMap, currentUser.getUid());
        myRole = newMyRole;
        roleDisplayText.setText(getRoleDisplay());
        if (roleRevealLayout.getVisibility() == View.VISIBLE) {
            showRoleRevealAnimation();
        }

        String newPhase = (String) data.get("phase");
        String newNightStage = (String) data.get("nightStage");
        Long day = (Long) data.get("dayNumber");

        if (newPhase != null && !newPhase.equals(currentPhase)) {
            resetPhaseState();
        }
        if (newNightStage != null && !newNightStage.equals(nightStage)) {
            hasActed = false;
            selectedTarget = null;
            cancelTimer();
            timerForStage = null;
        }

        currentPhase = newPhase != null ? newPhase : "";
        nightStage = newNightStage != null ? newNightStage : "mafia";
        currentDay = day != null ? day.intValue() : 1;

        updateAliveStatus();

        // Итог ночи (даже если этой же ночью игра закончилась) — показываем ПЕРЕД экраном победы
        Map<String, Object> nightResult = (Map<String, Object>) data.get("lastNightResult");
        boolean nightResultEndsGame = nightResult != null && nightResult.get("gameEndWinner") != null;
        if (nightResult != null && !nightResultShown
                && ("day".equals(currentPhase) || ("ended".equals(currentPhase) && nightResultEndsGame))) {
            showNightResultScreen(nightResult);
            return;
        }

        // Итог голосования (то же самое для дня)
        Map<String, Object> voteResult = (Map<String, Object>) data.get("lastVoteResult");
        boolean voteResultEndsGame = voteResult != null && voteResult.get("gameEndWinner") != null;
        if (voteResult != null && !voteResultShown
                && ("night".equals(currentPhase) || ("ended".equals(currentPhase) && voteResultEndsGame))) {
            showVoteResultScreen(voteResult);
            return;
        }

        if ("ended".equals(currentPhase)) {
            // Доходим сюда только если ни один экран-результат не должен показываться первым
            // (например, переподключение уже после того, как реакция была показана).
            if (!nightResultShown && !voteResultShown) {
                handleGameEnd(data);
            }
            return;
        }

        updateGameUI();
        updatePlayersList(playersMap);

        if ("night".equals(currentPhase)) {
            updateNightUI();
        } else if ("day".equals(currentPhase)) {
            updateDayDiscussionUI();
        } else if ("voting".equals(currentPhase)) {
            Map<String, Object> dayVotes = (Map<String, Object>) data.get("dayVotes");
            updateDayVotingUI(dayVotes);
        }
    }

    private String getRoleFromPlayers(Map<String, Object> players, String uid) {
        if (players == null || uid == null) return null;
        Object pObj = players.get(uid);
        if (pObj instanceof Map) {
            Object r = ((Map<String, Object>) pObj).get("role");
            if (r != null) {
                String s = String.valueOf(r).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private void resetPhaseState() {
        hasActed = false;
        selectedTarget = null;
        votingTimerStarted = false;
        dayTimerStarted = false;
        // Скрываем чат при смене фазы
        chatPanel.setVisibility(View.GONE);
        openChatButton.setVisibility(View.GONE);
        stopChatListener();
        if (playersAdapter != null) {
            playersAdapter.setSelectedPlayer(null);
            playersAdapter.setVoteCounts(new HashMap<>());
        }
    }

    private void updateAliveStatus() {
        if (playersMap == null || currentUser == null) return;
        Object meObj = playersMap.get(currentUser.getUid());
        if (!(meObj instanceof Map)) {
            isAlive = false;
        } else {
            isAlive = isAliveObject(((Map<String, Object>) meObj).get("alive"));
        }
    }

    // Универсальная проверка, считается ли объект "alive": поддерживает Boolean, Number и строковые представления
    private boolean isAliveObject(Object aliveObj) {
        if (aliveObj == null) return false;
        if (aliveObj instanceof Boolean) return (Boolean) aliveObj;
        if (aliveObj instanceof Number) return ((Number) aliveObj).intValue() != 0;
        String s = String.valueOf(aliveObj).trim().toLowerCase();
        if (s.isEmpty()) return false;
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private void updateGameUI() {
        String phaseDisplay = "";
        if ("night".equals(currentPhase)) phaseDisplay = "🌙 Ночь " + currentDay;
        else if ("day".equals(currentPhase)) phaseDisplay = "☀️ День " + currentDay;
        else if ("voting".equals(currentPhase)) phaseDisplay = "🗳️ Голосование";
        phaseText.setText(phaseDisplay);

        if (bloodDripPhase != null) {
            bloodDripPhase.setVisibility("night".equals(currentPhase) ? View.VISIBLE : View.GONE);
        }

        playersAdapter.setInteractionEnabled(isAlive);

        if (!isAlive) {
            infoText.setText("👁 Вы мертвы — режим наблюдателя");
            actionButton.setVisibility(View.GONE);
        }
    }

    private void updateNightUI() {
        int seconds = "mafia".equals(nightStage) ? MAFIA_STAGE_SECONDS
                : "doctor".equals(nightStage) ? DOCTOR_STAGE_SECONDS
                : SHERIFF_STAGE_SECONDS;
        // Таймер крутится у КАЖДОГО клиента (а не только у того, чья очередь), чтобы игра
        // не зависала, если у действующего игрока пропадёт связь.
        startPhaseTimerOnce(seconds);

        if (!isAlive) {
            nightWaitingLayout.setVisibility(View.VISIBLE);
            playersRecyclerView.setVisibility(View.GONE);
            actionButton.setVisibility(View.GONE);
            nightWaitingText.setText("🌑 Вы наблюдаете за ночью...");
            return;
        }

        boolean isMyStageTurn = false;

        if ("mafia".equals(nightStage)) {
            if ("mafia".equals(normalizeRole(myRole))) {
                showActionUI("🔫 Выберите жертву");
                isMyStageTurn = true;
            } else {
                showWaitingUI("🌑 Мафия вышла на охоту...");
            }
        } else if ("doctor".equals(nightStage)) {
            if ("doctor".equals(normalizeRole(myRole))) {
                showActionUI("💉 Кого спасти?");
                isMyStageTurn = true;
            } else {
                showWaitingUI("💊 Доктор выбирает...");
            }
        } else if ("sheriff".equals(nightStage)) {
            if ("sheriff".equals(normalizeRole(myRole))) {
                showActionUI("🕵️ Кого проверить?");
                isMyStageTurn = true;
            } else {
                showWaitingUI("🔍 Шериф ведёт расследование...");
            }
        }

        // Показываем кнопку подтвердить только тому, чья очередь и кто ещё не acted
        if (isMyStageTurn && !hasActed) {
            actionButton.setVisibility(View.VISIBLE);
        } else {
            actionButton.setVisibility(View.GONE);
        }
    }

    private void showActionUI(String hint) {
        nightWaitingLayout.setVisibility(View.GONE);
        playersRecyclerView.setVisibility(View.VISIBLE);
        if (!hasActed) {
            infoText.setText(hint);
            actionButton.setEnabled(selectedTarget != null);
        } else {
            infoText.setText("✅ Выбор сохранён");
        }
    }

    private void showWaitingUI(String message) {
        nightWaitingLayout.setVisibility(View.VISIBLE);
        playersRecyclerView.setVisibility(View.GONE);
        nightWaitingText.setText(message);
        actionButton.setVisibility(View.GONE);
    }

    /** Дневное обсуждение (после ночи, до голосования) — отдельный экран для ВСЕХ игроков. */
    private void updateDayDiscussionUI() {
        startDayTimerOnce(DAY_DISCUSSION_SECONDS);

        nightWaitingLayout.setVisibility(View.GONE);
        playersRecyclerView.setVisibility(View.VISIBLE);
        actionButton.setVisibility(View.GONE);

        if (isAlive) {
            infoText.setText("💬 Обсудите ситуацию. Голосование начнётся автоматически.");
        }

        startVotingButton.setVisibility(isHost ? View.VISIBLE : View.GONE);

        // Показываем кнопку открытия чата и запускаем слушатель
        openChatButton.setVisibility(View.VISIBLE);
        startChatListener();
    }

    private void updateDayVotingUI(Map<String, Object> dayVotes) {
        // Таймер голосования крутится у всех, чтобы "пропавший" игрок не блокировал игру навсегда.
        startVotingTimerOnce(VOTING_SECONDS);

        startVotingButton.setVisibility(View.GONE);
        nightWaitingLayout.setVisibility(View.GONE);
        playersRecyclerView.setVisibility(View.VISIBLE);

        if (!isAlive) {
            infoText.setText("👁 Наблюдаете за голосованием");
            actionButton.setVisibility(View.GONE);
        } else if (!hasActed) {
            infoText.setText("🗳️ Кого казнить?");
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setEnabled(selectedTarget != null);
        } else {
            infoText.setText("✅ Голос засчитан");
            actionButton.setVisibility(View.GONE);
        }

        Map<String, Integer> counts = new HashMap<>();
        if (dayVotes != null) {
            for (Object v : dayVotes.values()) {
                if (v != null) {
                    String id = v.toString();
                    counts.put(id, counts.getOrDefault(id, 0) + 1);
                }
            }
        }
        playersAdapter.setVoteCounts(counts);
    }

    // ─────────────────────────────────────────────────────────────
    // Отправка действий — через атомарные транзакции (GameTransactions)
    // ─────────────────────────────────────────────────────────────

    private void performAction() {
        if (selectedTarget == null || !isAlive || hasActed) return;

        hasActed = true;
        actionButton.setEnabled(false);
        actionButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        String uid = currentUser.getUid();
        String target = selectedTarget;
        String phaseAtClick = currentPhase;

        GameTransactions.OnTxComplete cb = new GameTransactions.OnTxComplete() {
            @Override public void onSuccess() {
                progressBar.setVisibility(View.GONE);
                infoText.setText("✅ Выбор сохранён");
            }
            @Override public void onError(Exception e) {
                Log.e(TAG, "Ошибка отправки действия", e);
                progressBar.setVisibility(View.GONE);
                hasActed = false;
                actionButton.setEnabled(true);
                actionButton.setVisibility(View.VISIBLE);
                Toast.makeText(GameActivity.this, "Ошибка отправки, попробуйте ещё раз", Toast.LENGTH_SHORT).show();
            }
        };

        if ("voting".equals(phaseAtClick)) {
            GameTransactions.submitDayVote(db, roomId, uid, target, cb);
        } else if ("night".equals(phaseAtClick)) {
            GameTransactions.submitNightAction(db, roomId, uid, target, cb);
        }
    }

    private boolean canActInCurrentPhase() {
        if (!isAlive) return false;
        String normRole = normalizeRole(myRole);
        if ("night".equals(currentPhase)) {
            if ("mafia".equals(nightStage)) return "mafia".equals(normRole);
            if ("doctor".equals(nightStage)) return "doctor".equals(normRole);
            if ("sheriff".equals(nightStage)) return "sheriff".equals(normRole);
            return false;
        }
        return "voting".equals(currentPhase);
    }

    // Нормализует роль в нижний регистр и убирает пробелы
    private String normalizeRole(String r) {
        if (r == null) return null;
        String s = r.trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    // ─────────────────────────────────────────────────────────────
    // Экраны результатов
    // ─────────────────────────────────────────────────────────────

    private void showNightResultScreen(Map<String, Object> nightResultData) {
        if (nightResultShown) return;
        nightResultShown = true;
        cancelTimer();

        Intent intent = new Intent(this, NightResultActivity.class);
        intent.putExtra("killedPlayerName", (String) nightResultData.get("killedPlayerName"));
        intent.putExtra("killedPlayerRole", (String) nightResultData.get("killedPlayerRole"));
        intent.putExtra("wasKillBlocked", Boolean.TRUE.equals(nightResultData.get("wasKillBlocked")));
        intent.putExtra("isSheriff", "sheriff".equals(normalizeRole(myRole)));
        intent.putExtra("sheriffTargetName", getPlayerNameFromMap((String) nightResultData.get("sheriffTargetId")));
        intent.putExtra("sheriffTargetRole", (String) nightResultData.get("sheriffTargetRole"));
        intent.putExtra("gameEndWinner", (String) nightResultData.get("gameEndWinner"));
        intent.putExtra("roomId", roomId);
        intent.putExtra("isHost", isHost);
        startActivity(intent);
    }

    private void showVoteResultScreen(Map<String, Object> voteResultData) {
        if (voteResultShown) return;
        voteResultShown = true;
        cancelTimer();

        Object topVoteCount = voteResultData.get("topVoteCount");
        Object totalVotes = voteResultData.get("totalVotes");

        Intent intent = new Intent(this, VoteResultActivity.class);
        intent.putExtra("executedPlayerName", (String) voteResultData.get("executedPlayerName"));
        intent.putExtra("executedPlayerRole", (String) voteResultData.get("executedPlayerRole"));
        intent.putExtra("wasTie", Boolean.TRUE.equals(voteResultData.get("wasTie")));
        intent.putExtra("voteCount", topVoteCount instanceof Number ? ((Number) topVoteCount).intValue() : 0);
        intent.putExtra("totalVotes", totalVotes instanceof Number ? ((Number) totalVotes).intValue() : 0);
        intent.putExtra("gameEndWinner", (String) voteResultData.get("gameEndWinner"));
        intent.putExtra("roomId", roomId);
        intent.putExtra("isHost", isHost);
        startActivity(intent);
    }

    private void handleGameEnd(Map<String, Object> data) {
        if (gameEndHandled) return;
        gameEndHandled = true;

        String winner = (String) data.get("winner");
        Intent intent = new Intent(this, GameEndActivity.class);
        intent.putExtra("winner", winner);
        intent.putExtra("roomId", roomId);
        startActivity(intent);
        finish();
    }

    // ─────────────────────────────────────────────────────────────
    // Анимация роли
    // ─────────────────────────────────────────────────────────────

    private void showRoleRevealAnimation() {
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        roleText.setText(getRoleDisplay());
        roleText.startAnimation(fadeIn);
        roleText.setVisibility(View.VISIBLE);

        new Handler().postDelayed(() -> {
            Animation fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out);
            roleText.startAnimation(fadeOut);

            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override public void onAnimationEnd(Animation animation) {
                    roleText.setVisibility(View.GONE);
                    roleRevealLayout.setVisibility(View.GONE);

                    Animation slideIn = AnimationUtils.loadAnimation(GameActivity.this, R.anim.slide_in);
                    gameControlsLayout.startAnimation(slideIn);
                    gameControlsLayout.setVisibility(View.VISIBLE);
                }
                @Override public void onAnimationStart(Animation a) {}
                @Override public void onAnimationRepeat(Animation a) {}
            });
        }, 1800);
    }

    // ─────────────────────────────────────────────────────────────
    // Таймеры — крутятся у КАЖДОГО клиента, не только у хоста/действующего игрока.
    // По истечении времени клиент сам пытается продвинуть игру транзакцией; если
    // её уже продвинул кто-то другой, транзакция просто ничего не делает.
    // ─────────────────────────────────────────────────────────────

    private void startPhaseTimerOnce(int seconds) {
        if (phaseTimer != null && nightStage.equals(timerForStage)) return;
        timerForStage = nightStage;
        cancelTimer();

        final String stageAtStart = nightStage;
        phaseTimer = new CountDownTimer((long) seconds * 1000, 1000) {
            @Override public void onTick(long ms) {
                timerText.setText("Осталось: " + (ms / 1000) + "с");
            }
            @Override public void onFinish() {
                timerText.setText("Время вышло!");
                GameTransactions.forceAdvanceNightStage(db, roomId, stageAtStart, null);
            }
        }.start();
    }

    private void startVotingTimerOnce(int seconds) {
        if (votingTimerStarted) return;
        votingTimerStarted = true;
        cancelTimer();

        phaseTimer = new CountDownTimer((long) seconds * 1000, 1000) {
            @Override public void onTick(long ms) {
                timerText.setText("Осталось: " + (ms / 1000) + "с");
            }
            @Override public void onFinish() {
                timerText.setText("Время вышло!");
                GameTransactions.forceResolveVoting(db, roomId, null);
            }
        }.start();
    }

    private void startDayTimerOnce(int seconds) {
        if (dayTimerStarted) return;
        dayTimerStarted = true;
        cancelTimer();

        phaseTimer = new CountDownTimer((long) seconds * 1000, 1000) {
            @Override public void onTick(long ms) {
                timerText.setText("Голосование через: " + (ms / 1000) + "с");
            }
            @Override public void onFinish() {
                timerText.setText("");
                GameTransactions.forceStartVoting(db, roomId, null);
            }
        }.start();
    }

    private void cancelTimer() {
        if (phaseTimer != null) {
            phaseTimer.cancel();
            phaseTimer = null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Список игроков
    // ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void updatePlayersList(Map<String, Object> players) {
        if (players == null) return;
        playersList.clear();

        for (Map.Entry<String, Object> entry : players.entrySet()) {
            Object pObjRaw = entry.getValue();
            if (!(pObjRaw instanceof Map)) continue;
            Map<String, Object> pData = (Map<String, Object>) pObjRaw;
            String name = getPlayerNameFromMap(entry.getKey());
            Player player = new Player(entry.getKey(), name);
            player.setAlive(isAliveObject(pData.get("alive")));
            Object photoBase64Obj = pData.get("photoBase64");
            if (photoBase64Obj != null) player.setPhotoUrl(photoBase64Obj.toString());
            playersList.add(player);
        }

        playersAdapter.setPlayers(playersList);
    }

    @SuppressWarnings("unchecked")
    private String getPlayerNameFromMap(String userId) {
        if (playersMap == null || userId == null) return "Игрок";
        Object pObj = playersMap.get(userId);
        if (pObj instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) pObj;
            Object nameObj = p.get("name");
            if (nameObj != null) {
                String name = nameObj.toString().trim();
                if (!name.isEmpty()) return name;
            }
        }
        return "Игрок";
    }

    private String getRoleDisplay() {
        if (myRole == null) return "Неизвестно";
        String r = normalizeRole(myRole);
        if ("mafia".equals(r)) return "🔫 МАФИЯ";
        if ("sheriff".equals(r)) return "🕵️ ШЕРИФ";
        if ("doctor".equals(r)) return "💉 ДОКТОР";
        if ("civilian".equals(r)) return "👤 МИРНЫЙ";
        return myRole.toUpperCase();
    }

    // ─────────────────────────────────────────────────────────────
    // Чат
    // ─────────────────────────────────────────────────────────────

    private void startChatListener() {
        if (chatListener != null) return; // уже запущен
        chatAdapter.clear();

        chatListener = db.collection("games").document(roomId)
                .collection("chat")
                .whereEqualTo("dayNumber", currentDay)
                .orderBy("timestamp")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;

                    List<ChatMessage> msgs = new ArrayList<>();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snapshots.getDocuments()) {
                        ChatMessage msg = doc.toObject(ChatMessage.class);
                        if (msg != null) msgs.add(msg);
                    }
                    chatAdapter.setMessages(msgs);
                    if (!msgs.isEmpty()) {
                        chatRecyclerView.scrollToPosition(msgs.size() - 1);
                    }
                });
    }

    private void stopChatListener() {
        if (chatListener != null) {
            chatListener.remove();
            chatListener = null;
        }
        chatAdapter.clear();
    }

    private void sendChatMessage() {
        if (chatInput == null) return;
        String text = chatInput.getText() != null ? chatInput.getText().toString().trim() : "";
        if (text.isEmpty()) return;

        chatInput.setText("");

        ChatMessage msg = new ChatMessage(
                currentUser.getUid(), myName, text, myPhotoBase64, currentDay);

        db.collection("games").document(roomId)
                .collection("chat")
                .add(msg)
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Ошибка отправки", Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        cancelTimer();

        // Принудительно обновим состояние из Firestore на случай, если listener не сработал
        // (например, активность была в фоне и пропустила событие).
        if (roomId != null && db != null) {
            db.collection("games").document(roomId).get()
                    .addOnSuccessListener(snap -> {
                        if (snap != null && snap.exists()) {
                            Map<String, Object> data = snap.getData();
                            if (data != null) {
                                applyGameState(data);
                            }
                        }
                    });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameListener != null) gameListener.remove();
        stopChatListener();
        cancelTimer();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Выйти из игры?")
                .setMessage("Вы действительно хотите покинуть игру?")
                .setPositiveButton("Выйти", (dialog, which) -> {
                    if (gameListener != null) gameListener.remove();
                    cancelTimer();
                    super.onBackPressed();
                })
                .setNegativeButton("Остаться", null)
                .show();
    }
}