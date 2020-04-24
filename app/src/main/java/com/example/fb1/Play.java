package com.example.fb1;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import java.util.Random;

import static com.example.fb1.Data.gameOver;
import static com.example.fb1.Data.userTurn;

public class Play extends AppCompatActivity implements OpponentFragment.OnFragmentInteractionListener, UserFragment.OnFragmentInteractionListener, CardFragment.OnFragmentInteractionListener, OverFragment.OnFragmentInteractionListener {

    Button cont;
    Random random = new Random();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.game);

        Data.distribute();
        userTurn = random.nextBoolean();

        cont = findViewById(R.id.Continue);
        cont.setVisibility(View.GONE);

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        if (!gameOver && userTurn)
            transaction.replace(R.id.toBeReplaced, UserFragment.newInstance());
        else if (!gameOver) transaction.replace(R.id.toBeReplaced, OpponentFragment.newInstance());
        transaction.commit();

        cont.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                cont.setVisibility(View.GONE);

                FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
                if (gameOver) transaction.replace(R.id.toBeReplaced, new OverFragment());
                else if (userTurn)
                    transaction.replace(R.id.toBeReplaced, UserFragment.newInstance());
                else transaction.replace(R.id.toBeReplaced, OpponentFragment.newInstance());
                transaction.commit();

                cont.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onFragmentInteraction(Uri uri) {

    }
}
