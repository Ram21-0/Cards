package com.example.fb1;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashSet;

public class Compete extends AppCompatActivity {

    DatabaseReference reference, root;

    EditText editText;
    Button enter;

    HashSet<String> set;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        editText = findViewById(R.id.username);
        enter = findViewById(R.id.enter);

        root = FirebaseDatabase.getInstance().getReference();
        reference = FirebaseDatabase.getInstance().getReference().child("USERS");

        set = new HashSet<>();

        reference.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                for(DataSnapshot ds : dataSnapshot.getChildren()) {
                    set.add(ds.getKey());
                }
                System.out.println(set);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {

            }
        });

        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String username = editText.getText().toString().trim().toLowerCase();
                if(username.length() == 0)
                    Toast.makeText(Compete.this, "Invalid username", Toast.LENGTH_SHORT).show();
                else if(set.contains(username))
                    Toast.makeText(Compete.this, "Username already exists", Toast.LENGTH_SHORT).show();
                else {
                    reference.child(username).child("USERNAME").setValue(username);
                    root.child("COUNT").setValue(set.size() + 1);
                    set.add(username);
                    Data.username = username;
                    Data.loggedIn = true;
                    Toast.makeText(Compete.this, "Logged in as " + username, Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(Compete.this,Play.class));
                }
            }
        });
    }
}