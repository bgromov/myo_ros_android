package com.github.bgromov.myo_ros_android.myo_node;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thalmic.myo.Hub;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref_main);

        Preference button = findPreference("attach_myo");

        button.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Hub hub = Hub.getInstance();
                hub.attachToAdjacentMyo();
                return true;
            }
        });

        syncPreferences();
    }

    public void syncPreferences() {
        PreferenceScreen screen = getPreferenceScreen();
        SharedPreferences sharedPreferences = screen.getSharedPreferences();

        Gson gson = new Gson();
        Type myoPropertiesType = new TypeToken<HashMap<String, MyoProperties>>(){}.getType();
        HashMap<String, MyoProperties> myoSettings = gson.fromJson(sharedPreferences.getString("myos", ""), myoPropertiesType);

        // TODO Add button 'Add Myo'
        // No settings found
        if (myoPropertiesType == null) return;


        Map<String, ?> allPrefs = sharedPreferences.getAll();
        // Myo ID -> MAC address
        Map<Integer, String> staticIds = new HashMap<>();
        // MAC address -> Myo name
        Map<String, String> macsToNames = new HashMap<>();
        String prefix = "myo_";

        List<String> optionsList = new ArrayList<>();
        List<String> valuesList = new ArrayList<>();

        for (Map.Entry<String, MyoProperties> e: myoSettings.entrySet()) {
            optionsList.add(e.getValue().getName() + " [" + e.getKey() + "]");
            valuesList.add(e.getKey());
        }

        for (Map.Entry<String, MyoProperties> e: myoSettings.entrySet()) {
            String mac = e.getKey();
            String name = e.getValue().getName();
            Integer id = e.getValue().getId();

            ListPreference p = new ListPreference(screen.getContext());
            CharSequence[] ents = optionsList.toArray(new CharSequence[optionsList.size()]);
            CharSequence[] vals = valuesList.toArray(new CharSequence[valuesList.size()]);

//            p.setKey(prefix + id.toString());
            p.setEntries(ents);
            p.setEntryValues(vals);
            p.setValueIndex(id - 1);

            p.setTitle("Myo " + id.toString());
//            p.setSummary(macsToNames.get(p.getValue()) + " [" + p.getValue() + "]");
            p.setSummary(name + " [" + mac + "]");

            p.setOnPreferenceChangeListener((Preference preference, Object newValue) -> {
                preference.setSummary(macsToNames.get(newValue.toString()) + " [" + newValue.toString() + "]");
                return true;
            });

            p.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    return true;
                }
            });

            screen.addPreference(p);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = super.onCreateView(inflater, container, savedInstanceState);
        Preference button = findPreference("attach_myo");

        if (v != null) {
            ListView lv = (ListView) v.findViewById(android.R.id.list);
            lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                    if (id != button.getLayoutResource()) {
                        view.set
                    }
                    return false;
                }
            });
        }

        return v;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        syncPreferences();
    }
}
