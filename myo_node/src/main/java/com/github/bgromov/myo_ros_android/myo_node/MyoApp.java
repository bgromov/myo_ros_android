package com.github.bgromov.myo_ros_android.myo_node;

import android.app.Application;
import android.content.Intent;

import com.github.bgromov.myo_ros_android.myo_node.MyoService;

/**
 * Created by 0xff on 27/08/17.
 */

public class MyoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, MyoService.class));
    }
}