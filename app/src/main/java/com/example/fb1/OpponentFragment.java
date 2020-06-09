package com.example.fb1;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import static com.example.fb1.Data.addToClash;
import static com.example.fb1.Data.addToOpp;
import static com.example.fb1.Data.addToUser;
import static com.example.fb1.Data.gameOver;
import static com.example.fb1.Data.getOpp;
import static com.example.fb1.Data.getOppCall;
import static com.example.fb1.Data.getUser;
import static com.example.fb1.Data.oppCount;
import static com.example.fb1.Data.userCount;
import static com.example.fb1.Data.userTurn;

/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link OpponentFragment.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link OpponentFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class OpponentFragment extends Fragment {
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    private OnFragmentInteractionListener mListener;

    private Wrestler w1, w2;
    private TextView yourCardsCount, oppCardsCount, opponentData, result;
    private Button battle, viewOpponent;
    private String call, battleResult;
    private String[] type = {"Rank", "Fight", "Chest", "Biceps", "Height", "Weight"};

    public OpponentFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     * <p>
     * //     * @param param1 Parameter 1.
     * //     * @param param2 Parameter 2.
     *
     * @return A new instance of fragment UserFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static OpponentFragment newInstance() {
        OpponentFragment fragment = new OpponentFragment();
        Bundle args = new Bundle();
//        args.putString(ARG_PARAM1, param1);
//        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.opponent_turn, container, false);

        if (gameOver) {
            FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.toBeReplaced, new OverFragment());
            transaction.commit();
        }

        yourCardsCount = v.findViewById(R.id.yourCards);
        oppCardsCount = v.findViewById(R.id.oppCards);
        opponentData = v.findViewById(R.id.opponentData);
        viewOpponent = v.findViewById(R.id.viewOpponent);
        battle = v.findViewById(R.id.battle);
        result = v.findViewById(R.id.result);

        yourCardsCount.setText("Your Cards : " + Data.userCount);
        oppCardsCount.setText("Not Your Cards : " + Data.oppCount);

        w1 = getUser();
        w2 = getOpp();

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        Fragment fragment = CardFragment.newInstance(Data.list, w1.getRank() - 1, false);
        transaction.replace(R.id.cardToBeReplaced, fragment);
        transaction.commit();

        call = getOppCall(w2);
        battle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                battle.setVisibility(View.GONE);
                opponentData.setText(type[call.charAt(0) - 49] + " " + call.substring(2));
                battleResult = Data.battle(w1, w2, call.charAt(0) - 48);
                onResult(battleResult);
            }
        });

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


    private void onResult(final String battle) {
        result.setVisibility(View.VISIBLE);
        switch (battle) {
            case "YOU WON!!!":
                result.setTextColor(Color.GREEN);
                break;
            case "YOU LOST!!!":
                result.setTextColor(Color.RED);
                break;
            default:
                result.setTextColor(Color.WHITE);
        }
        result.setText(battle);
        viewOpponent.setVisibility(View.VISIBLE);

        getActivity().findViewById(R.id.Continue).setVisibility(View.VISIBLE);

        switch (battle) {
            case "YOU WON!!!":
                addToUser(w1, w2);
                userTurn = true;
                break;
            case "YOU LOST!!!":
                addToOpp(w1, w2);
                userTurn = false;
                break;
            default:
                addToClash(w1, w2);
        }
        yourCardsCount.setText("Your Cards - " + userCount);
        oppCardsCount.setText("Not Your Cards - " + oppCount);

        viewOpponent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                viewOpponent.setVisibility(View.GONE);
                yourCardsCount.setVisibility(View.INVISIBLE);
                oppCardsCount.setText("YOUR OPPONENT WAS " + w2.getName());
                FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
                Fragment fragment = CardFragment.newInstance(Data.list, w2.getRank() - 1, false);
                transaction.replace(R.id.cardToBeReplaced, fragment);
                transaction.commit();
            }
        });
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
