package com.example.fb1;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.squareup.picasso.Picasso;

import java.util.ArrayList;

/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link CardFragment.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link CardFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class CardFragment extends Fragment {
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private ArrayList<Wrestler> list; //mParam1
    private int index; //mParam2;
    private boolean small;

    private OnFragmentInteractionListener mListener;

    View v;

    public CardFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment CardFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static CardFragment newInstance(ArrayList<Wrestler> param1, int param2, boolean param3) {
        CardFragment fragment = new CardFragment();
        Bundle args = new Bundle();
        args.putParcelableArrayList("List", param1);
        args.putInt("Index", param2);
        args.putBoolean("Size", param3);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            list = getArguments().getParcelableArrayList("List");
            index = getArguments().getInt("Index");
            small = getArguments().getBoolean("Size");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        Wrestler w = new Wrestler();
        if (index != -1) w = list.get(index);

        GradientDrawable sd = (GradientDrawable) getActivity().getDrawable(R.drawable.rounded);
        int resId, color = 0;
        if (index != -1) {
            resId = w.getBrand().equals("sd") ? R.color.SDColor : R.color.RawColor;
            sd.setColor(getResources().getColor(resId));
            color = getResources().getColor(resId);
        }

//        if(!small)
        v = inflater.inflate(R.layout.card, container, false);
//        else v = inflater.inflate(R.layout.game_card,container,false);

        if (index != -1) {
            TextView t = v.findViewById(R.id.t1);
            t.setTextColor(color);
            t = v.findViewById(R.id.t2);
            t.setTextColor(color);
            t = v.findViewById(R.id.t3);
            t.setTextColor(color);
            t = v.findViewById(R.id.t4);
            t.setTextColor(color);
            t = v.findViewById(R.id.t5);
            t.setTextColor(color);
            t = v.findViewById(R.id.t6);
            t.setTextColor(color);
        }

        ImageView imageView = v.findViewById(R.id.image);
        TextView name = v.findViewById(R.id.name),
                rank = v.findViewById(R.id.rank),
                fight = v.findViewById(R.id.fight),
                chest = v.findViewById(R.id.chest),
                biceps = v.findViewById(R.id.biceps),
                ht = v.findViewById(R.id.height),
                wt = v.findViewById(R.id.weight);

        if (index != -1) {
//            if (!small)
            Picasso.with(v.getContext()).load(w.getImg()).resize(840, 830).into(imageView);
//            else Picasso.with(v.getContext()).load(w.getImg()).resize(210, 208).into(imageView);

            name.setText(w.getName());
            rank.setText(String.valueOf(w.getRank()));
            fight.setText(String.valueOf(w.getFight()));
            chest.setText(String.valueOf(w.getChest()));
            biceps.setText(String.valueOf(w.getBiceps()));
            ht.setText(w.getFeet() + "\'" + w.getInches() + "\"");
            wt.setText(w.getWeightString());
        }
        return v;
    }

    // TODO: Rename method, update argument and hook method into UI event
    public void onButtonPressed(Uri uri) {
        if (mListener != null) {
            mListener.onFragmentInteraction(uri);
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        void onFragmentInteraction(Uri uri);
    }
}
