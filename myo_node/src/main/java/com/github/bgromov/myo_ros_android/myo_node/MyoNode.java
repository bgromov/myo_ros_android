package com.github.bgromov.myo_ros_android.myo_node;

import com.google.common.primitives.UnsignedInteger;
import com.thalmic.myo.Myo;

import org.ros.message.MessageListener;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;
import org.ros.node.topic.Publisher;
import org.ros.node.topic.Subscriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.lang.String;

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

    private Publisher<QuaternionStamped> pub_rotation_;
    private Publisher<myo_ros.GestureStamped> pub_gesture_;
    private Publisher<geometry_msgs.Vector3Stamped> pub_gyro_;
    private Publisher<geometry_msgs.Vector3Stamped> pub_accel_;
    private Publisher<myo_ros.StatusStamped> pub_status_;
    private Subscriber<myo_ros.Vibration> sub_vibration_;
    private Subscriber<std_msgs.Bool> sub_unlock_override_;

    private Publisher<tf2_msgs.TFMessage> pub_tf_;

    public ConnectedNode node_;

    public MyoNode(Myo myo, int id, long timeOffset){
        myo_ = myo;
        id_ = id;
        mTimeOffsetUs = timeOffset;
    }

    public org.ros.message.Time myoToRosTime(long timestamp) {
        if (mTimeOffsetUs == 0) {
            mTimeOffsetUs = node_.getCurrentTime().totalNsecs() / 1000 - timestamp;
        }
        // Myo timestamp is in microseconds
        long new_ts = timestamp + mTimeOffsetUs;
        return new org.ros.message.Time((int) new_ts / 1000000, (int) (new_ts % 1000000) * 1000);
    }

    @Override
    public void onStart(ConnectedNode connectedNode) {
        node_ = connectedNode;

        // Kinda tf broadcaster
        pub_tf_ = node_.newPublisher("/tf", tf2_msgs.TFMessage._TYPE);


        params_ = node_.getParameterTree();
        List<String> macs = new ArrayList<String>();
        params_.getList("~/static_myo_ids", macs);

        for (int i = 0; i < macs.size(); i++) {
            if (myo_.getMacAddress().replace(":", "-").toLowerCase() == macs.get(i).replace(":", "-").toLowerCase()) {
                id_ = i + 1;
                break;
            }
        }

        ns_ = "myo" + Integer.toString(id_);
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
