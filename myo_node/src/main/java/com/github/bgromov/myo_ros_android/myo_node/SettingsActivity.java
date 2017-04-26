package com.github.bgromov.myo_ros_android.myo_node;

import android.app.Activity;
import android.os.Bundle;

/**
 * Created by 0xff on 19/04/17.
 */

public class SettingsActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }
}