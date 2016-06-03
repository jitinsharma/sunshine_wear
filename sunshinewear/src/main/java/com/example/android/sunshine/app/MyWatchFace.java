/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService{
    private GoogleApiClient mGoogleApiClient;
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private String LOG_TAG = this.getClass().getSimpleName();
    Paint mTextPaintDate;
    Paint mTextHighTemp;
    Paint mTextLowTemp;
    Paint mArcPaint;
    String data;
    String highTemp;
    String lowTemp;
    String time;
    Bitmap icon;
    Date mDate;
    SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.white));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(isInAmbientMode() ? resources.getColor(R.color.white): resources.getColor(R.color.black));

            mTextPaintDate = new Paint();
            mTextPaintDate = createTextPaint(resources.getColor(R.color.black));

            mTextHighTemp = new Paint();
            mTextHighTemp = createTextPaint(resources.getColor(R.color.white));

            mTextLowTemp = new Paint();
            mTextLowTemp = createTextPaint(resources.getColor(R.color.white));

            mArcPaint = new Paint();
            mArcPaint = createTextPaint(resources.getColor(R.color.background));

            mTime = new Time();
            mDate = new Date();

            mGoogleApiClient = new GoogleApiClient.Builder(getBaseContext())
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(Bundle bundle) {
                            Log.d(LOG_TAG, "API client connected");
                            final DataApi.DataListener dataListener = new DataApi.DataListener() {
                                @Override
                                public void onDataChanged(DataEventBuffer dataEventBuffer) {
                                    Log.e(LOG_TAG, "onDataChanged(): " + dataEventBuffer);
                                    for (DataEvent event: dataEventBuffer){
                                        if (event.getType() == DataEvent.TYPE_CHANGED){
                                            DataItem dataItem = event.getDataItem();
                                            if (dataItem.getUri().getPath().equals("/weather")){
                                                DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                                                data = dataMap.getString("DATA");
                                                Log.d(LOG_TAG, "data " + data);
                                                try {
                                                    JSONObject dataObject = new JSONObject(data);
                                                    Log.d(LOG_TAG, "data json object " + dataObject);
                                                    highTemp = dataObject.getString("HIGH");
                                                    lowTemp = dataObject.getString("LOW");
                                                    time = dataObject.getString("TIME");
                                                    Log.d(LOG_TAG, "high temp:" + highTemp);
                                                    Log.d(LOG_TAG, "low temp:" + lowTemp);
                                                    //Toast.makeText(getBaseContext(), "temp: " + highTemp + lowTemp, Toast.LENGTH_LONG).show();
                                                } catch (JSONException e) {
                                                    e.printStackTrace();
                                                }
                                                Asset assetIcon = dataMap.getAsset("ICON");
                                                icon = loadBitmapFromAsset(assetIcon);
                                            }
                                        }
                                    }
                                }
                            };
                            Wearable.DataApi.addListener(mGoogleApiClient, dataListener);
                        }

                        @Override
                        public void onConnectionSuspended(int i) {
                            Log.d(LOG_TAG, "API client connection suspended");
                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                            Log.d(LOG_TAG, "API client connection failed");
                            Log.d(LOG_TAG, "" + connectionResult.getErrorCode());
                            Log.d(LOG_TAG, "" + connectionResult.getResolution());
                        }
                    })
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(Typeface.SANS_SERIF);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(60);
            mTextPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextHighTemp.setTextSize(25);
            mTextLowTemp.setTextSize(25);
            mTextPaintDate.setTextSize(25);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = MyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(R.color.background));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            Log.d(LOG_TAG, "Drawing");
            Log.d(LOG_TAG, "Drawing " + highTemp);
            int xPos = (canvas.getWidth() / 2);
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                mTextPaint.setColor(getResources().getColor(R.color.white));
            } else {
                mTextPaint.setColor(getResources().getColor(R.color.black));
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                float width = (float)canvas.getWidth();
                float height = (float)canvas.getHeight();
                float radius;

                if (width > height){
                    radius = height/2.0f;
                }else{
                    radius = width/2.0f;
                }

                float center_x = width * 0.5f;
                float center_y = height * 1.0f;
                final RectF rect = new RectF();
                rect.set(center_x - radius,
                        center_y - radius,
                        center_x + radius,
                        center_y + radius);
                canvas.drawRoundRect(rect, 100, 100, mArcPaint);

                String highTempText = "Sync with phone";
                String lowTempText = "";

                if (highTemp!=null){
                    highTempText = (char) 0x2191 + highTemp;
                }
                if (lowTemp!=null){
                    lowTempText = (char) 0x2191 + lowTemp;
                }
                String date = "Thu, 02 Jun 2016";
                canvas.drawText(highTempText, mXOffset*2.5f, mYOffset*3.5f, mTextHighTemp);
                canvas.drawText(lowTempText, mXOffset*4.5f, mYOffset*3.5f, mTextLowTemp);
                canvas.drawText(dateFormat.format(mDate), mXOffset*2, mYOffset*(1.5f), mTextPaintDate);
                if (icon!=null){
                    canvas.drawBitmap(icon, mXOffset*3.5f, mYOffset*2.5f,null);
                }
                else{
                }
            }

            //canvas.drawRect(0, bounds.bottom, bounds.width(), bounds.width()/2, mArcPaint);
            //canvas.drawRoundRect(30, 60, 100, 240, mXOffset, mYOffset*2, mArcPaint);

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);

            canvas.drawText(text, xPos, mYOffset, mTextPaint);
            //String tempText = highTemp;
            //String lowTemp;
            //String highTempText = (char) 0x2191 + "20" + (char) 0x00B0;
            //String lowTempText = (char) 0x2193 + "12" + (char) 0x00B0;
            //canvas.drawText(tempText, mXOffset, mYOffset, mTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                Log.w(LOG_TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }
    }
}
