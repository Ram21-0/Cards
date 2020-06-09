package com.example.fb1;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class Data {

    public static String jsonString = "{\"data\": [{\"1\": {\"rank\": 0, \"fight\": 44, \"chest\": 38, \"biceps\": 38, \"height\": 15, \"weight\": 29}}, {\"2\": {\"rank\": 1, \"fight\": 46, \"chest\": 18, \"biceps\": 3, \"height\": 3, \"weight\": 5}}, {\"3\": {\"rank\": 2, \"fight\": 31, \"chest\": 38, \"biceps\": 38, \"height\": 44, \"weight\": 43}}, {\"4\": {\"rank\": 3, \"fight\": 50, \"chest\": 51, \"biceps\": 45, \"height\": 51, \"weight\": 50}}, {\"5\": {\"rank\": 4, \"fight\": 43, \"chest\": 8, \"biceps\": 10, \"height\": 37, \"weight\": 29}}, {\"6\": {\"rank\": 5, \"fight\": 52, \"chest\": 49, \"biceps\": 45, \"height\": 50, \"weight\": 46}}, {\"7\": {\"rank\": 6, \"fight\": 33, \"chest\": 51, \"biceps\": 52, \"height\": 53, \"weight\": 52}}, {\"8\": {\"rank\": 7, \"fight\": 53, \"chest\": 8, \"biceps\": 10, \"height\": 51, \"weight\": 53}}, {\"9\": {\"rank\": 8, \"fight\": 45, \"chest\": 27, \"biceps\": 38, \"height\": 37, \"weight\": 35}}, {\"10\": {\"rank\": 9, \"fight\": 37, \"chest\": 6, \"biceps\": 29, \"height\": 15, \"weight\": 15}}, {\"11\": {\"rank\": 10, \"fight\": 49, \"chest\": 27, \"biceps\": 10, \"height\": 10, \"weight\": 18}}, {\"12\": {\"rank\": 11, \"fight\": 51, \"chest\": 38, \"biceps\": 45, \"height\": 37, \"weight\": 38}}, {\"13\": {\"rank\": 12, \"fight\": 34, \"chest\": 8, \"biceps\": 23, \"height\": 34, \"weight\": 39}}, {\"14\": {\"rank\": 13, \"fight\": 47, \"chest\": 4, \"biceps\": 29, \"height\": 28, \"weight\": 22}}, {\"15\": {\"rank\": 14, \"fight\": 38, \"chest\": 38, \"biceps\": 23, \"height\": 15, \"weight\": 16}}, {\"16\": {\"rank\": 15, \"fight\": 48, \"chest\": 8, \"biceps\": 10, \"height\": 28, \"weight\": 18}}, {\"17\": {\"rank\": 16, \"fight\": 41, \"chest\": 53, \"biceps\": 52, \"height\": 15, \"weight\": 51}}, {\"18\": {\"rank\": 17, \"fight\": 27, \"chest\": 1, \"biceps\": 0, \"height\": 3, \"weight\": 0}}, {\"19\": {\"rank\": 18, \"fight\": 40, \"chest\": 27, \"biceps\": 29, \"height\": 6, \"weight\": 9}}, {\"20\": {\"rank\": 19, \"fight\": 18, \"chest\": 49, \"biceps\": 29, \"height\": 15, \"weight\": 25}}, {\"21\": {\"rank\": 20, \"fight\": 36, \"chest\": 27, \"biceps\": 48, \"height\": 10, \"weight\": 34}}, {\"22\": {\"rank\": 21, \"fight\": 42, \"chest\": 38, \"biceps\": 38, \"height\": 28, \"weight\": 26}}, {\"23\": {\"rank\": 22, \"fight\": 39, \"chest\": 8, \"biceps\": 10, \"height\": 44, \"weight\": 40}}, {\"24\": {\"rank\": 23, \"fight\": 23, \"chest\": 18, \"biceps\": 10, \"height\": 44, \"weight\": 41}}, {\"25\": {\"rank\": 24, \"fight\": 19, \"chest\": 1, \"biceps\": 0, \"height\": 3, \"weight\": 0}}, {\"26\": {\"rank\": 25, \"fight\": 7, \"chest\": 38, \"biceps\": 51, \"height\": 37, \"weight\": 49}}, {\"27\": {\"rank\": 26, \"fight\": 30, \"chest\": 8, \"biceps\": 6, \"height\": 15, \"weight\": 16}}, {\"28\": {\"rank\": 27, \"fight\": 35, \"chest\": 26, \"biceps\": 29, \"height\": 28, \"weight\": 29}}, {\"29\": {\"rank\": 28, \"fight\": 12, \"chest\": 38, \"biceps\": 29, \"height\": 47, \"weight\": 47}}, {\"30\": {\"rank\": 29, \"fight\": 29, \"chest\": 27, \"biceps\": 38, \"height\": 28, \"weight\": 21}}, {\"31\": {\"rank\": 30, \"fight\": 23, \"chest\": 27, \"biceps\": 20, \"height\": 37, \"weight\": 44}}, {\"32\": {\"rank\": 31, \"fight\": 19, \"chest\": 8, \"biceps\": 10, \"height\": 15, \"weight\": 23}}, {\"33\": {\"rank\": 32, \"fight\": 6, \"chest\": 38, \"biceps\": 38, \"height\": 34, \"weight\": 36}}, {\"34\": {\"rank\": 33, \"fight\": 21, \"chest\": 27, \"biceps\": 48, \"height\": 15, \"weight\": 29}}, {\"35\": {\"rank\": 34, \"fight\": 16, \"chest\": 18, \"biceps\": 6, \"height\": 15, \"weight\": 27}}, {\"36\": {\"rank\": 35, \"fight\": 21, \"chest\": 27, \"biceps\": 48, \"height\": 28, \"weight\": 29}}, {\"37\": {\"rank\": 36, \"fight\": 9, \"chest\": 23, \"biceps\": 10, \"height\": 15, \"weight\": 12}}, {\"38\": {\"rank\": 37, \"fight\": 27, \"chest\": 3, \"biceps\": 3, \"height\": 1, \"weight\": 3}}, {\"39\": {\"rank\": 38, \"fight\": 10, \"chest\": 8, \"biceps\": 23, \"height\": 34, \"weight\": 27}}, {\"40\": {\"rank\": 39, \"fight\": 15, \"chest\": 8, \"biceps\": 6, \"height\": 15, \"weight\": 23}}, {\"41\": {\"rank\": 40, \"fight\": 13, \"chest\": 18, \"biceps\": 23, \"height\": 6, \"weight\": 7}}, {\"42\": {\"rank\": 41, \"fight\": 3, \"chest\": 18, \"biceps\": 23, \"height\": 47, \"weight\": 42}}, {\"43\": {\"rank\": 42, \"fight\": 4, \"chest\": 23, \"biceps\": 6, \"height\": 15, \"weight\": 8}}, {\"44\": {\"rank\": 43, \"fight\": 0, \"chest\": 8, \"biceps\": 10, \"height\": 10, \"weight\": 11}}, {\"45\": {\"rank\": 44, \"fight\": 1, \"chest\": 38, \"biceps\": 29, \"height\": 47, \"weight\": 47}}, {\"46\": {\"rank\": 45, \"fight\": 16, \"chest\": 6, \"biceps\": 10, \"height\": 15, \"weight\": 12}}, {\"47\": {\"rank\": 46, \"fight\": 1, \"chest\": 27, \"biceps\": 37, \"height\": 42, \"weight\": 37}}, {\"48\": {\"rank\": 47, \"fight\": 11, \"chest\": 38, \"biceps\": 38, \"height\": 10, \"weight\": 12}}, {\"49\": {\"rank\": 48, \"fight\": 13, \"chest\": 23, \"biceps\": 20, \"height\": 6, \"weight\": 6}}, {\"50\": {\"rank\": 49, \"fight\": 7, \"chest\": 27, \"biceps\": 20, \"height\": 42, \"weight\": 44}}, {\"51\": {\"rank\": 50, \"fight\": 23, \"chest\": 0, \"biceps\": 3, \"height\": 1, \"weight\": 4}}, {\"52\": {\"rank\": 51, \"fight\": 4, \"chest\": 27, \"biceps\": 23, \"height\": 9, \"weight\": 10}}, {\"53\": {\"rank\": 52, \"fight\": 32, \"chest\": 5, \"biceps\": 0, \"height\": 0, \"weight\": 2}}, {\"54\": {\"rank\": 53, \"fight\": 26, \"chest\": 38, \"biceps\": 29, \"height\": 10, \"weight\": 18}}]}";

    public static ArrayList<Wrestler> list = newList();
    public static ArrayList<Wrestler> A = new ArrayList<>();
    public static ArrayList<Wrestler> B = new ArrayList<>();
    public static ArrayList<Wrestler> clash = new ArrayList<>();
    public static int userCount, oppCount;
    public static boolean userTurn, userWon, gameOver, loaded = false, isClash = false;
    public static String username;
    public static boolean loggedIn = false;
    public static ArrayList<HashMap<Integer, Integer>> choice;

    public static ArrayList<Wrestler> newList() {
        loadChoices();
        userWon = gameOver = isClash = false;
        final ArrayList<Wrestler> l = new ArrayList<>(55);
        DatabaseReference reference = FirebaseDatabase.getInstance().getReference().child("Data");
        reference.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {

                int i = 0;
                l.clear();
                for (DataSnapshot ds : dataSnapshot.getChildren()) {
                    Wrestler w = ds.getValue(Wrestler.class);
                    if (i++ > 0) l.add(w);
                }
                loaded = true;
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {

            }
        });

        return l;
    }

    public static void distribute() {
        gameOver = false;
        isClash = false;
        ArrayList<Wrestler> deck = new ArrayList<>(list);
        Collections.shuffle(deck);
        A = new ArrayList<>();
        B = new ArrayList<>();
        for (int i = 0; i < deck.size(); i++)
            if (i % 2 == 0) A.add(deck.get(i));
            else B.add(deck.get(i));
        userCount = A.size();
        oppCount = B.size();
    }

    public static Wrestler getUser() {
        if (A.isEmpty()) return null;
        return A.remove(0);
    }

    public static Wrestler getOpp() {
        if (B.isEmpty()) return null;
        return B.remove(0);
    }

    public static void addToUser(Wrestler w1, Wrestler w2) {
        A.add(w1);
        A.add(w2);
        userCount++;
        oppCount--;
        if (isClash) {
            isClash = false;
            addToUserFromClash();
        }
        if (oppCount == 0 || B.size() == 0) {
            gameOver = userWon = true;
        }
    }

    public static void addToOpp(Wrestler w1, Wrestler w2) {
        B.add(w2);
        B.add(w1);
        userCount--;
        oppCount++;
        if (isClash) {
            isClash = false;
            addToOppFromClash();
        }
        if (A.size() == 0 || userCount == 0) {
            gameOver = true;
            userWon = false;
        }
    }

    public static void addToClash(Wrestler w1, Wrestler w2) {
        isClash = true;
        clash.add(w1);
        clash.add(w2);
        userCount--;
        oppCount--;
        if (B.size() == 0 || oppCount == 0) {
            gameOver = userWon = true;
        }
        if (A.size() == 0 || userCount == 0) {
            gameOver = true;
            userWon = false;
        }
    }

    public static void addToUserFromClash() {
        A.addAll(clash);
        userCount += clash.size();
        clash = new ArrayList<>();
    }

    public static void addToOppFromClash() {
        B.addAll(clash);
        oppCount += clash.size();
        clash = new ArrayList<>();
    }

    public static String battle(Wrestler w1, Wrestler w2, int pos) {
        switch (pos) {
            case 1:
                if (w1.getRank() < w2.getRank()) return "YOU WON!!!";
                return "YOU LOST!!!";
            case 2:
                if (w1.getFight() > w2.getFight()) return "YOU WON!!!";
                if (w1.getFight() < w2.getFight()) return "YOU LOST!!!";
                break;
            case 3:
                if (w1.getChest() > w2.getChest()) return "YOU WON!!!";
                if (w1.getChest() < w2.getChest()) return "YOU LOST!!!";
                break;
            case 4:
                if (w1.getBiceps() > w2.getBiceps()) return "YOU WON!!!";
                if (w1.getBiceps() < w2.getBiceps()) return "YOU LOST!!!";
                break;
            case 5:
                if (w1.getFeet() > w2.getFeet()) return "YOU WON!!!";
                if (w1.getFeet() < w2.getFeet()) return "YOU LOST!!!";
                if (w1.getInches() > w2.getInches()) return "YOU WON!!!";
                if (w1.getInches() < w2.getInches()) return "YOU LOST!!!";
                break;
            case 6:
                if (w1.getWeight() > w2.getWeight()) return "YOU WON!!!";
                if (w1.getWeight() < w2.getWeight()) return "YOU LOST!!!";
        }
        return "CLASH";
    }

    static void loadChoices() {
        JSONObject jsonObject = null;
        try {
            jsonObject = new JSONObject(jsonString);
            final JSONArray jsonArray = jsonObject.getJSONArray("data");
            choice = new ArrayList<>();

            for (int i = 0; i < jsonArray.length(); i++) {
                HashMap<Integer, Integer> m = new HashMap<>();
                JSONObject wObj = jsonArray.getJSONObject(i);
                JSONObject obj = wObj.getJSONObject("" + (i + 1));
                m.put(1, obj.getInt("rank"));
                m.put(2, obj.getInt("fight"));
                m.put(3, obj.getInt("chest"));
                m.put(4, obj.getInt("biceps"));
                m.put(5, obj.getInt("fight"));
                m.put(6, obj.getInt("weight"));
                choice.add(m);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public static String getOppCall(Wrestler w2) {

        int r = 0, max = -1;
        HashMap<Integer, Integer> m = choice.get(w2.getRank() - 1);
        System.out.println(m);
        for (int i : m.keySet()) {
            if (max < m.get(i)) {
                max = m.get(i);
                r = i;
            }
        }

//        Random random = new Random();
//        int r = random.nextInt(6) + 1;

        switch (r) {
            case 1:
                return r + "-" + w2.getRank();
            case 2:
                return r + "-" + w2.getFight();
            case 3:
                return r + "-" + w2.getChest();
            case 4:
                return r + "-" + w2.getBiceps();
            case 5:
                return r + "-" + w2.getHeightString();
            case 6:
                return r + "-" + w2.getWeightString();
        }
        return "";
    }
}
