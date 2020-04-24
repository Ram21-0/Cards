package com.example.fb1;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class Exhibition extends AppCompatActivity {//implements MyAdapter.OnItemClickListener {

    RecyclerView recyclerView;
    MyAdapter adapter;
    RecyclerView.LayoutManager layoutManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.exhibition);

        recyclerView = findViewById(R.id.recyclerView);

        recyclerView.setHasFixedSize(true);
        layoutManager = new GridLayoutManager(this, 3);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new MyAdapter(Data.list, this);
        adapter.notifyDataSetChanged();
        recyclerView.setAdapter(adapter);


//        Toast.makeText(this, "Set " + adapter.getItemCount(), Toast.LENGTH_SHORT).show();
    }

//    @Override
//    public void onItemClick(int position) {
//        Intent i = new Intent(Exhibition.this,ViewCardActivity.class);
//        i.putParcelableArrayListExtra("List",list);
//        i.putExtra("Index",position);
//        startActivity(i);
//    }
}
