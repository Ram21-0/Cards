package com.example.fb1;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

public class Data {

    public static ArrayList<Wrestler> list = newList();
    public static ArrayList<Wrestler> A = new ArrayList<>();
    public static ArrayList<Wrestler> B = new ArrayList<>();
    public static ArrayList<Wrestler> clash = new ArrayList<>();
    public static int userCount, oppCount;
    public static boolean userTurn, userWon, gameOver, loaded = false, isClash = false;

    public static ArrayList<Wrestler> newList() {
        userWon = gameOver = isClash = false;
        final ArrayList<Wrestler> l = new ArrayList<>(55);
        DatabaseReference reference = FirebaseDatabase.getInstance().getReference().child("");
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

    public static String getOppCall(Wrestler w2) {
        Random random = new Random();
        int r = random.nextInt(6) + 1;
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
