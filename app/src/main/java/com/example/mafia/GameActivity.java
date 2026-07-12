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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

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

import android.media.MediaPlayer;

public class GameActivity extends AppCompatActivity {

    private static final String TAG = "GAME";

    private static final int MAFIA_STAGE_SECONDS = 30;
    private static final int DOCTOR_STAGE_SECONDS = 25;
    private static final int SHERIFF_STAGE_SECONDS = 25;
    private static final int DAY_DISCUSSION_SECONDS = 60;
    private static final int VOTING_SECONDS = 60;

    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private ListenerRegistration gameListener;
    private ListenerRegistration perksListener;

    private String roomId;
    private String myRole;
    private boolean isHost;
    private boolean isAlive = true;
    private String currentPhase = "";
    private String nightStage = "mafia";
    private int currentDay = 1;
    private Map<String, Object> playersMap;
    private String votingManagerId;

    // ── Premium ──────────────────────────────────────────────────
    private boolean myIsPremium = false;
    private boolean myTrialAvailableInGame = false;
    private boolean resurrectUsed = false;
    private boolean deathOfferShown = false;

    // ── UI ──────────────────────────────────────────────────────
    private TextView roleText, phaseText, infoText, timerText, roleDisplayText;
    private android.widget.ImageView roleRevealImage, roleDisplayImage;
    private RecyclerView playersRecyclerView;
    private Button actionButton, startVotingButton;
    private ProgressBar progressBar;
    private LinearLayout gameControlsLayout, roleRevealLayout, nightWaitingLayout;
    private TextView nightWaitingText;
    private BloodDripView bloodDripRole, bloodDripPhase;

    // ── Панель плюшек ────────────────────────────────────────────
    private LinearLayout perksPanel;
    private Button perkShieldButton, perkSelfhealButton, perkInvisibleButton;

    // Количество плюшек в инвентаре (из Firestore users)
    private long perkShieldCount = 0;
    private long perkSelfhealCount = 0;
    private long perkInvisibleCount = 0;

    // Активна ли плюшка в ТЕКУЩЕЙ ночи (хранится в games/{roomId}/players/{uid}.activePerk_*)
    // Эти флаги читаются из снепшота игры, не из профиля
    private boolean perkShieldActiveInGame = false;
    private boolean perkSelfhealActiveInGame = false;
    private boolean perkInvisibleActiveInGame = false;

    // ── Адаптер ───────────────────────────────────────────────────
    private PlayerActionAdapter playersAdapter;
    private List<Player> playersList = new ArrayList<>();

    // ── Чат ──────────────────────────────────────────────────────
    private android.widget.LinearLayout chatPanel;
    private com.google.android.material.floatingactionbutton.FloatingActionButton openChatButton;
    private androidx.recyclerview.widget.RecyclerView chatRecyclerView;
    private com.google.android.material.textfield.TextInputEditText chatInput;
    private ChatAdapter chatAdapter;
    private ListenerRegistration chatListener;
    private String myName = "Игрок";
    private String myPhotoBase64 = null;

    private String selectedTarget = null;
    private boolean hasActed = false;
    private CountDownTimer phaseTimer;
    private String previousRole = null;
    private MediaPlayer gameMusicPlayer;
    private long    lastShownRevealTs     = 0;
    private boolean resurrectRevealShown  = false;

    // ID последнего показанного результата — чтобы при onResume не показывать его повторно
    // и при этом показывать результат СЛЕДУЮЩЕЙ ночи/голосования
    private String shownNightResultId = null;  // "day_{N}" — результат ночи N (фаза после = день N)
    private String shownVoteResultId = null;  // "night_{N}" — результат голосования дня N
    private boolean gameEndHandled = false;
    private boolean votingTimerStarted = false;
    private boolean dayTimerStarted = false;
    private String timerForStage = null;

    // Самолечение: если активна плюшка самолечения, доктор может выбрать себя
    private boolean selfhealUnlocked = false;


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

        DiamondManager.ensureInitialDiamonds(db, currentUser.getUid());

