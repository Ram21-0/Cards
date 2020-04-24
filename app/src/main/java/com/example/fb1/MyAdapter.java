package com.example.fb1;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Picasso;

import java.util.ArrayList;

public class MyAdapter extends RecyclerView.Adapter<MyAdapter.MyViewHolder> {

    ArrayList<Wrestler> list;
    private Context context;
//    private OnItemClickListener listener;
//
//    public interface OnItemClickListener {
//        void onItemClick(int position);
//    }
//
//    public void setOnItemClickListener(OnItemClickListener listener) {
//        this.listener = listener;
//    }

    public class MyViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;

        public MyViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.image);

//            itemView.setOnClickListener(new View.OnClickListener() {
//                @Override
//                public void onClick(View v) {
//                    if(listener != null)
//                    {
//                        int pos = getAdapterPosition();
//                        if(pos != RecyclerView.NO_POSITION)
//                            listener.onItemClick(pos);
//                    }
//                }
//            });
        }
    }

    public MyAdapter(ArrayList<Wrestler> list, Context context) {
        this.list = list;
        this.context = context;
    }

    @NonNull
    @Override
    public MyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.exhib_card, parent, false);
        Toast.makeText(context, "" + getItemCount(), Toast.LENGTH_SHORT).show();
        return new MyAdapter.MyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final MyViewHolder holder, final int position) {
        Wrestler w = list.get(position);
        Picasso.with(context).load(w.getImg()).resize(350, 350).into(holder.imageView);
        holder.imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(v.getContext(), ViewCardActivity.class);
                i.putParcelableArrayListExtra("List", list);
                i.putExtra("Index", position);
                v.getContext().startActivity(i);

            }
        });
//        Toast.makeText(context, "added"+getItemCount(), Toast.LENGTH_SHORT).show();
    }

    @Override
    public int getItemCount() {
        return list.size();
    }
}
