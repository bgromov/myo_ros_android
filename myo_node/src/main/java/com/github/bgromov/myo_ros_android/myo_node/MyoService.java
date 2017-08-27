package com.github.bgromov.myo_ros_android.myo_node;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class MyoService extends Service {
    public MyoService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
