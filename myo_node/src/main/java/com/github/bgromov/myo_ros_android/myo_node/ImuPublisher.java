package com.github.bgromov.myo_ros_android.myo_node;

/*
 * Copyright (c) 2011, Chad Rockey
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Android Sensors Driver nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.util.Arrays;
import java.util.List;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import android.util.Log;

import org.ros.node.ConnectedNode;
import org.ros.namespace.GraphName;

import geometry_msgs.PoseStamped;
import geometry_msgs.QuaternionStamped;
import geometry_msgs.TransformStamped;
import sensor_msgs.Imu;
import tf2_msgs.TFMessage;

import org.ros.node.Node;
import org.ros.node.NodeMain;
import org.ros.node.topic.Publisher;

import org.ros.rosjava_geometry.Quaternion;

/**
 * @author chadrockey@gmail.com (Chad Rockey)
 * @author axelfurlan@gmail.com (Axel Furlan)
 */
public class ImuPublisher implements NodeMain
{

    private String fixed_frame_id;
    private String imu_frame_id;
    private String node_ns;
    private ImuThread imuThread;
    private SensorListener sensorListener;
    private SensorManager sensorManager;
    private ConnectedNode node_;
    private Publisher<Imu> pub_imu;
    private Publisher<TFMessage> pub_tf;
    private Publisher<QuaternionStamped> pub_rotation;

    private class ImuThread extends Thread
    {
        private final SensorManager sensorManager;
        private SensorListener sensorListener;
        private Looper threadLooper;

        private final Sensor accelSensor;
        private final Sensor gyroSensor;
        private final Sensor quatSensor;

        private ImuThread(SensorManager sensorManager, SensorListener sensorListener)
        {
            this.sensorManager = sensorManager;
            this.sensorListener = sensorListener;
            this.accelSensor = this.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            this.gyroSensor = this.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            this.quatSensor = this.sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }


        public void run()
        {
            Looper.prepare();
            this.threadLooper = Looper.myLooper();
//            this.sensorManager.registerListener(this.sensorListener, this.accelSensor, SensorManager.SENSOR_DELAY_FASTEST);
//            this.sensorManager.registerListener(this.sensorListener, this.gyroSensor, SensorManager.SENSOR_DELAY_FASTEST);
            this.sensorManager.registerListener(this.sensorListener, this.quatSensor, SensorManager.SENSOR_DELAY_GAME);
            Looper.loop();
        }


        public void shutdown()
        {
            this.sensorManager.unregisterListener(this.sensorListener);
            if(this.threadLooper != null)
            {
                this.threadLooper.quit();
            }
        }
    }

    private class SensorListener implements SensorEventListener
    {
        private Publisher<Imu> pub_imu;
        private Publisher<TFMessage> pub_tf;
        private Publisher<geometry_msgs.QuaternionStamped> pub_rotation;

        private boolean hasAccel;
        private boolean hasGyro;
        private boolean hasQuat;

        private long accelTime;
        private long gyroTime;
        private long quatTime;

        private Imu imu_msg;
        private TransformStamped geom_tf_msg;
        private QuaternionStamped rotation_msg;

        private SensorListener(Publisher<Imu> pub_imu, Publisher<TFMessage> pub_tf, Publisher<QuaternionStamped> pub_rotation,
                               boolean hasAccel, boolean hasGyro, boolean hasQuat)
        {
            this.pub_imu = pub_imu;
            this.pub_tf = pub_tf;
            this.pub_rotation = pub_rotation;

            this.hasAccel = hasAccel;
            this.hasGyro = hasGyro;
            this.hasQuat = hasQuat;

            this.accelTime = 0;
            this.gyroTime = 0;
            this.quatTime = 0;

            this.imu_msg = this.pub_imu.newMessage();
            this.geom_tf_msg = node_.getTopicMessageFactory().newFromType(TransformStamped._TYPE);
            this.rotation_msg = this.pub_rotation.newMessage();
        }

        //	@Override
        public void onAccuracyChanged(Sensor sensor, int accuracy)
        {
        }

        public void sendTransform(geometry_msgs.TransformStamped msg) {
            tf2_msgs.TFMessage tf_msg = pub_tf.newMessage();
            tf_msg.setTransforms(Arrays.asList(msg));
            pub_tf.publish(tf_msg);
        }

