package com.github.bgromov.myo_ros_android.myo_node;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

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

import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMainExecutor;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import geometry_msgs.QuaternionStamped;
import geometry_msgs.Vector3Stamped;
import myo_ros.GestureStamped;
import myo_ros.StatusStamped;

public class MyoService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
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
    private SensorManager mSensorManager;
    private ImuPublisher imu_pub;
    private PowerManager mPowerManager;

    public MyoService() {
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
//            // Acquire wake lock
//            wakeLock.acquire();

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

//            TextView text_view = (TextView) findViewById(R.id.main_view);
//            String text = "";
//            for (Myo m : mKnownMyoObjs.keySet()) {
//                text += m.getName() + " [" + m.getMacAddress() + "]\n";
//            }
//            text_view.setText(text);
        }

        @Override
        public void onDetach(Myo myo, long timestamp) {
            removeMyo(myo);

//            // Release wake lock
//            wakeLock.release();
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
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.d("Myo Node", "Preference " + key + " has changed");
    }

    @Override
    public void onCreate() {
        super.onCreate();

//        Stetho.initializeWithDefaults(this);

//        requestWindowFeature(Window.FEATURE_NO_TITLE);
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
            stopSelf();
            return;
        }
        hub.setMyoAttachAllowance(99);

        mSensorManager = (SensorManager)this.getSystemService(SENSOR_SERVICE);
        mPowerManager = (PowerManager)this.getSystemService(Context.POWER_SERVICE);
//        wakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName());

        Log.w("MyoService", "Created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // We don't want any callbacks when the Activity is gone, so unregister the listener.
        Hub.getInstance().removeListener(mListener);
        // The Activity is finishing, so shutdown the Hub. This will disconnect from the Myo.
        Hub.getInstance().shutdown();
    }
}
