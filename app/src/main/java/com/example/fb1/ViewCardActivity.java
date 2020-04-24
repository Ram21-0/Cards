package com.example.fb1;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;

public class ViewCardActivity extends AppCompatActivity implements CardFragment.OnFragmentInteractionListener {

    ArrayList<Wrestler> list = new ArrayList<>();
    int index;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_card_activity);

        Intent i = getIntent();
        list = i.getParcelableArrayListExtra("List");
        index = i.getIntExtra("Index", 0);

        Fragment fragment = CardFragment.newInstance(list, index, false);
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.toBeReplaced, fragment);
        transaction.commit();
    }

    @Override
    public void onFragmentInteraction(Uri uri) {

    }
}
