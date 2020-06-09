package com.example.fb1;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    Button exhibition, start, login;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        exhibition = findViewById(R.id.exhibition);
        start = findViewById(R.id.start);
        login = findViewById(R.id.login);

//        if (Data.list.isEmpty()) {
//            exhibition.setVisibility(View.GONE);
//            start.setVisibility(View.GONE);
//            login.setVisibility(View.GONE);
//            Data.list = Data.newList();
//        }

        exhibition.setVisibility(View.VISIBLE);
        start.setVisibility(View.VISIBLE);
        login.setVisibility(View.VISIBLE);

        exhibition.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, Exhibition.class));
            }
        });

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, Play.class));
            }
        });

        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, Compete.class));
            }
        });
    }
}
