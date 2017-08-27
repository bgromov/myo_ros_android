package com.github.bgromov.myo_ros_android.myo_node;

import android.util.Log;

import com.google.common.primitives.UnsignedInteger;
import com.thalmic.myo.Myo;

import org.ros.message.MessageListener;
import org.ros.message.Time;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.lang.String;
import java.util.Map;

import geometry_msgs.QuaternionStamped;
import geometry_msgs.TransformStamped;
import geometry_msgs.Vector3Stamped;
import myo_ros.GestureStamped;
import myo_ros.Vibration;
import std_msgs.Bool;

import tf2_msgs.TFMessage;

/**
 * Created by 0xff on 07/04/17.
 */

public class MyoNode extends AbstractNodeMain {
    private ParameterTree params_;
    private final String fixed_frame_id_ = "/world";
    private String frame_id_;
    private String ns_;

    public int getMyoID() {
        return id_;
    }

    private int id_;
    private Myo myo_;
    private long mTimeOffsetUs = 0;
    private boolean use_ros_timestamps_ = false;

    private Publisher<QuaternionStamped> pub_rotation_;
    private Publisher<myo_ros.GestureStamped> pub_gesture_;
    private Publisher<geometry_msgs.Vector3Stamped> pub_gyro_;
    private Publisher<geometry_msgs.Vector3Stamped> pub_accel_;
    private Publisher<myo_ros.StatusStamped> pub_status_;
    private Subscriber<myo_ros.Vibration> sub_vibration_;
    private Subscriber<std_msgs.Bool> sub_unlock_override_;

    private Publisher<tf2_msgs.TFMessage> pub_tf_;

    public ConnectedNode node_;

    public long sync_count_ = 0;
    public boolean time_synced_ = false;

    public long acc_offset = 0;

    private Map<Long, Long> time_stats_ = new HashMap<>();

