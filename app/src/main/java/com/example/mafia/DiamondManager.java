package com.example.mafia;

import android.util.Log;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class DiamondManager {

    private static final String TAG = "DiamondManager";

    public static final long PERK_SHIELD_COST     = 150;
    public static final long PERK_SELFHEAL_COST   = 100;
    public static final long PERK_INVISIBLE_COST  = 100;
    public static final long INITIAL_DIAMONDS     = 250;
    public static final long WINNER_REWARD        = 50;

    /** Однократно начисляем 250 алмазов, если ещё не было. */
    public static void ensureInitialDiamonds(FirebaseFirestore db, String uid) {
        db.collection("users").document(uid).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    Boolean initialized = doc.getBoolean("diamondsInitialized");
                    if (Boolean.TRUE.equals(initialized)) return;

                    Map<String, Object> upd = new HashMap<>();
                    upd.put("diamondsInitialized", true);
                    upd.put("diamonds", FieldValue.increment(INITIAL_DIAMONDS));
                    db.collection("users").document(uid).update(upd)
                            .addOnFailureListener(e ->
                                    Log.e(TAG, "Ошибка начисления стартовых алмазов", e));
                });
    }

    /**
     * Формула награды по позиции выбывания.
     *  deathPosition == 0 → дожил (победитель или проигравший без смерти) → 0
     *  deathPosition == 1 → убит первым → 0
     *  deathPosition == 2 → 0–10 случайных
     *  deathPosition == 3 → 10–15
     *  deathPosition == N → (N-2)*5 .. (N-2)*5+5, max 45
     *  isWinner → 50
     */
    public static int calcDiamondsForPosition(int deathPosition, boolean isWinner) {
        if (isWinner) return (int) WINNER_REWARD;
        if (deathPosition <= 1) return 0;

        int minDiamonds = (deathPosition - 2) * 5;
        int maxDiamonds = (deathPosition == 2) ? 10 : minDiamonds + 5;
        maxDiamonds = Math.min(maxDiamonds, 45);
        minDiamonds = Math.min(minDiamonds, 45);
        if (minDiamonds >= maxDiamonds) return minDiamonds;
        return minDiamonds + (int) (Math.random() * (maxDiamonds - minDiamonds + 1));
    }

    /** Покупка плюшки через транзакцию. */
    public static void purchasePerk(FirebaseFirestore db, String uid, String perkType,
                                    OnPurchaseResult callback) {
        long cost = getPerkCost(perkType);
        String field = getPerkField(perkType);
        if (cost < 0 || field == null) {
            callback.onError("Неизвестная плюшка");
            return;
        }

        DocumentReference docRef = db.collection("users").document(uid);

        db.runTransaction(transaction -> {
                    DocumentSnapshot snap = transaction.get(docRef);
                    Long diamondsLong = snap.getLong("diamonds");
                    long diamonds = diamondsLong != null ? diamondsLong : 0L;
                    if (diamonds < cost) { throw new RuntimeException("NOT_ENOUGH");}
                    transaction.update(docRef, "diamonds", FieldValue.increment(-cost));
                    transaction.update(docRef, field, FieldValue.increment(1));
                    return null;
                }).addOnSuccessListener(v -> callback.onSuccess())
                .addOnFailureListener(e -> {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.contains("NOT_ENOUGH")) {
                        callback.onNotEnoughDiamonds();
                    } else {
                        callback.onError(msg);
                    }
                });
    }

    /**
     * Деактивировать и уменьшить счётчик плюшки после завершения игры.
     */
    public static void consumeActivePerk(FirebaseFirestore db, String uid, String perkType) {
        String perkField  = getPerkField(perkType);
        String activeField = "activePerk_" + perkField;

        DocumentReference docRef = db.collection("users").document(uid);

        db.runTransaction(transaction -> {
            DocumentSnapshot snap = transaction.get(docRef);
            Long countLong = snap.getLong(perkField);
            long count = countLong != null ? countLong : 0L;
            long newCount = Math.max(0, count - 1);
            transaction.update(docRef, perkField, newCount);
            transaction.update(docRef, activeField, false);
            return null;
        }).addOnFailureListener(e ->
                Log.e(TAG, "Ошибка consumeActivePerk " + perkType, e));
    }

    public static long getPerkCost(String perkType) {
        if ("shield".equals(perkType))    return PERK_SHIELD_COST;
        if ("selfheal".equals(perkType))  return PERK_SELFHEAL_COST;
        if ("invisible".equals(perkType)) return PERK_INVISIBLE_COST;
        return -1;
    }

    public static String getPerkField(String perkType) {
        if ("shield".equals(perkType))    return "perk_shield";
        if ("selfheal".equals(perkType))  return "perk_selfheal";
        if ("invisible".equals(perkType)) return "perk_invisible";
        return null;
    }

    public interface OnPurchaseResult {
        void onSuccess();
        void onNotEnoughDiamonds();
        void onError(String message);
    }
}