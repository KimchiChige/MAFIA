package com.example.mafia;

import android.util.Log;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class PremiumManager {

    private static final String TAG = "PremiumManager";

    public static final int DAILY_DIAMONDS        = 20;
    public static final int PREMIUM_GAME_BONUS    = 30;
    public static final int MAX_ROLE_USES_PER_DAY = 3;

    // ─────────────────────────────────────────────────────────────────────────
    // Проверка статуса
    // ─────────────────────────────────────────────────────────────────────────

    public interface OnPremiumStatusLoaded {
        void onLoaded(boolean isPremium);
    }

    public static void checkPremium(FirebaseFirestore db, String uid,
                                    OnPremiumStatusLoaded callback) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    boolean premium = doc.exists() && Boolean.TRUE.equals(doc.getBoolean("isPremium"));
                    callback.onLoaded(premium);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Ошибка проверки премиума", e);
                    callback.onLoaded(false);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Пробный период: 1 бесплатная игра с Premium-фичами при первом запуске
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Поле в users/{uid}: trialGameAvailable (boolean)
    //   true  — новый пользователь, пробная игра ещё не использована
    //   false — пробная игра уже сыграна (или пользователь уже стал Premium)
    // Ставится в true при регистрации (см. патч LoginActivity), либо
    // отсутствие поля трактуется как true для существующих пользователей,
    // ещё не имевших этого поля до обновления (см. hasTrialAvailable).

    public interface OnTrialCheckResult {
        void onResult(boolean available);
    }

    /**
     * Есть ли у пользователя ещё доступная пробная Premium-игра.
     * Если пользователь уже Premium — пробный период не нужен, вернёт false.
     */
    public static void hasTrialAvailable(FirebaseFirestore db, String uid,
                                         OnTrialCheckResult callback) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) { callback.onResult(false); return; }
                    boolean isPremium = Boolean.TRUE.equals(doc.getBoolean("isPremium"));
                    if (isPremium) { callback.onResult(false); return; }

                    // Если поля нет в документе (старый пользователь до апдейта) —
                    // считаем пробник ещё доступным.
                    Boolean trialField = doc.getBoolean("trialGameAvailable");
                    boolean available = trialField == null || trialField;
                    callback.onResult(available);
                })
                .addOnFailureListener(e -> callback.onResult(false));
    }

    /**
     * Списывает пробную попытку — вызывать один раз, когда игра с пробными
     * Premium-фичами реально завершилась (не в момент старта, а в момент
     * окончания игры), чтобы не "сжигать" пробник если игра не состоялась.
     */
    public static void consumeTrialGame(FirebaseFirestore db, String uid) {
        db.collection("users").document(uid)
                .update("trialGameAvailable", false)
                .addOnFailureListener(e -> Log.e(TAG, "Ошибка списания пробной игры", e));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ежедневный бонус (20 алмазов)
    // ─────────────────────────────────────────────────────────────────────────

    public interface OnDailyClaimResult {
        void onClaimed(int diamonds);
        void onAlreadyClaimed();
        void onNotPremium();
        void onError(String msg);
    }

    public static void claimDailyBonus(FirebaseFirestore db, String uid,
                                       OnDailyClaimResult callback) {
        DocumentReference ref = db.collection("users").document(uid);

        db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref);
            if (!snap.exists()) return "no_user";
            Boolean isPremium = snap.getBoolean("isPremium");
            if (!Boolean.TRUE.equals(isPremium)) return "not_premium";

            Long lastClaim = snap.getLong("premiumDailyLastClaim");
            if (lastClaim != null && isSameDay(lastClaim, System.currentTimeMillis())) {
                return "already_claimed";
            }

            tx.update(ref, "diamonds", FieldValue.increment(DAILY_DIAMONDS));
            tx.update(ref, "premiumDailyLastClaim", System.currentTimeMillis());
            return "claimed";

        }).addOnSuccessListener(result -> {
            if ("claimed".equals(result)) {
                callback.onClaimed(DAILY_DIAMONDS);
            } else if ("already_claimed".equals(result)) {
                callback.onAlreadyClaimed();
            } else if ("not_premium".equals(result)) {
                callback.onNotPremium();
            } else {
                callback.onError("no_user");
            }
        }).addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ВИП-умножитель алмазов (+30 за игру)
    // ─────────────────────────────────────────────────────────────────────────

    public interface OnBonusResult {
        void onDone(int bonusAdded);
        void onError();
    }

    public static void awardPremiumGameBonus(FirebaseFirestore db, String uid,
                                             OnBonusResult callback) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists() || !Boolean.TRUE.equals(doc.getBoolean("isPremium"))) {
                        callback.onDone(0);
                        return;
                    }
                    db.collection("users").document(uid)
                            .update("diamonds", FieldValue.increment(PREMIUM_GAME_BONUS))
                            .addOnSuccessListener(v -> callback.onDone(PREMIUM_GAME_BONUS))
                            .addOnFailureListener(e -> callback.onError());
                })
                .addOnFailureListener(e -> callback.onError());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Выбор роли (3 раза в день)
    // ─────────────────────────────────────────────────────────────────────────

    public interface OnRoleSelectResult {
        void onSuccess(int usesLeft);
        void onLimitReached();
        void onNotPremium();
        void onError();
    }

    public static void useRoleSelection(FirebaseFirestore db, String uid, String chosenRole,
                                        OnRoleSelectResult callback) {
        DocumentReference ref = db.collection("users").document(uid);
        String today = getTodayString();

        db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref);
            if (!snap.exists()) return "no_user";
            if (!Boolean.TRUE.equals(snap.getBoolean("isPremium"))) return "not_premium";

            String lastDate = snap.getString("premiumRoleUsesDate");
            long usesLeft = MAX_ROLE_USES_PER_DAY;

            if (today.equals(lastDate)) {
                Long used = snap.getLong("premiumRoleUsesLeft");
                usesLeft = used != null ? used : MAX_ROLE_USES_PER_DAY;
            }

            if (usesLeft <= 0) return "limit";

            tx.update(ref, "premiumRoleUsesDate", today);
            tx.update(ref, "premiumRoleUsesLeft", usesLeft - 1);
            tx.update(ref, "premiumChosenRole", chosenRole);
            return "ok:" + (usesLeft - 1);

        }).addOnSuccessListener(result -> {
            if ("not_premium".equals(result)) { callback.onNotPremium(); return; }
            if ("limit".equals(result))       { callback.onLimitReached(); return; }
            if (result != null && result.startsWith("ok:")) {
                int left = Integer.parseInt(result.substring(3));
                callback.onSuccess(left);
            } else {
                callback.onError();
            }
        }).addOnFailureListener(e -> callback.onError());
    }

    /**
     * Выбор роли для пользователя с ПРОБНЫМ периодом (isPremium == false,
     * но trialGameAvailable == true). В отличие от обычного Premium-выбора:
     *  - не проверяет isPremium
     *  - не расходует MAX_ROLE_USES_PER_DAY — пробник разовый, всего 1 попытка
     *  - использует то же поле premiumChosenRole, чтобы startGame() применил
     *    роль тем же кодом, что и для настоящего Premium
     * Саму попытку (trialGameAvailable -> false) списывает не этот метод,
     * а PremiumManager.consumeTrialGame() — вызывается позже, при завершении
     * игры, чтобы не сжигать пробник если игра не состоялась.
     */
    public static void useTrialRoleSelection(FirebaseFirestore db, String uid, String chosenRole,
                                             OnRoleSelectResult callback) {
        DocumentReference ref = db.collection("users").document(uid);
        String today = getTodayString();

        db.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(ref);
            if (!snap.exists()) return "no_user";

            Boolean isPremium = snap.getBoolean("isPremium");
            if (Boolean.TRUE.equals(isPremium)) return "already_premium"; // используй обычный путь

            Boolean trialField = snap.getBoolean("trialGameAvailable");
            boolean trialAvailable = trialField == null || trialField;
            if (!trialAvailable) return "limit";

            tx.update(ref, "premiumRoleUsesDate", today);
            tx.update(ref, "premiumChosenRole", chosenRole);
            return "ok:0";

        }).addOnSuccessListener(result -> {
            if ("limit".equals(result))          { callback.onLimitReached(); return; }
            if ("already_premium".equals(result)){ callback.onNotPremium();   return; }
            if (result != null && result.startsWith("ok:")) {
                callback.onSuccess(0);   // пробник одноразовый, "осталось попыток" не актуально
            } else {
                callback.onError();
            }
        }).addOnFailureListener(e -> callback.onError());
    }

    // Простой callback с int — заменяет android.util.Consumer (требует API 24+)
    public interface OnIntCallback {
        void onResult(int value);
    }

    /** Сколько попыток выбора роли осталось сегодня. */
    public static void getRoleUsesLeft(FirebaseFirestore db, String uid,
                                       OnIntCallback callback) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists() || !Boolean.TRUE.equals(doc.getBoolean("isPremium"))) {
                        callback.onResult(0);
                        return;
                    }
                    String today    = getTodayString();
                    String lastDate = doc.getString("premiumRoleUsesDate");
                    if (!today.equals(lastDate)) {
                        callback.onResult(MAX_ROLE_USES_PER_DAY);
                        return;
                    }
                    Long uses = doc.getLong("premiumRoleUsesLeft");
                    callback.onResult(uses != null ? (int)(long)uses : MAX_ROLE_USES_PER_DAY);
                })
                .addOnFailureListener(e -> callback.onResult(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Реанимация (1 раз за игру)
    // ─────────────────────────────────────────────────────────────────────────

    public interface OnResurrectResult {
        void onSuccess();
        void onAlreadyUsed();
        void onNotPremium();
        void onError();
    }

    public static void resurrect(FirebaseFirestore db, String roomId,
                                 String callerUid, String targetUid,
                                 OnResurrectResult callback) {
        db.collection("games").document(roomId).get()
                .addOnSuccessListener(gameDoc -> {
                    if (!gameDoc.exists()) { callback.onError(); return; }

                    Boolean used = gameDoc.getBoolean("resurrectUsed_" + callerUid);
                    if (Boolean.TRUE.equals(used)) { callback.onAlreadyUsed(); return; }

                    db.collection("users").document(callerUid).get()
                            .addOnSuccessListener(userDoc -> {
                                if (!Boolean.TRUE.equals(userDoc.getBoolean("isPremium"))) {
                                    callback.onNotPremium();
                                    return;
                                }
                                Map<String, Object> updates = new HashMap<>();
                                updates.put("players." + targetUid + ".alive", true);
                                updates.put("players." + targetUid + ".resurrected", true);
                                updates.put("resurrectUsed_" + callerUid, true);
                                updates.put("resurrectPending_" + targetUid, true);

                                db.collection("games").document(roomId).update(updates)
                                        .addOnSuccessListener(v -> callback.onSuccess())
                                        .addOnFailureListener(e -> callback.onError());
                            })
                            .addOnFailureListener(e -> callback.onError());
                })
                .addOnFailureListener(e -> callback.onError());
    }

    public static void revealAsMafia(FirebaseFirestore db, String roomId,
                                     String revealerUid, String revealerName,
                                     String targetUid, String targetName) {
        Map<String, Object> reveal = new HashMap<>();
        reveal.put("revealerUid",  revealerUid);
        reveal.put("revealerName", revealerName);
        reveal.put("targetUid",    targetUid);
        reveal.put("targetName",   targetName);
        reveal.put("timestamp",    System.currentTimeMillis());

        Map<String, Object> gameUpdate = new HashMap<>();
        gameUpdate.put("resurrectReveal", reveal);
        gameUpdate.put("resurrectPending_" + revealerUid, false);

        db.collection("games").document(roomId).update(gameUpdate)
                .addOnFailureListener(e -> Log.e(TAG, "revealAsMafia error", e));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Утилиты
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isSameDay(long ts1, long ts2) {
        Calendar c1 = Calendar.getInstance(); c1.setTimeInMillis(ts1);
        Calendar c2 = Calendar.getInstance(); c2.setTimeInMillis(ts2);
        return c1.get(Calendar.YEAR)        == c2.get(Calendar.YEAR)
                && c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private static String getTodayString() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.MONTH) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }
}