    private Map<Long, org.ros.message.Time> time_cache_ = new LinkedHashMap<Long, org.ros.message.Time>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, org.ros.message.Time> eldest) {
            return  this.size() > 10;
        }
    };

    public MyoNode(Myo myo, int id, long timestamp){
        myo_ = myo;
        id_ = id;
    }

    public boolean syncToRosTime(long timestamp) {
        if (use_ros_timestamps_) {
            time_synced_ = true;
            return true;
        }
        if (!time_synced_) {
            if (sync_count_ < 250) {
                org.ros.message.Time now = node_.getCurrentTime();
                long offset = now.totalNsecs() / 1000 - timestamp;

                //            long delta = (mTimeOffsetUs - offset);
                //            Log.i("Myo" + id_, "Time offset: " + mTimeOffsetUs + " Delta: " + delta);

                time_stats_.put(timestamp, offset);

                //            mTimeOffsetUs = offset;

                sync_count_++;

                return false;
            } else {
                List<Long> a = new ArrayList<>(time_stats_.values());
                Collections.sort(a);
                if (a.size() % 2 == 0) {
                    mTimeOffsetUs = (a.get(a.size() / 2) + a.get(a.size() / 2 - 1)) / 2;
                } else
                {
                    mTimeOffsetUs = a.get((a.size() - 1) / 2);
                }
                time_synced_ = true;
                Log.i("Myo" + id_, "Median time offset with ROS [us]: " + mTimeOffsetUs);
            }
        }
        return true;
    }

    public org.ros.message.Time myoToRosTime(long timestamp) {
        org.ros.message.Time now = node_.getCurrentTime();

        if (mTimeOffsetUs == 0) {
            mTimeOffsetUs =  now.subtract(org.ros.message.Time.fromNano(timestamp * 1000)).totalNsecs() / 1000;
        }
        // Myo timestamp is in microseconds
        long new_ts = timestamp + mTimeOffsetUs;

        if (use_ros_timestamps_) {
            if (!time_cache_.containsKey(timestamp)) {
                time_cache_.put(timestamp, now);
            } else {
//                Log.i("Myo" + id_, " timestamp already exists: " + timestamp);
            }
            // Get cached time
//            Log.i("Myo" + id_, " cached timestamp: " + time_cache_.get(timestamp));
            return time_cache_.get(timestamp);
        } else {
//            return new org.ros.message.Time((int) (new_ts / 1000000), (int) ((new_ts % 1000000) * 1000));
            return org.ros.message.Time.fromNano(new_ts * 1000);
        }
    }

    @Override
    public void onStart(ConnectedNode connectedNode) {
        node_ = connectedNode;

        // Kinda tf broadcaster
        pub_tf_ = node_.newPublisher("/tf", tf2_msgs.TFMessage._TYPE);


        params_ = node_.getParameterTree();
        List<String> macs = new ArrayList<String>();
        params_.getList("/idsia/myo/static_myo_ids", macs);
        params_.getBoolean("/idsia/myo/use_ros_timestamps", use_ros_timestamps_);

        use_ros_timestamps_ = true;

        if (use_ros_timestamps_) Log.i("onStart", "Using ROS timestamps for Myo messages");

        for (int i = 0; i < macs.size(); i++) {
            if (myo_.getMacAddress().replace(":", "-").toLowerCase() == macs.get(i).replace(":", "-").toLowerCase()) {
                id_ = i + 1;
                break;
            }
        }

        ns_ = "/idsia/myo" + Integer.toString(id_);
        frame_id_ = ns_ + "_frame";

        pub_rotation_ = node_.newPublisher(ns_ + "/rotation", QuaternionStamped._TYPE);
        pub_gesture_ = node_.newPublisher(ns_ + "/gesture", GestureStamped._TYPE);
        pub_gyro_ = node_.newPublisher(ns_ + "/gyro", Vector3Stamped._TYPE);
        pub_accel_ = node_.newPublisher(ns_ + "/accel", Vector3Stamped._TYPE);
        pub_status_ = node_.newPublisher(ns_ + "/status", myo_ros.StatusStamped._TYPE);

        sub_vibration_ = node_.newSubscriber(ns_ + "/vibration", myo_ros.Vibration._TYPE);

        sub_vibration_.addMessageListener(new MessageListener<myo_ros.Vibration>() {
            @Override
            public void onNewMessage(myo_ros.Vibration msg) {
                if (myo_ != null) {
                    myo_.vibrate((Myo.VibrationType.values()[msg.getVibration()]));
                }
            }
        });

        sub_unlock_override_ = node_.newSubscriber(ns_ + "/unlock_override", std_msgs.Bool._TYPE);
        sub_unlock_override_.addMessageListener(new MessageListener<std_msgs.Bool>() {
            @Override
            public void onNewMessage(std_msgs.Bool msg) {
                if (myo_ != null) {
                    if (msg.getData() == true) {
                        myo_.unlock(Myo.UnlockType.HOLD);
                    } else {
                        myo_.unlock(Myo.UnlockType.TIMED);
                    }
                }
            }
        });

        synchronized (this) {
            this.notifyAll();
        }
    }

    @Override
    public GraphName getDefaultNodeName() {
        return GraphName.of("myo_ros_android/myo_node" + Integer.toString(id_));
    }

    public void sendTransform(geometry_msgs.TransformStamped msg) {
        tf2_msgs.TFMessage tf_msg = pub_tf_.newMessage();
        tf_msg.setTransforms(Arrays.asList(msg));
        pub_tf_.publish(tf_msg);
    }

    public void publishRotation(geometry_msgs.QuaternionStamped msg) {
        msg.getHeader().setFrameId(fixed_frame_id_);

        geometry_msgs.TransformStamped tfs = node_.getTopicMessageFactory().newFromType(geometry_msgs.TransformStamped._TYPE);

        tfs.setHeader(msg.getHeader());
        tfs.setChildFrameId(frame_id_);
        tfs.getTransform().setRotation(msg.getQuaternion());

        pub_rotation_.publish(msg);
        this.sendTransform(tfs);
    }

    public void publishGesture(myo_ros.GestureStamped msg) {
        msg.getHeader().setFrameId(fixed_frame_id_);

        pub_gesture_.publish(msg);
    }

    public void publishGyro(geometry_msgs.Vector3Stamped msg) {
        msg.getHeader().setFrameId(fixed_frame_id_);

        pub_gyro_.publish(msg);
    }

    public void publishAccel(geometry_msgs.Vector3Stamped msg) {
        msg.getHeader().setFrameId(fixed_frame_id_);

        pub_accel_.publish(msg);
    }

    public void publishStatus(myo_ros.StatusStamped msg) {
        msg.getHeader().setFrameId(fixed_frame_id_);

        pub_status_.publish(msg);
    }
}
