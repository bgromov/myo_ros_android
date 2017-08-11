/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.github.bgromov.myo_ros_android.myo_node;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.util.CircularArray;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import org.ros.android.RosActivity;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMainExecutor;

import android.util.Log;

import com.facebook.stetho.Stetho;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thalmic.myo.AbstractDeviceListener;
import com.thalmic.myo.Arm;
import com.thalmic.myo.DeviceListener;
import com.thalmic.myo.Hub;
import com.thalmic.myo.Myo;
import com.thalmic.myo.Pose;
import com.thalmic.myo.Quaternion;
import com.thalmic.myo.Vector3;
import com.thalmic.myo.XDirection;
import com.thalmic.myo.scanner.ScanActivity;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import geometry_msgs.Vector3Stamped;
import myo_ros.StatusStamped;
import geometry_msgs.QuaternionStamped;
import myo_ros.GestureStamped;

public class MainActivity extends RosActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    // We store each Myo object that we attach to in this list, so that we can keep track of the order we've seen
    // each Myo and give it a unique short identifier (see onAttach() and identifyMyo() below).
    private Map<Myo, MyoNode> mKnownMyoObjs = new HashMap<>();
    private Set<Myo> mTimeSyncedMyos = new HashSet<>();
    private Map<String, MyoProperties> mMyoSettings;
    private int mMyoCount = 0;
    private Gson gson;

    // ROS stuff
    private NodeMainExecutor mNodeExecutor;
    private NodeConfiguration mNodeConfiguration;

    private SharedPreferences mPrefs;

    public MainActivity() {
        super("Myo Node", "Myo Node");
    }

    // Returns unique integer ID for Myo. If the Myo known already, takes it ID from persistent
    // storage. Otherwise, finds the next available.
    private int identifyMyo(Myo myo) {
        int id = -1;
        // Check if Myo known to us (including persistent storage)
        if (mMyoSettings.containsKey(myo.getMacAddress())) {
            MyoProperties props = mMyoSettings.get(myo.getMacAddress());

            Log.d("Myo Node", myo.getName() + " [" + myo.getMacAddress() + "] known as Myo " + Integer.toString(props.id));
            return props.id;
        } else {
            // Get all known IDs
            Set<Integer> known_ids = new TreeSet<>();
            for (MyoProperties v: mMyoSettings.values()) {
                known_ids.add(v.id);
            }
            // Find next available ID
            id = 1;
            while (known_ids.contains(id)) id++;

            Log.d("Myo Node", myo.getName() + " [" + myo.getMacAddress() + "] was give ID " + Integer.toString(id));
            return id;
        }
    }

    public MyoNode addMyo(Myo myo, long timestamp) {
        MyoProperties props = new MyoProperties();
        props.id = identifyMyo(myo);
        props.name = myo.getName();

        mMyoSettings.put(myo.getMacAddress(), props);
        mPrefs.edit().putString("myos", gson.toJson(mMyoSettings)).apply();

        MyoNode node = new MyoNode(myo, props.id, timestamp);
        mKnownMyoObjs.put(myo, node);

        return node;

//        mPrefs.edit().putString("myo_" + identifyMyo(myo), myo.getMacAddress()).apply();
//        mPrefs.edit().putString(myo.getMacAddress(), myo.getName()).apply();
    }

    public void removeMyo(Myo myo) {
        if (mKnownMyoObjs.containsKey(myo)) {
            mNodeExecutor.shutdownNodeMain(mKnownMyoObjs.get(myo));
            mKnownMyoObjs.remove(myo);
            mMyoSettings.remove(myo.getMacAddress());
            mPrefs.edit().putString("myos", gson.toJson(mMyoSettings)).apply();
        }
    }

    public List<String> getAllKnownMyos() {
        List<String> list = new ArrayList<>();
        mKnownMyoObjs.keySet().forEach(k -> {
            list.add(k.getMacAddress());
        });
        return list;
    }

    // Classes that inherit from AbstractDeviceListener can be used to receive events from Myo devices.
    // If you do not override an event, the default behavior is to do nothing.
    private DeviceListener mListener = new AbstractDeviceListener() {
        // Every time the SDK successfully attaches to a Myo armband, this function will be called.
        //
        // You can rely on the following rules:
        //  - onAttach() will only be called once for each Myo device
        //  - no other events will occur involving a given Myo device before onAttach() is called with it
        //
        // If you need to do some kind of per-Myo preparation before handling events, you can safely do it in onAttach().
        @Override
        public void onAttach(Myo myo, long timestamp) {
            // The object for a Myo is unique - in other words, it's safe to compare two Myo references to
            // see if they're referring to the same Myo.
            // Add the Myo object to our list of known Myo devices. This list is used to implement identifyMyo() below so
            // that we can give each Myo a nice short identifier.
            if (mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = addMyo(myo, timestamp);

            mNodeExecutor.execute(nodeMain, mNodeConfiguration);

            synchronized (nodeMain) {
                while (nodeMain.node_ == null) {
                    try {
                        nodeMain.wait();
                    } catch (InterruptedException e) {

                    }
                }
            }

            // Now that we've added it to our list, get our short ID for it and print it out.
            Log.i("onAttach", "Attached to " + myo.getName() + " [" + myo.getMacAddress() + "], now known as Myo " + identifyMyo(myo) + ".");
        }

        @Override
        public void onDetach(Myo myo, long timestamp) {
            removeMyo(myo);
        }

        // onConnect() is called whenever a Myo has been connected.
        @Override
        public void onConnect(Myo myo, long timestamp) {
            // Set the text color of the text view to cyan when a Myo connects.
        }
        // onDisconnect() is called whenever a Myo has been disconnected.
        @Override
        public void onDisconnect(Myo myo, long timestamp) {
            // Set the text color of the text view to red when a Myo disconnects.
        }
        // onArmSync() is called whenever Myo has recognized a Sync Gesture after someone has put it on their
        // arm. This lets Myo know which arm it's on and which way it's facing.
        @Override
        public void onArmSync(Myo myo, long timestamp, Arm arm, XDirection xDirection) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            StatusStamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(StatusStamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getStatus().setSync(true);
            msg.getStatus().setUnlock(false);
            msg.getStatus().setArm((byte) arm.ordinal());
            msg.getStatus().setDirection((byte) xDirection.ordinal());

            nodeMain.publishStatus(msg);
        }
        // onArmUnsync() is called whenever Myo has detected that it was moved from a stable position on a person's arm after
        // it recognized the arm. Typically this happens when someone takes Myo off of their arm, but it can also happen
        // when Myo is moved around on the arm.
        @Override
        public void onArmUnsync(Myo myo, long timestamp) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            StatusStamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(StatusStamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getStatus().setSync(false);
            msg.getStatus().setUnlock(false);
            msg.getStatus().setArm((byte) Arm.UNKNOWN.ordinal());
            msg.getStatus().setDirection((byte) XDirection.UNKNOWN.ordinal());

            nodeMain.publishStatus(msg);
        }
        // onUnlock() is called whenever a synced Myo has been unlocked. Under the standard locking
        // policy, that means poses will now be delivered to the listener.
        @Override
        public void onUnlock(Myo myo, long timestamp) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            StatusStamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(StatusStamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getStatus().setUnlock(true);

            nodeMain.publishStatus(msg);
        }
        // onLock() is called whenever a synced Myo has been locked. Under the standard locking
        // policy, that means poses will no longer be delivered to the listener.
        @Override
        public void onLock(Myo myo, long timestamp) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            StatusStamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(StatusStamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getStatus().setUnlock(false);

            nodeMain.publishStatus(msg);
        }

        // onOrientationData() is called whenever a Myo provides its current orientation,
        // represented as a quaternion.
        @Override
        public void onOrientationData(Myo myo, long timestamp, Quaternion rotation) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;

            if (!nodeMain.time_synced_) {
                nodeMain.syncToRosTime(timestamp);
                return;
            }

//            org.ros.message.Time now = nodeMain.node_.getCurrentTime();
//            Log.i("Myo" + nodeMain.getMyoID(), "Ros time: " + now + " Myo corrected time: " + nodeMain.myoToRosTime(timestamp)
//                    + " Offset: " + now.subtract(nodeMain.myoToRosTime(timestamp)));

            QuaternionStamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(geometry_msgs.QuaternionStamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            Quaternion q_n = rotation.normalized();

            msg.getQuaternion().setX(q_n.x());
            msg.getQuaternion().setY(q_n.y());
            msg.getQuaternion().setZ(q_n.z());
            msg.getQuaternion().setW(q_n.w());

            nodeMain.publishRotation(msg);
        }
        // onPose() is called whenever a Myo provides a new pose.
        @Override
        public void onPose(Myo myo, long timestamp, Pose pose) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            GestureStamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(GestureStamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getGesture().setGesture((byte) pose.ordinal());

            nodeMain.publishGesture(msg);
        }

        @Override
        public void onGyroscopeData(Myo myo, long timestamp, Vector3 gyro) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            Vector3Stamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(Vector3Stamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getVector().setX(gyro.x());
            msg.getVector().setY(gyro.y());
            msg.getVector().setZ(gyro.z());

            nodeMain.publishGyro(msg);
        }

        @Override
        public void onAccelerometerData(Myo myo, long timestamp, Vector3 accel) {
            if (!mKnownMyoObjs.containsKey(myo)) return;

            MyoNode nodeMain = mKnownMyoObjs.get(myo);
            if (nodeMain.node_ == null) return;
            if (!nodeMain.time_synced_) return;

            Vector3Stamped msg = nodeMain.node_.getTopicMessageFactory().newFromType(Vector3Stamped._TYPE);

            msg.getHeader().setStamp(nodeMain.myoToRosTime(timestamp));

            msg.getVector().setX(accel.x());
            msg.getVector().setY(accel.y());
            msg.getVector().setZ(accel.z());

            nodeMain.publishAccel(msg);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.d("Myo Node", "Preference " + key + " has changed");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

//        Stetho.initializeWithDefaults(this);

//        requestWindowFeature(Window.FEATURE_NO_TITLE);
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.main);

        gson = new Gson();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        Type myoPropertiesType = new TypeToken<HashMap<String, MyoProperties>>(){}.getType();
        mMyoSettings = gson.fromJson(mPrefs.getString("myos", ""), myoPropertiesType);
        // If the setting does not exist yet, create an empty one
        if (mMyoSettings == null) {
            mMyoSettings = new HashMap<>();
            mPrefs.edit().putString("myos", "").apply();
        }

        // First, we initialize the Hub singleton with an application identifier.
        Hub hub = Hub.getInstance();
        if (!hub.init(this, getPackageName())) {
            // We can't do anything with the Myo device if the Hub can't be initialized, so exit.
            Toast.makeText(this, "Couldn't initialize Hub", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        hub.setMyoAttachAllowance(99);
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {
        try {
            java.net.Socket socket = new java.net.Socket(getMasterUri().getHost(), getMasterUri().getPort());
            java.net.InetAddress local_network_address = socket.getLocalAddress();
            socket.close();

            mNodeConfiguration = NodeConfiguration.newPublic(local_network_address.getHostAddress(), getMasterUri());
            mNodeExecutor = nodeMainExecutor;

            // Next, register for DeviceListener callbacks.
            Hub.getInstance().addListener(mListener);
            onScanActionSelected();
        } catch (IOException e) {
            // Socket problem
            Log.e("MyoNode", "socket error trying to get networking information from the master uri");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // We don't want any callbacks when the Activity is gone, so unregister the listener.
        Hub.getInstance().removeListener(mListener);
        if (isFinishing()) {
            // The Activity is finishing, so shutdown the Hub. This will disconnect from the Myo.
            Hub.getInstance().shutdown();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (R.id.action_scan == id) {
            onScanActionSelected();
            return true;
        }
        if (R.id.action_ros_master == id) {
//            startMasterChooser();
            return true;
        }
        if (R.id.action_myo_ids == id) {
            onMyoIDsActionSelected();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onMyoIDsActionSelected() {
//        Intent intent = new Intent(this, MyoIDsActivity.class);
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void onScanActionSelected() {
        // Launch the ScanActivity to scan for Myos to connect to.
        Intent intent = new Intent(this, ScanActivity.class);
        startActivity(intent);
    }
}