        //	@Override
        public void onSensorChanged(SensorEvent event)
        {
            org.ros.message.Time now = node_.getCurrentTime();

            if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            {
                this.imu_msg.getLinearAcceleration().setX(event.values[0]);
                this.imu_msg.getLinearAcceleration().setY(event.values[1]);
                this.imu_msg.getLinearAcceleration().setZ(event.values[2]);

                double[] tmpCov = {0.01,0,0, 0,0.01,0, 0,0,0.01};// TODO Make Parameter
                this.imu_msg.setLinearAccelerationCovariance(tmpCov);
                this.accelTime = event.timestamp;
            }
            else if(event.sensor.getType() == Sensor.TYPE_GYROSCOPE)
            {
                this.imu_msg.getAngularVelocity().setX(event.values[0]);
                this.imu_msg.getAngularVelocity().setY(event.values[1]);
                this.imu_msg.getAngularVelocity().setZ(event.values[2]);
                double[] tmpCov = {0.0025,0,0, 0,0.0025,0, 0,0,0.0025};// TODO Make Parameter
                this.imu_msg.setAngularVelocityCovariance(tmpCov);
                this.gyroTime = event.timestamp;
            }
            else if(event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR)
            {
                float[] quaternion = new float[4];


                SensorManager.getQuaternionFromVector(quaternion, event.values);

                org.ros.rosjava_geometry.Quaternion quat_tmp = (new Quaternion(quaternion[1], quaternion[2], quaternion[3], quaternion[0])).normalize();

                quat_tmp.toQuaternionMessage(this.imu_msg.getOrientation());

                double[] tmpCov = {0.001,0,0, 0,0.001,0, 0,0,0.001};// TODO Make Parameter
                this.imu_msg.setOrientationCovariance(tmpCov);
                this.quatTime = event.timestamp;

                this.rotation_msg.getHeader().setFrameId(fixed_frame_id);
                this.rotation_msg.getHeader().setStamp(now);
                this.rotation_msg.setQuaternion(this.imu_msg.getOrientation());

                this.geom_tf_msg.setChildFrameId(imu_frame_id);
                this.geom_tf_msg.setHeader(this.rotation_msg.getHeader());
                this.geom_tf_msg.getTransform().setRotation(this.imu_msg.getOrientation());

                this.sendTransform(this.geom_tf_msg);
                pub_rotation.publish(this.rotation_msg);

                this.geom_tf_msg = node_.getTopicMessageFactory().newFromType(TransformStamped._TYPE);
                this.rotation_msg = this.pub_rotation.newMessage();
            }

            // Currently storing event times in case I filter them in the future.  Otherwise they are used to determine if all sensors have reported.
            if((this.accelTime != 0 || !this.hasAccel) && (this.gyroTime != 0 || !this.hasGyro) && (this.quatTime != 0 || !this.hasQuat))
            {
//                // Convert event.timestamp (nanoseconds uptime) into system time, use that as the header stamp
//                long time_delta_millis = System.currentTimeMillis() - SystemClock.uptimeMillis();
//                this.imu_msg.getHeader().setStamp(Time.fromMillis(time_delta_millis + event.timestamp/1000000));
                this.imu_msg.getHeader().setStamp(now);
                this.imu_msg.getHeader().setFrameId(imu_frame_id);

                pub_imu.publish(this.imu_msg);

                // Create a new message
                this.imu_msg = this.pub_imu.newMessage();

                // Reset times
                this.accelTime = 0;
                this.gyroTime = 0;
                this.quatTime = 0;
            }
        }
    }

    public ImuPublisher(SensorManager manager)
    {
        this.sensorManager = manager;
        this.node_ns = "idsia";
        this.fixed_frame_id = "/world";
        this.imu_frame_id = "body_imu";
    }

    public GraphName getDefaultNodeName()
    {
        return GraphName.of("myo_ros_android/imu_node");
    }

    public void onError(Node node, Throwable throwable)
    {
    }

    public void onStart(ConnectedNode node)
    {
        try
        {
            this.node_ = node;

//            ParameterTree params = node_.getParameterTree();

            this.pub_imu = node.newPublisher(node_ns + "/android/imu", Imu._TYPE);
            this.pub_tf = node.newPublisher("/tf", tf2_msgs.TFMessage._TYPE);
            this.pub_rotation = node.newPublisher(node_ns + "/android/rotation", QuaternionStamped._TYPE);
            // 	Determine if we have the various needed sensors
            boolean hasAccel = false;
            boolean hasGyro = false;
            boolean hasQuat = false;

            List<Sensor> accelList = this.sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);

            if(accelList.size() > 0)
            {
                hasAccel = true;
            }

            List<Sensor> gyroList = this.sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE);
            if(gyroList.size() > 0)
            {
                hasGyro = true;
            }

            List<Sensor> quatList = this.sensorManager.getSensorList(Sensor.TYPE_ROTATION_VECTOR);
            if(quatList.size() > 0)
            {
                hasQuat = true;
            }

            this.sensorListener = new SensorListener(pub_imu, pub_tf, pub_rotation, hasAccel, hasGyro, hasQuat);
            this.imuThread = new ImuThread(this.sensorManager, sensorListener);
            this.imuThread.start();
        }
        catch (Exception e)
        {
            if (node != null)
            {
                node.getLog().fatal(e);
            }
            else
            {
                e.printStackTrace();
            }
        }
    }

    //@Override
    public void onShutdown(Node arg0)
    {
        this.imuThread.shutdown();

        try
        {
            this.imuThread.join();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    //@Override
    public void onShutdownComplete(Node arg0)
    {
    }

}