        initViews();
        startPerksInventoryListener();
        setupGameListener();
    }

    private void initViews() {
        roleText = findViewById(R.id.roleText);
        phaseText = findViewById(R.id.phaseText);
        infoText = findViewById(R.id.infoText);
        timerText = findViewById(R.id.timerText);
        roleDisplayText = findViewById(R.id.roleDisplayText);
        roleRevealImage = findViewById(R.id.roleRevealImage);
        roleDisplayImage = findViewById(R.id.roleDisplayImage);
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

        perksPanel = findViewById(R.id.perksPanel);
        perkShieldButton = findViewById(R.id.perkShieldButton);
        perkSelfhealButton = findViewById(R.id.perkSelfhealButton);
        perkInvisibleButton = findViewById(R.id.perkInvisibleButton);

        perkShieldButton.setOnClickListener(v -> onPerkShieldClick());
        perkSelfhealButton.setOnClickListener(v -> onPerkSelfhealClick());
        perkInvisibleButton.setOnClickListener(v -> onPerkInvisibleClick());

        playersAdapter = new PlayerActionAdapter(currentUser.getUid());
        playersRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
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

        db.collection("users").document(currentUser.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String nick = doc.getString("nickname");
                        myName = (nick != null && !nick.isEmpty()) ? nick : "Игрок";
                        myPhotoBase64 = doc.getString("photoBase64");
                        myIsPremium = Boolean.TRUE.equals(doc.getBoolean("isPremium"));
                        if (!myIsPremium) {
                            PremiumManager.hasTrialAvailable(db, currentUser.getUid(), available -> {
                                myTrialAvailableInGame = available;
                            });
                        }
                        // Передаём статус Premium в адаптер (детальные голоса)
                        if (playersAdapter != null) {
                            playersAdapter.setShowVoteDetails(myIsPremium);
                        }
                        // Обновляем ChatAdapter статусом живой/мёртвый
                        if (chatAdapter != null) {
                            chatAdapter.setViewerAlive(isAlive);
                        }
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────
    // ПЛЮШКИ — инвентарь из профиля (только количество, не активация)
    // ─────────────────────────────────────────────────────────────

    /**
     * Слушаем документ пользователя ТОЛЬКО для отображения количества плюшек в инвентаре.
     * Активация/деактивация — через документ игры (games/{roomId}/players/{uid}.activePerk_*).
     */
    private void startPerksInventoryListener() {
        perksListener = db.collection("users").document(currentUser.getUid())
                .addSnapshotListener((snap, e) -> {
                    if (snap == null || !snap.exists()) return;
                    perkShieldCount = snap.getLong("perk_shield") != null ? snap.getLong("perk_shield") : 0L;
                    perkSelfhealCount = snap.getLong("perk_selfheal") != null ? snap.getLong("perk_selfheal") : 0L;
                    perkInvisibleCount = snap.getLong("perk_invisible") != null ? snap.getLong("perk_invisible") : 0L;
                    updatePerksUI();
                });
    }

    /**
     * Читаем статус активации из снепшота игры (players.{uid}.activePerk_*)
     */
    @SuppressWarnings("unchecked")
    private void readActivePerksFromGameSnapshot(Map<String, Object> players) {
        if (players == null) return;
        Object me = players.get(currentUser.getUid());
        if (!(me instanceof Map)) return;
        Map<String, Object> myData = (Map<String, Object>) me;
        perkShieldActiveInGame = Boolean.TRUE.equals(myData.get("activePerk_shield"));
        perkSelfhealActiveInGame = Boolean.TRUE.equals(myData.get("activePerk_selfheal"));
        perkInvisibleActiveInGame = Boolean.TRUE.equals(myData.get("activePerk_invisible"));

        // Самолечение разблокирует выбор себя доктором
        selfhealUnlocked = perkSelfhealActiveInGame && "doctor".equals(normalizeRole(myRole));
        // Сообщаем адаптеру можно ли выбрать себя
        if (playersAdapter != null) {
            playersAdapter.setSelfSelectionAllowed(selfhealUnlocked);
        }
    }

    private void updatePerksUI() {
        if (perksPanel == null) return;

        // Панель видна только живому игроку ночью (когда плюшки реально применяются)
        boolean isNight = "night".equals(currentPhase);
        boolean showPanel = isAlive && isNight;
        perksPanel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        if (!showPanel) return;

        // --- ЩИТ ---
        if (perkShieldActiveInGame) {
            perkShieldButton.setText("🛡️ ЩИТ АКТИВЕН");
            perkShieldButton.setEnabled(false);
            perkShieldButton.setAlpha(0.7f);
        } else if (perkShieldCount > 0) {
            perkShieldButton.setText("🛡️ Щит x" + perkShieldCount);
            perkShieldButton.setEnabled(true);
            perkShieldButton.setAlpha(1f);
        } else {
            perkShieldButton.setText("🛡️ Нет щита");
            perkShieldButton.setEnabled(false);
            perkShieldButton.setAlpha(0.3f);
        }

        // --- САМОЛЕЧЕНИЕ (только доктору) ---
        boolean isDoctor = "doctor".equals(normalizeRole(myRole));
        perkSelfhealButton.setVisibility(isDoctor ? View.VISIBLE : View.GONE);
        if (isDoctor) {
            if (perkSelfhealActiveInGame) {
                perkSelfhealButton.setText("💊 САМОЛЕЧЕНИЕ АКТИВНО");
                perkSelfhealButton.setEnabled(false);
                perkSelfhealButton.setAlpha(0.7f);
            } else if (perkSelfhealCount > 0) {
                perkSelfhealButton.setText("💊 Самолечение x" + perkSelfhealCount);
                perkSelfhealButton.setEnabled(true);
                perkSelfhealButton.setAlpha(1f);
            } else {
                perkSelfhealButton.setText("💊 Нет самолечения");
                perkSelfhealButton.setEnabled(false);
                perkSelfhealButton.setAlpha(0.3f);
            }
        }

        // --- НЕВИДИМКА ---
        if (perkInvisibleActiveInGame) {
            perkInvisibleButton.setText("👁️ НЕВИДИМКА АКТИВНА");
            perkInvisibleButton.setEnabled(false);
            perkInvisibleButton.setAlpha(0.7f);
        } else if (perkInvisibleCount > 0) {
            perkInvisibleButton.setText("👁️ Невидимка x" + perkInvisibleCount);
            perkInvisibleButton.setEnabled(true);
            perkInvisibleButton.setAlpha(1f);
        } else {
            perkInvisibleButton.setText("👁️ Нет невидимки");
            perkInvisibleButton.setEnabled(false);
            perkInvisibleButton.setAlpha(0.3f);
        }
    }

    // ── Клики по плюшкам ─────────────────────────────────────────

    private void onPerkShieldClick() {
        new AlertDialog.Builder(this, R.style.MafiaDialog)
                .setTitle("🛡️ Активировать Щит?")
                .setMessage("Если этой ночью мафия выберет вас жертвой — атака будет заблокирована.\n\nПлюшка расходуется только если мафия реально атаковала вас.")
                .setPositiveButton("АКТИВИРОВАТЬ", (d, w) -> activatePerkInGame("shield"))
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    private void onPerkSelfhealClick() {
        new AlertDialog.Builder(this, R.style.MafiaDialog)
                .setTitle("💊 Активировать Самолечение?")
                .setMessage("Вы сможете выбрать себя как доктор этой ночью.\n\nПлюшка расходуется только если вы вылечили себя.")
                .setPositiveButton("АКТИВИРОВАТЬ", (d, w) -> activatePerkInGame("selfheal"))
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    private void onPerkInvisibleClick() {
        new AlertDialog.Builder(this, R.style.MafiaDialog)
                .setTitle("👁️ Активировать Невидимку?")
                .setMessage("Мафия не сможет выбрать вас жертвой этой ночью.\n\nПлюшка расходуется по окончании ночи.")
                .setPositiveButton("АКТИВИРОВАТЬ", (d, w) -> activatePerkInGame("invisible"))
                .setNegativeButton("ОТМЕНА", null)
                .show();
    }

    /**
     * Активируем плюшку — пишем флаг в players.{uid}.activePerk_{type} в документ игры.
     * Количество в инвентаре (users/{uid}.perk_*) НЕ трогаем здесь — только после реального использования.
     */
    private void activatePerkInGame(String perkType) {
        String field = "players." + currentUser.getUid() + ".activePerk_" + perkType;
        db.collection("games").document(roomId)
                .update(field, true)
                .addOnSuccessListener(v -> {
                    Toast.makeText(this, "Плюшка активирована ✓", Toast.LENGTH_SHORT).show();
                    // Для самолечения — сразу разблокируем кнопку выбора себя
                    if ("selfheal".equals(perkType) && "doctor".equals(normalizeRole(myRole))) {
                        selfhealUnlocked = true;
                        playersAdapter.setSelfSelectionAllowed(true);
                        // Сбросить выбранную цель, чтобы игрок заново выбрал (возможно себя)
                        selectedTarget = null;
                        playersAdapter.setSelectedPlayer(null);
                        actionButton.setEnabled(false);
                        infoText.setText("💊 Теперь можете выбрать себя для лечения");
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Ошибка активации", Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────────
    // FIRESTORE LISTENER
    // ─────────────────────────────────────────────────────────────

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
        boolean wasAliveBeforeUpdate = isAlive;

        String newMyRole = getRoleFromPlayers(playersMap, currentUser.getUid());
        myRole = newMyRole;
        if (newMyRole != null && !newMyRole.equals(previousRole)) {
            previousRole = newMyRole;
            showRoleRevealAnimation();
            startGameMusic();
        }
        roleDisplayText.setText(getRoleDisplay());
        applyRoleImages();

        // Читаем активные плюшки из снепшота игры
        readActivePerksFromGameSnapshot(playersMap);

        String newPhase = (String) data.get("phase");
        String newNightStage = (String) data.get("nightStage");
        Long day = (Long) data.get("dayNumber");
        Object vmObj = data.get("votingManagerId");
        votingManagerId = (vmObj instanceof String) ? (String) vmObj : null;

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
        updatePerksUI();

        // ── Передаём управление голосованием, если текущий votingManager мёртв ──
        // Без этого вызова: если умирает именно хост (или тот, кто был
        // votingManager), кнопка "Начать голосование" не появляется вообще
        // ни у кого до конца дневной фазы — только через минуту по таймауту.
        GameTransactions.transferVotingManagerIfNeeded(db, roomId, new GameTransactions.OnTxComplete() {
            @Override public void onSuccess() {}
            @Override public void onError(Exception e) {
                Log.e(TAG, "Ошибка передачи votingManager", e);
            }
        });

        // ── Маркетинг: игрок только что умер — предлагаем Premium (если не Premium) ──
        if (wasAliveBeforeUpdate && !isAlive && !deathOfferShown) {
            deathOfferShown = true;
            showDeathPremiumOfferIfEligible();
        }

        Map<String, Object> nightResult = (Map<String, Object>) data.get("lastNightResult");
        boolean nightResultEndsGame = nightResult != null && nightResult.get("gameEndWinner") != null;
        // Идентификатор результата ночи — "day_{currentDay}" (фаза уже перешла в день)
        String nightResultId = "day_" + currentDay;
        if (nightResult != null && !nightResultId.equals(shownNightResultId)
                && ("day".equals(currentPhase) || ("ended".equals(currentPhase) && nightResultEndsGame))) {
            showNightResultScreen(nightResult, nightResultId);
            return;
        }

        Map<String, Object> voteResult = (Map<String, Object>) data.get("lastVoteResult");
        boolean voteResultEndsGame = voteResult != null && voteResult.get("gameEndWinner") != null;
        // Идентификатор результата голосования — "night_{currentDay}" (фаза перешла в следующую ночь)
        String voteResultId = "night_" + currentDay;
        if (voteResult != null && !voteResultId.equals(shownVoteResultId)
                && ("night".equals(currentPhase) || ("ended".equals(currentPhase) && voteResultEndsGame))) {
            showVoteResultScreen(voteResult, voteResultId);
            return;
        }

        if ("ended".equals(currentPhase)) {
            handleGameEnd(data);  // gameEndHandled-флаг защищает от повторного вызова
            return;
        }

        // ── Проверяем, есть ли новое "разоблачение" от реанимированного ────
        Object revealObj = data.get("resurrectReveal");
        if (revealObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reveal = (Map<String, Object>) revealObj;
            Object tsObj = reveal.get("timestamp");
            long revealTs = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0;
            if (revealTs > 0 && revealTs != lastShownRevealTs) {
                lastShownRevealTs = revealTs;
                String revealerName = reveal.get("revealerName") != null ? reveal.get("revealerName").toString() : "?";
                String targetName   = reveal.get("targetName")   != null ? reveal.get("targetName").toString()   : "?";
                showResurrectRevealDialog(revealerName, targetName);
            }
        }

        // ── Проверяем, нужно ли показать диалог выбора "кого сдать" ──────
        boolean myResurrectPending = Boolean.TRUE.equals(data.get("resurrectPending_" + currentUser.getUid()));
        if (myResurrectPending && !resurrectRevealShown) {
            resurrectRevealShown = true;
            showRevealChoiceDialog();
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

        // ── Кнопка реанимации: обновляется НЕЗАВИСИМО от фазы/updateGameUI() ──
        // Доступна и живому, и мёртвому Premium-игроку (1 раз за игру).
        updateResurrectButtonVisibility();
    }

    // ─────────────────────────────────────────────────────────────
    // РЕАНИМАЦИЯ (Premium)
    // ─────────────────────────────────────────────────────────────

    /**
     * Доступна ли реанимация прямо сейчас.
     * Правила: 1 раз за игру, доступна и живому, и мёртвому Premium-игроку.
     *  - Жив  → реанимирует любого МЁРТВОГО игрока (себя незачем — он и так жив).
     *  - Мёртв → реанимирует себя ИЛИ любого другого мёртвого.
     */
    private boolean canUseResurrect() {
        if (resurrectUsed) return false;
        if (!myIsPremium) return false;
        return true;
    }

    /** Показывает/скрывает кнопку реанимации. Вызывается из applyGameState() каждый раз. */
    private void updateResurrectButtonVisibility() {
        Button resurrectBtn = findViewById(R.id.resurrectButton);
        if (resurrectBtn == null) return;

        boolean hasDeadTarget = false;
        for (Player p : playersList) {
            if (!p.isAlive()) { hasDeadTarget = true; break; }
        }
        // Если я жив, "мёртвая цель" — это кто-то другой (не я). Если я мёртв — я тоже
        // считаюсь допустимой целью, но playersList и так содержит меня как мёртвого,
        // так что hasDeadTarget корректно учитывает оба случая.

        if (canUseResurrect() && hasDeadTarget) {
            resurrectBtn.setVisibility(View.VISIBLE);
            resurrectBtn.setOnClickListener(v -> showResurrectDialog());
        } else {
            resurrectBtn.setVisibility(View.GONE);
        }
    }

    /** Открывает диалог выбора цели реанимации. */
    /** Открывает диалог выбора цели реанимации. */
    private void showResurrectDialog() {
        if (!canUseResurrect()) {
            if (!myIsPremium) {
                Toast.makeText(this, "Реанимация — Premium фича", Toast.LENGTH_SHORT).show();
            } else if (resurrectUsed) {
                Toast.makeText(this, "Реанимация уже использована в этой игре", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // ── Собираем список мёртвых игроков ────────────────────────────────
        // Если Я ЖИВ — себя в список не добавляем (реанимировать живого некого).
        // Если Я МЁРТВ — себя добавляем первым пунктом.
        List<Player> deadOthers = new ArrayList<>();
        Player me = null;
        for (Player p : playersList) {
            if (p.isAlive()) continue;
            if (p.getId().equals(currentUser.getUid())) {
                if (!isAlive) me = p;   // себя добавляем в опции только если я тоже мёртв
            } else {
                deadOthers.add(p);
            }
        }

        final List<String> labels = new ArrayList<>();
        final List<String> ids    = new ArrayList<>();
        if (me != null) {
            labels.add("🔁 Реанимировать себя");
            ids.add(me.getId());
        }
        for (Player p : deadOthers) {
            labels.add("💀 " + p.getName());
            ids.add(p.getId());
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, "Некого реанимировать", Toast.LENGTH_SHORT).show();
            return;
        }

        // ⚠️ ВАЖНО: используем кастомный ListView через setView() вместо setItems().
        // Раньше здесь было setMessage() + setItems() — в androidx.appcompat.app.AlertDialog
        // (тема Material3) такая комбинация НЕ рендерит список: видно только текст-сообщение,
        // а пункты с именами игроков не отображаются и не нажимаются. ListView гарантирует,
        // что список виден и кликабелен в любой теме.
        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("💫 Реанимация — кого вернуть в игру? (1 раз за игру)")
                .setView(listView)
                .setNegativeButton("Отмена", null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String targetUid = ids.get(position);
            dialog.dismiss();
            confirmResurrect(targetUid);
        });

        dialog.show();
    }

    private void confirmResurrect(String targetUid) {
        PremiumManager.resurrect(db, roomId, currentUser.getUid(), targetUid,
                new PremiumManager.OnResurrectResult() {
                    @Override public void onSuccess() {
                        resurrectUsed = true;
                        Toast.makeText(GameActivity.this,
                                "💫 Реанимация применена!", Toast.LENGTH_SHORT).show();
                        updateResurrectButtonVisibility();
                    }
                    @Override public void onAlreadyUsed() {
                        resurrectUsed = true;   // синхронизируем локальный флаг с сервером
                        Toast.makeText(GameActivity.this,
                                "Реанимация уже использована в этой игре", Toast.LENGTH_SHORT).show();
                        updateResurrectButtonVisibility();
                    }
                    @Override public void onNotPremium() {}
                    @Override public void onError() {
                        Toast.makeText(GameActivity.this,
                                "Ошибка реанимации", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** Диалог для реанимированного игрока — кого назвать мафией (необязательно правда). */
    private void showRevealChoiceDialog() {
        List<Player> others = new ArrayList<>();
        for (Player p : playersList) {
            if (!p.getId().equals(currentUser.getUid())) others.add(p);
        }

        if (others.isEmpty()) return; // некого называть — выходим

        final List<String> names = new ArrayList<>();
        final List<String> ids   = new ArrayList<>();
        for (Player p : others) {
            names.add(p.getName());
            ids.add(p.getId());
        }

        // Тот же фикс, что и в showResurrectDialog(): setItems() + setMessage()
        // вместе не работают в AppCompat/Material AlertDialog — используем ListView.
        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("🔮 Вы восстали! Кого назвать мафией?\n(можно сказать неправду)")
                .setView(listView)
                .setCancelable(false)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String targetUid  = ids.get(position);
            String targetName = names.get(position);
            dialog.dismiss();
            PremiumManager.revealAsMafia(db, roomId,
                    currentUser.getUid(), myName,
                    targetUid, targetName);
        });

        dialog.show();
    }

    /** Всплывающее окно для ВСЕХ игроков при новом "разоблачении" от реанимированного. */
    private void showResurrectRevealDialog(String revealerName, String targetName) {
        new AlertDialog.Builder(this)
                .setTitle("💀 Реанимированный игрок говорит!")
                .setMessage("Игрок " + revealerName + " (реанимированный) считает, что " +
                        targetName + " — мафия!\n\n" +
                        "⚠️ Помни: реанимированный может говорить неправду!")
                .setPositiveButton("Понял(а)", null)
                .show();
    }

    /**
     * Маркетинг: предложение купить Premium сразу после смерти игрока —
     * только если он не Premium и не имеет доступного пробника (иначе он
     * и так видит кнопку реанимации, повторное предложение было бы лишним).
     */
    private void showDeathPremiumOfferIfEligible() {
        if (myIsPremium || myTrialAvailableInGame) return;

        new Handler().postDelayed(() -> {
            if (isFinishing()) return;

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
        }, 1500); // полторы секунды после смерти — даём игроку осознать, что случилось
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
        timerForStage = null;
        selfhealUnlocked = false;
        cancelTimer();
        if (playersAdapter != null) {
            playersAdapter.setSelfSelectionAllowed(false);
        }
        chatPanel.setVisibility(View.GONE);
        openChatButton.setVisibility(View.GONE);
        stopChatListener();
        if (playersAdapter != null) {
            playersAdapter.setSelectedPlayer(null);
            playersAdapter.setVoteCounts(new HashMap<>());
        }
        resurrectRevealShown = false;
    }

    private void updateAliveStatus() {
        if (playersMap == null || currentUser == null) return;
        Object meObj = playersMap.get(currentUser.getUid());
        isAlive = (meObj instanceof Map) && isAliveObject(((Map<String, Object>) meObj).get("alive"));
        if (chatAdapter != null) {
            chatAdapter.setViewerAlive(isAlive);
        }
    }

    private boolean isAliveObject(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        String s = String.valueOf(o).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }

    private void updateGameUI() {
        String phaseDisplay = "";
        if ("night".equals(currentPhase)) phaseDisplay = "🌙 Ночь " + currentDay;
        else if ("day".equals(currentPhase)) phaseDisplay = "☀️ День " + currentDay;
        else if ("voting".equals(currentPhase)) phaseDisplay = "🗳️ Голосование";
        phaseText.setText(phaseDisplay);

        if (bloodDripPhase != null)
            bloodDripPhase.setVisibility("night".equals(currentPhase) ? View.VISIBLE : View.GONE);

        playersAdapter.setInteractionEnabled(isAlive);
        if (!isAlive) {
            infoText.setText("👁 Вы мертвы — режим наблюдателя");
            actionButton.setVisibility(View.GONE);
        }
        // Кнопка реанимации управляется отдельно через updateResurrectButtonVisibility(),
        // вызываемый в конце applyGameState() — здесь её трогать не нужно, чтобы не
        // конфликтовать с видимостью кнопки "Начать голосование" и с фазами игры.
    }

    private void updateNightUI() {
        int seconds = "mafia".equals(nightStage) ? MAFIA_STAGE_SECONDS
                : "doctor".equals(nightStage) ? DOCTOR_STAGE_SECONDS
                : SHERIFF_STAGE_SECONDS;
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
            } else showWaitingUI("🌑 Мафия вышла на охоту...");
        } else if ("doctor".equals(nightStage)) {
            if ("doctor".equals(normalizeRole(myRole))) {
                String hint = selfhealUnlocked
                        ? "💉 Кого спасти? (можно выбрать себя)"
                        : "💉 Кого спасти?";
                showActionUI(hint);
                isMyStageTurn = true;
            } else showWaitingUI("💊 Доктор выбирает...");
        } else if ("sheriff".equals(nightStage)) {
            if ("sheriff".equals(normalizeRole(myRole))) {
                showActionUI("🕵️ Кого проверить?");
                isMyStageTurn = true;
            } else showWaitingUI("🔍 Шериф ведёт расследование...");
        }

        actionButton.setVisibility((isMyStageTurn && !hasActed) ? View.VISIBLE : View.GONE);
    }

    private void showActionUI(String hint) {
        nightWaitingLayout.setVisibility(View.GONE);
        playersRecyclerView.setVisibility(View.VISIBLE);
        if (!hasActed) {
            infoText.setText(hint);
            actionButton.setEnabled(selectedTarget != null);
        } else infoText.setText("✅ Выбор сохранён");
    }

    private void showWaitingUI(String message) {
        nightWaitingLayout.setVisibility(View.VISIBLE);
        playersRecyclerView.setVisibility(View.GONE);
        nightWaitingText.setText(message);
        actionButton.setVisibility(View.GONE);
    }

    private void updateDayDiscussionUI() {
        startDayTimerOnce(DAY_DISCUSSION_SECONDS);
        nightWaitingLayout.setVisibility(View.GONE);
        playersRecyclerView.setVisibility(View.VISIBLE);
        actionButton.setVisibility(View.GONE);
        if (isAlive) infoText.setText("💬 Обсудите ситуацию. Голосование начнётся автоматически.");
        // Кнопка доступна любому живому игроку (хост мог умереть)
        boolean isVotingManager = currentUser.getUid().equals(votingManagerId);
        startVotingButton.setVisibility((isAlive && isVotingManager) ? View.VISIBLE : View.GONE);
        openChatButton.setVisibility(View.VISIBLE);
        startChatListener();
    }

    private void updateDayVotingUI(Map<String, Object> dayVotes) {
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
        Map<String, List<String>> details = new HashMap<>();

        if (dayVotes != null) {
            for (Map.Entry<String, Object> entry : dayVotes.entrySet()) {
                Object v = entry.getValue();
                if (v == null) continue;
                String targetId = v.toString();
                String voterId  = entry.getKey();

                counts.put(targetId, counts.getOrDefault(targetId, 0) + 1);

                // Для Premium: собираем имена проголосовавших
                if (myIsPremium) {
                    String voterName = getPlayerNameFromMap(voterId);
                    if (!details.containsKey(targetId)) details.put(targetId, new ArrayList<>());
                    details.get(targetId).add(voterName);
                }
            }
        }

        playersAdapter.setVoteCounts(counts);
        if (myIsPremium) {
            playersAdapter.setVoteDetails(details);
        }
    }


    // ─────────────────────────────────────────────────────────────
    // Действия
    // ─────────────────────────────────────────────────────────────

    private void startVotingButtonClick() {
        startVotingButton.setEnabled(false);
        GameTransactions.forceStartVoting(db, roomId, new GameTransactions.OnTxComplete() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(Exception e) {
                startVotingButton.setEnabled(true);
                Toast.makeText(GameActivity.this, "Ошибка запуска голосования", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void performAction() {
        if (selectedTarget == null || !isAlive || hasActed) return;

        hasActed = true;
        actionButton.setEnabled(false);
        actionButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        String uid = currentUser.getUid();
        String target = selectedTarget;
        String phase = currentPhase;

        GameTransactions.OnTxComplete cb = new GameTransactions.OnTxComplete() {
            @Override
            public void onSuccess() {
                progressBar.setVisibility(View.GONE);
                infoText.setText("✅ Выбор сохранён");
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Ошибка отправки действия", e);
                progressBar.setVisibility(View.GONE);
                hasActed = false;
                actionButton.setEnabled(true);
                actionButton.setVisibility(View.VISIBLE);
                Toast.makeText(GameActivity.this, "Ошибка, попробуйте ещё раз", Toast.LENGTH_SHORT).show();
            }
        };

        if ("voting".equals(phase)) {
            GameTransactions.submitDayVote(db, roomId, uid, target, cb);
        } else if ("night".equals(phase)) {
            GameTransactions.submitNightAction(db, roomId, uid, target, cb);
        }
    }

    private boolean canActInCurrentPhase() {
        if (!isAlive) return false;
        String r = normalizeRole(myRole);
        if ("night".equals(currentPhase)) {
            if ("mafia".equals(nightStage)) return "mafia".equals(r);
            if ("doctor".equals(nightStage)) return "doctor".equals(r);
            if ("sheriff".equals(nightStage)) return "sheriff".equals(r);
            return false;
        }
        return "voting".equals(currentPhase);
    }

    private String normalizeRole(String r) {
        if (r == null) return null;
        String s = r.trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    // ─────────────────────────────────────────────────────────────
    // Экраны результатов
    // ─────────────────────────────────────────────────────────────

    private void showNightResultScreen(Map<String, Object> nightResultData, String resultId) {
        stopGameMusic();
        if (resultId.equals(shownNightResultId)) return;
        shownNightResultId = resultId;
        cancelTimer();

        Intent intent = new Intent(this, NightResultActivity.class);
        intent.putExtra("killedPlayerName", (String) nightResultData.get("killedPlayerName"));
        intent.putExtra("killedPlayerRole", (String) nightResultData.get("killedPlayerRole"));
        intent.putExtra("killedPlayerPhoto", (String) nightResultData.get("killedPlayerPhoto"));
        intent.putExtra("wasKillBlocked", Boolean.TRUE.equals(nightResultData.get("wasKillBlocked")));
        intent.putExtra("isSheriff", "sheriff".equals(normalizeRole(myRole)));
        intent.putExtra("sheriffTargetName", getPlayerNameFromMap((String) nightResultData.get("sheriffTargetId")));
        intent.putExtra("sheriffTargetRole", (String) nightResultData.get("sheriffTargetRole"));
        intent.putExtra("gameEndWinner", (String) nightResultData.get("gameEndWinner"));
        intent.putExtra("roomId", roomId);
        intent.putExtra("isHost", isHost);
        startActivity(intent);
    }

    private void showVoteResultScreen(Map<String, Object> voteResultData, String resultId) {
        stopGameMusic();
        if (resultId.equals(shownVoteResultId)) return;
        shownVoteResultId = resultId;
        cancelTimer();

        Object topVoteCount = voteResultData.get("topVoteCount");
        Object totalVotes = voteResultData.get("totalVotes");

        Intent intent = new Intent(this, VoteResultActivity.class);
        intent.putExtra("executedPlayerName", (String) voteResultData.get("executedPlayerName"));
        intent.putExtra("executedPlayerRole", (String) voteResultData.get("executedPlayerRole"));
        intent.putExtra("executedPlayerPhoto", (String) voteResultData.get("executedPlayerPhoto"));
        intent.putExtra("wasTie", Boolean.TRUE.equals(voteResultData.get("wasTie")));
        intent.putExtra("voteCount", topVoteCount instanceof Number ? ((Number) topVoteCount).intValue() : 0);
        intent.putExtra("totalVotes", totalVotes instanceof Number ? ((Number) totalVotes).intValue() : 0);
        intent.putExtra("gameEndWinner", (String) voteResultData.get("gameEndWinner"));
        intent.putExtra("roomId", roomId);
        intent.putExtra("isHost", isHost);
        startActivity(intent);
    }

    private void handleGameEnd(Map<String, Object> data) {
        stopGameMusic();
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
        try {
            MediaPlayer mp = MediaPlayer.create(this, R.raw.sound_for_role);
            if (mp != null) {
                mp.start();
                mp.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception ignored) {
        }

        applyRoleImages();
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        roleText.setText(getRoleDisplay());
        roleText.startAnimation(fadeIn);
        roleText.setVisibility(View.VISIBLE);
        if (roleRevealImage != null && roleRevealImage.getVisibility() == View.VISIBLE)
            roleRevealImage.startAnimation(fadeIn);

        new Handler().postDelayed(() -> {
            Animation fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out);
            roleText.startAnimation(fadeOut);
            if (roleRevealImage != null && roleRevealImage.getVisibility() == View.VISIBLE)
                roleRevealImage.startAnimation(fadeOut);

            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationEnd(Animation a) {
                    roleText.setVisibility(View.GONE);
                    if (roleRevealImage != null) roleRevealImage.setVisibility(View.GONE);
                    roleRevealLayout.setVisibility(View.GONE);
                    Animation slideIn = AnimationUtils.loadAnimation(GameActivity.this, R.anim.slide_in);
                    gameControlsLayout.startAnimation(slideIn);
                    gameControlsLayout.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAnimationStart(Animation a) {
                }

                @Override
                public void onAnimationRepeat(Animation a) {
                }
            });
        }, 1800);
    }

    // ─────────────────────────────────────────────────────────────
    // Таймеры
    // ─────────────────────────────────────────────────────────────

    private void startPhaseTimerOnce(int seconds) {
        if (phaseTimer != null && nightStage.equals(timerForStage)) return;
        timerForStage = nightStage;
        cancelTimer();
        final String stageAtStart = nightStage;
        phaseTimer = new CountDownTimer((long) seconds * 1000, 1000) {
            @Override
            public void onTick(long ms) {
                timerText.setText("Осталось: " + ms / 1000 + "с");
            }

            @Override
            public void onFinish() {
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
            @Override
            public void onTick(long ms) {
                timerText.setText("Осталось: " + ms / 1000 + "с");
            }

            @Override
            public void onFinish() {
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
            @Override
            public void onTick(long ms) {
                timerText.setText("Голосование через: " + ms / 1000 + "с");
            }

            @Override
            public void onFinish() {
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
            Object raw = entry.getValue();
            if (!(raw instanceof Map)) continue;
            Map<String, Object> pData = (Map<String, Object>) raw;

            Player player = new Player(entry.getKey(), getPlayerNameFromMap(entry.getKey()));
            player.setAlive(isAliveObject(pData.get("alive")));

            // Фото
            Object photo = pData.get("photoBase64");
            if (photo != null) player.setPhotoUrl(photo.toString());

            // ── Premium кастомизация ──────────────────────────────────────
            Object isPremObj = pData.get("isPremium");
            player.setPremium(Boolean.TRUE.equals(isPremObj));

            Object border = pData.get("cardBorderColor");
            if (border != null && !border.toString().isEmpty())
                player.setCardBorderColor(border.toString());

            Object badge = pData.get("avatarBadge");
            if (badge != null && !badge.toString().isEmpty())
                player.setAvatarBadge(badge.toString());

            Object nickColor = pData.get("nicknameColor");
            if (nickColor != null && !nickColor.toString().isEmpty())
                player.setNicknameColor(nickColor.toString());

            Object cardBg = pData.get("cardBackground");
            if (cardBg != null && !cardBg.toString().isEmpty())
                player.setCardBackground(cardBg.toString());

            Object bgOpacity = pData.get("cardBgOpacity");
            if (bgOpacity instanceof Number)
                player.setCardBgOpacity(((Number) bgOpacity).intValue());

            Object resurrected = pData.get("resurrected");
            player.setResurrected(Boolean.TRUE.equals(resurrected));
            // ─────────────────────────────────────────────────────────────

            playersList.add(player);
        }
        playersAdapter.setPlayers(playersList);
    }

    @SuppressWarnings("unchecked")
    private String getPlayerNameFromMap(String userId) {
        if (playersMap == null || userId == null) return "Игрок";
        Object pObj = playersMap.get(userId);
        if (pObj instanceof Map) {
            Object n = ((Map<String, Object>) pObj).get("name");
            if (n != null) {
                String s = n.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "Игрок";
    }

    private String getRoleDisplay() {
        if (myRole == null) return "Неизвестно";
        String r = normalizeRole(myRole);
        if ("mafia".equals(r)) return "МАФИЯ";
        if ("sheriff".equals(r)) return "ШЕРИФ";
        if ("doctor".equals(r)) return "ДОКТОР";
        if ("civilian".equals(r)) return "МИРНЫЙ";
        if ("lover".equals(r)) return "ЛЮБОВНИЦА";
        return myRole.toUpperCase();
    }

    private int getRoleImageRes() {
        if (myRole == null) return 0;
        String r = normalizeRole(myRole);
        if ("mafia".equals(r)) return R.drawable.role_mafia;
        if ("sheriff".equals(r)) return R.drawable.role_sheriff;
        if ("doctor".equals(r)) return R.drawable.role_doctor;
        if ("civilian".equals(r)) return R.drawable.role_civilian;
        if ("lover".equals(r)) return R.drawable.role_lover;
        return 0;
    }

    private void applyRoleImages() {
        int res = getRoleImageRes();
        if (res != 0) {
            if (roleRevealImage != null) {
                roleRevealImage.setImageResource(res);
                roleRevealImage.setVisibility(View.VISIBLE);
            }
            if (roleDisplayImage != null) {
                roleDisplayImage.setImageResource(res);
                roleDisplayImage.setVisibility(View.VISIBLE);
            }
        }
    }

    // ── Музыка ────────────────────────────────────────────────────

    private void startGameMusic() {
        if (gameMusicPlayer == null) {
            gameMusicPlayer = MediaPlayer.create(this, R.raw.sound_for_game);
            gameMusicPlayer.setLooping(true);
        }
        if (!gameMusicPlayer.isPlaying()) gameMusicPlayer.start();
    }

    private void stopGameMusic() {
        if (gameMusicPlayer != null) {
            if (gameMusicPlayer.isPlaying()) gameMusicPlayer.stop();
            gameMusicPlayer.release();
            gameMusicPlayer = null;
        }
    }

    // ── Чат ───────────────────────────────────────────────────────

    private void startChatListener() {
        if (chatListener != null) return;
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
                    if (!msgs.isEmpty()) chatRecyclerView.scrollToPosition(msgs.size() - 1);
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
                currentUser.getUid(), myName, text, myPhotoBase64, currentDay, !isAlive);
        db.collection("games").document(roomId).collection("chat").add(msg)
                .addOnFailureListener(e -> Toast.makeText(this, "Ошибка отправки", Toast.LENGTH_SHORT).show());
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        cancelTimer();
        votingTimerStarted = false;
        dayTimerStarted = false;
        timerForStage = null;
        if (roomId != null && db != null) {
            db.collection("games").document(roomId).get()
                    .addOnSuccessListener(snap -> {
                        if (snap != null && snap.exists()) {
                            Map<String, Object> d = snap.getData();
                            if (d != null) applyGameState(d);
                        }
                    });
        }
    }

    @Override
    protected void onDestroy() {
        stopGameMusic();
        super.onDestroy();
        if (gameListener != null) gameListener.remove();
        if (perksListener != null) perksListener.remove();
        stopChatListener();
        cancelTimer();
    }

    @Override
    public void onBackPressed() {
        stopGameMusic();
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