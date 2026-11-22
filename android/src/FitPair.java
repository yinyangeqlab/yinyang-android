package com.yinyangeq.qudelixprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Two generated choices passed to the model-specific blind comparison. */
final class FitPair {
    static final String EXTRA = "generatedFitPair";
    final String[] names = new String[2];
    final String[] main = new String[2];
    final String[] match = new String[2];

    FitPair(String payload) throws JSONException {
        JSONArray choices = new JSONArray(payload);
        if (choices.length() != 2) throw new JSONException("Choose two fits.");
        for (int i = 0; i < 2; i++) {
            JSONObject choice = choices.getJSONObject(i);
            names[i] = choice.getString("name");
            main[i] = choice.getString("main");
            match[i] = choice.getString("match");
            if (names[i].isEmpty() || names[i].length() > 160 || main[i].isEmpty()
                    || main[i].length() + match[i].length() > 16384)
                throw new JSONException("Invalid fit.");
        }
    }
}
