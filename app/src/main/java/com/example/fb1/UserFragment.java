package com.example.fb1;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import static com.example.fb1.Data.addToOpp;
import static com.example.fb1.Data.addToUser;
import static com.example.fb1.Data.getOpp;
import static com.example.fb1.Data.getUser;
import static com.example.fb1.Data.oppCount;
import static com.example.fb1.Data.userCount;
import static com.example.fb1.Data.userTurn;

/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link UserFragment.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link UserFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class UserFragment extends Fragment {
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;
    private Wrestler w1, w2;

    private CheckBox r, f, c, b, h, w;
    private GridLayout checkList;
    private View view;
    private TextView yourCardsCount, oppCardsCount, result;
    private Button viewOpponent, cont;

    private String battle = "";

    private OnFragmentInteractionListener mListener;

    public UserFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     * <p>
     * //     * @param list Parameter 1.
     * //     * @param uIdx Parameter 2.
     *
     * @return A new instance of fragment UserFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static UserFragment newInstance() {
        UserFragment fragment = new UserFragment();
        Bundle args = new Bundle();
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

        View v = inflater.inflate(R.layout.user_turn, container, false);

//        Toast.makeText(v.getContext(), "User Fragment", Toast.LENGTH_SHORT).show();
        yourCardsCount = v.findViewById(R.id.yourCards);
        oppCardsCount = v.findViewById(R.id.oppCards);

        yourCardsCount.setText("Your Cards : " + Data.userCount);
        oppCardsCount.setText("Not Your Cards : " + Data.oppCount);

        checkList = v.findViewById(R.id.checklist);
        checkList.setVisibility(View.VISIBLE);
        r = v.findViewById(R.id.rankBox);
        f = v.findViewById(R.id.fightBox);
        c = v.findViewById(R.id.chestBox);
        b = v.findViewById(R.id.bicepsBox);
        h = v.findViewById(R.id.heightBox);
        w = v.findViewById(R.id.weightBox);

        result = v.findViewById(R.id.result);
        viewOpponent = v.findViewById(R.id.viewOpponent);

//        if(!gameOver) {
        w1 = getUser();
        w2 = getOpp();
//        }

        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();

//        if(!gameOver)
        fragment = CardFragment.newInstance(Data.list, w1.getRank() - 1, false);
//        else fragment = new OverFragment();
        transaction.replace(R.id.cardToBeReplaced, fragment);
        transaction.commit();

//        if(!gameOver)
        setCheckList();

        return v;
    }

    Fragment fragment;

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

    private void setClickList() {

    }

    private void setCheckList() {
        checkList.setVisibility(View.VISIBLE);

        uncheckAll();
        setAllClickable(true);
        r.setText("Rank - " + w1.getRank());
        f.setText("Fight - " + w1.getFight());
        c.setText("Chest - " + w1.getChest());
        b.setText("Biceps - " + w1.getBiceps());
        h.setText("Height - " + w1.getHeightString());
        w.setText("Weight - " + w1.getWeightString());

        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uncheckAll();
                r.setChecked(true);
                setAllClickable(false);
                battle = Data.battle(w1, w2, 1);
                onResult(battle);
            }
        });
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uncheckAll();
                f.setChecked(true);
                setAllClickable(false);
                battle = Data.battle(w1, w2, 2);
                onResult(battle);
            }
        });
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uncheckAll();
                c.setChecked(true);
                setAllClickable(false);
                battle = Data.battle(w1, w2, 3);
                onResult(battle);
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uncheckAll();
                b.setChecked(true);
                setAllClickable(false);
                battle = Data.battle(w1, w2, 4);
                onResult(battle);
            }
        });
        h.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uncheckAll();
                h.setChecked(true);
                setAllClickable(false);
                battle = Data.battle(w1, w2, 5);
                onResult(battle);
            }
        });
        w.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uncheckAll();
                w.setChecked(true);
                setAllClickable(false);
                battle = Data.battle(w1, w2, 6);
                onResult(battle);
            }
        });
    }

    private void uncheckAll() {
        r.setChecked(false);
        f.setChecked(false);
        c.setChecked(false);
        b.setChecked(false);
        h.setChecked(false);
        w.setChecked(false);
    }

    private void setAllClickable(boolean clickable) {
        r.setClickable(clickable);
        f.setClickable(clickable);
        c.setClickable(clickable);
        b.setClickable(clickable);
        h.setClickable(clickable);
        w.setClickable(clickable);
    }

    private void onResult(final String battle) {
        checkList.setVisibility(View.GONE);
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
                Data.userTurn = true;
                break;
            case "YOU LOST!!!":
                addToOpp(w1, w2);
                userTurn = false;
                break;
            default:
                Data.addToClash(w1, w2);
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
