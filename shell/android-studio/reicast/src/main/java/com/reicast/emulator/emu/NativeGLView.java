package com.reicast.emulator.emu;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import com.reicast.emulator.Emulator;
import com.reicast.emulator.NativeGLActivity;
import com.reicast.emulator.R;
import com.reicast.emulator.config.Config;
import com.reicast.emulator.periph.InputDeviceManager;
import com.reicast.emulator.periph.VJoy;

import java.io.UnsupportedEncodingException;

public class NativeGLView extends SurfaceView {
    private static String fileName;
    private EmuThread ethd;
    private Handler handler = new Handler();

    Vibrator vib;

    private boolean editVjoyMode = false;
    private int selectedVjoyElement = -1;
    private ScaleGestureDetector scaleGestureDetector;

    public float[][] vjoy_d_custom;

    private static final float[][] vjoy = VJoy.baseVJoy();

    private boolean touchVibrationEnabled;
    private int vibrationDuration;
    Context context;

    public void restoreCustomVjoyValues(float[][] vjoy_d_cached) {
        vjoy_d_custom = vjoy_d_cached;
        VJoy.writeCustomVjoyValues(vjoy_d_cached, context);

        resetEditMode();
        requestLayout();
    }

    public NativeGLView(Context context) {
        super(context);
    }

    public NativeGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public NativeGLView(final Context context, String newFileName, boolean editVjoyMode) {
        super(context);
        this.context = context;
        this.editVjoyMode = editVjoyMode;
        setKeepScreenOn(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            setOnSystemUiVisibilityChangeListener (new OnSystemUiVisibilityChangeListener() {
                public void onSystemUiVisibilityChange(int visibility) {
                    if ((visibility & SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        NativeGLView.this.setSystemUiVisibility(
                                SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                        | SYSTEM_UI_FLAG_FULLSCREEN
                                        | SYSTEM_UI_FLAG_HIDE_NAVIGATION);
                        requestLayout();
                    }
                }
            });
        }

        vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        JNIdc.screenDpi((int)Math.max(dm.xdpi, dm.ydpi));

        JNIdc.config(prefs.getString(Config.pref_home,
                Environment.getExternalStorageDirectory().getAbsolutePath()));

        ethd = new EmuThread(!Emulator.nosound);

        touchVibrationEnabled = prefs.getBoolean(Config.pref_touchvibe, true);
        vibrationDuration = prefs.getInt(Config.pref_vibrationDuration, 20);

        this.setLayerType(prefs.getInt(Config.pref_rendertype, LAYER_TYPE_HARDWARE), null);

        vjoy_d_custom = VJoy.readCustomVjoyValues(context);

        scaleGestureDetector = new ScaleGestureDetector(context, new OscOnScaleGestureListener());

        // This is the game we are going to run
        fileName = newFileName;

        if (NativeGLActivity.syms != null)
            JNIdc.data(1, NativeGLActivity.syms);

        final String initStatus = JNIdc.init(fileName);
        if (initStatus != null)
        {
            handler.post(new Runnable() {
                public void run() {
                    Log.e("initialization", "dc_init: " + initStatus);
                    Toast.makeText(context, initStatus, Toast.LENGTH_SHORT).show();
                }
            });


            throw new EmulatorInitFailed();
        }
        JNIdc.query(ethd);

        // Set the renderer responsible for frame rendering
        //setRenderer(rend = new GL2JNIView.Renderer(this));
        handler.post(new Runnable() {
            @Override
            public void run() {
                // FIXME STOP AT SOME POINT!!
                if (ethd.getState() == Thread.State.TERMINATED)
                    ((Activity)getContext()).finish();
                else {
                    JNIdc.rendframe();
                    handler.post(this);
                }
            }
        });

        ethd.start();

    }

    private void reset_analog()
    {

        int j=11;
        vjoy[j+1][0]=vjoy[j][0]+vjoy[j][2]/2-vjoy[j+1][2]/2;
        vjoy[j+1][1]=vjoy[j][1]+vjoy[j][3]/2-vjoy[j+1][3]/2;
        JNIdc.vjoy(j+1, vjoy[j+1][0], vjoy[j+1][1], vjoy[j+1][2], vjoy[j+1][3]);
    }

    int get_anal(int j, int axis)
    {
        return (int) (((vjoy[j+1][axis]+vjoy[j+1][axis+2]/2) - vjoy[j][axis] - vjoy[j][axis+2]/2)*254/vjoy[j][axis+2]);
    }

    float vbase(float p, float m, float scl)
    {
        return (int) ( m - (m -p)*scl);
    }

    float vbase(float p, float scl)
    {
        return (int) (p*scl );
    }

    public boolean isTablet() {
        return (getContext().getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK)
                >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom)
    {
        super.onLayout(changed, left, top, right, bottom);
        //dcpx/cm = dcpx/px * px/cm
        float magic = isTablet() ? 0.8f : 0.7f;
        float scl = 480.0f/getHeight() * getContext().getResources().getDisplayMetrics().density * magic;
        float scl_dc = getHeight()/480.0f;
        float tx  = ((getWidth()-640.0f*scl_dc)/2)/scl_dc;

        float a_x = -tx+ 24*scl;
        float a_y=- 24*scl;

        float[][] vjoy_d = VJoy.getVjoy_d(vjoy_d_custom);

        for (int i=0;i<vjoy.length;i++)
        {
            if (vjoy_d[i][0] == 288)
                vjoy[i][0] = vjoy_d[i][0];
            else if (vjoy_d[i][0]-vjoy_d_custom[getElementIdFromButtonId(i)][0] < 320)
                vjoy[i][0] = a_x + vbase(vjoy_d[i][0],scl);
            else
                vjoy[i][0] = -a_x + vbase(vjoy_d[i][0],640,scl);

            vjoy[i][1] = a_y + vbase(vjoy_d[i][1],480,scl);

            vjoy[i][2] = vbase(vjoy_d[i][2],scl);
            vjoy[i][3] = vbase(vjoy_d[i][3],scl);
        }

        for (int i=0;i<VJoy.VJoyCount;i++)
            JNIdc.vjoy(i,vjoy[i][0],vjoy[i][1],vjoy[i][2],vjoy[i][3]);

        reset_analog();
        VJoy.writeCustomVjoyValues(vjoy_d_custom, context);
    }

    int anal_id=-1, lt_id=-1, rt_id=-1;

    public void resetEditMode() {
        editLastX = 0;
        editLastY = 0;
    }

    private static int getElementIdFromButtonId(int buttonId) {
        if (buttonId <= 3)
            return 0; // DPAD
        else if (buttonId <= 7)
            return 1; // X, Y, B, A Buttons
        else if (buttonId == 8)
            return 2; // Start
        else if (buttonId == 9)
            return 3; // Left Trigger
        else if (buttonId == 10)
            return 4; // Right Trigger
        else if (buttonId <= 12)
            return 5; // Analog
        else
            return 0; // DPAD diagonials
    }

    public static int left_trigger = 0;
    public static int right_trigger = 0;
    public static int[] mouse_pos = { -32768, -32768 };
    public static int mouse_btns = 0;

    float editLastX = 0, editLastY = 0;

    @Override public boolean onTouchEvent(final MotionEvent event)
    {
        if (event.getSource() != InputDevice.SOURCE_TOUCHSCREEN)
            // Ignore real mice, trackballs, etc.
            return false;
        JNIdc.show_osd();

        scaleGestureDetector.onTouchEvent(event);

        float ty = 0.0f;
        float scl = getHeight()/480.0f;
        float tx = (getWidth()-640.0f*scl)/2;

        int rv = 0xFFFF;

        int aid = event.getActionMasked();
        int pid = event.getActionIndex();

        if (!JNIdc.guiIsOpen()) {
            if (editVjoyMode && selectedVjoyElement != -1 && aid == MotionEvent.ACTION_MOVE && !scaleGestureDetector.isInProgress()) {
                float x = (event.getX() - tx) / scl;
                float y = (event.getY() - ty) / scl;

                if (editLastX != 0 && editLastY != 0) {
                    float deltaX = x - editLastX;
                    float deltaY = y - editLastY;

                    vjoy_d_custom[selectedVjoyElement][0] += isTablet() ? deltaX * 2 : deltaX;
                    vjoy_d_custom[selectedVjoyElement][1] += isTablet() ? deltaY * 2 : deltaY;

                    requestLayout();
                }

                editLastX = x;
                editLastY = y;

                return true;
            }

            for (int i = 0; i < event.getPointerCount(); i++) {
                float x = (event.getX(i) - tx) / scl;
                float y = (event.getY(i) - ty) / scl;
                if (anal_id != event.getPointerId(i)) {
                    if (aid == MotionEvent.ACTION_POINTER_UP && pid == i)
                        continue;
                    for (int j = 0; j < vjoy.length; j++) {
                        if (x > vjoy[j][0] && x <= (vjoy[j][0] + vjoy[j][2])) {
                            /*
                                //Disable pressure sensitive R/L
                                //Doesn't really work properly

                                int pre=(int)(event.getPressure(i)*255);
                                if (pre>20)
                                {
                                    pre-=20;
                                    pre*=7;
                                }
                                if (pre>255) pre=255;
                            */

                            int pre = 255;

                            if (y > vjoy[j][1] && y <= (vjoy[j][1] + vjoy[j][3])) {
                                if (vjoy[j][4] >= -2) {
                                    if (vjoy[j][5] == 0)
                                        if (!editVjoyMode && touchVibrationEnabled)
                                            vib.vibrate(vibrationDuration);
                                    vjoy[j][5] = 2;
                                }


                                if (vjoy[j][4] == -3) {
                                    if (editVjoyMode) {
                                        selectedVjoyElement = 5; // Analog
                                        resetEditMode();
                                    } else {
                                        vjoy[j + 1][0] = x - vjoy[j + 1][2] / 2;
                                        vjoy[j + 1][1] = y - vjoy[j + 1][3] / 2;

                                        JNIdc.vjoy(j + 1, vjoy[j + 1][0], vjoy[j + 1][1], vjoy[j + 1][2], vjoy[j + 1][3]);
                                        anal_id = event.getPointerId(i);
                                    }
                                } else if (vjoy[j][4] != -4) {
                                    if (vjoy[j][4] == -1) {
                                        if (editVjoyMode) {
                                            selectedVjoyElement = 3; // Left Trigger
                                            resetEditMode();
                                        } else {
                                            left_trigger = pre;
                                            lt_id = event.getPointerId(i);
                                        }
                                    } else if (vjoy[j][4] == -2) {
                                        if (editVjoyMode) {
                                            selectedVjoyElement = 4; // Right Trigger
                                            resetEditMode();
                                        } else {
                                            right_trigger = pre;
                                            rt_id = event.getPointerId(i);
                                        }
                                    } else {
                                        if (editVjoyMode) {
                                            selectedVjoyElement = getElementIdFromButtonId(j);
                                            resetEditMode();
                                        } else
                                            rv &= ~(int) vjoy[j][4];
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (x < vjoy[11][0])
                        x = vjoy[11][0];
                    else if (x > (vjoy[11][0] + vjoy[11][2]))
                        x = vjoy[11][0] + vjoy[11][2];

                    if (y < vjoy[11][1])
                        y = vjoy[11][1];
                    else if (y > (vjoy[11][1] + vjoy[11][3]))
                        y = vjoy[11][1] + vjoy[11][3];

                    int j = 11;
                    vjoy[j + 1][0] = x - vjoy[j + 1][2] / 2;
                    vjoy[j + 1][1] = y - vjoy[j + 1][3] / 2;

                    JNIdc.vjoy(j + 1, vjoy[j + 1][0], vjoy[j + 1][1], vjoy[j + 1][2], vjoy[j + 1][3]);

                }
            }

            for (int j = 0; j < vjoy.length; j++) {
                if (vjoy[j][5] == 2)
                    vjoy[j][5] = 1;
                else if (vjoy[j][5] == 1)
                    vjoy[j][5] = 0;
            }
        }

        switch(aid)
        {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                selectedVjoyElement = -1;
                reset_analog();
                anal_id = -1;
                rv = 0xFFFF;
                right_trigger = 0;
                left_trigger = 0;
                lt_id = -1;
                rt_id = -1;
                for (int j= 0 ;j < vjoy.length; j++)
                    vjoy[j][5] = 0;
                mouse_btns = 0;
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex())==anal_id)
                {
                    reset_analog();
                    anal_id = -1;
                }
                else if (event.getPointerId(event.getActionIndex())==lt_id)
                {
                    left_trigger = 0;
                    lt_id = -1;
                }
                else if (event.getPointerId(event.getActionIndex())==rt_id)
                {
                    right_trigger = 0;
                    rt_id = -1;
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_DOWN:
                if (event.getPointerCount() != 1)
                {
                    mouse_btns = 0;
                }
                else
                {
                    MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
                    event.getPointerCoords(0, pointerCoords);
                    mouse_pos[0] = Math.round((pointerCoords.x - tx) / scl);
                    mouse_pos[1] = Math.round(pointerCoords.y / scl);
                    mouse_btns = MotionEvent.BUTTON_PRIMARY;    // Mouse left button down
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1)
                {
                    MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
                    event.getPointerCoords(0, pointerCoords);
                    mouse_pos[0] = Math.round((pointerCoords.x - tx) / scl);
                    mouse_pos[1] = Math.round(pointerCoords.y / scl);
                }
                break;
        }
        if (getResources().getString(R.string.flavor).equals("naomi"))  // FIXME
        {
            if (left_trigger != 0)
                rv &= ~VJoy.key_CONT_C; // Service key/coin
        }
        int joyx = get_anal(11, 0);
        int joyy = get_anal(11, 1);
        InputDeviceManager.getInstance().virtualGamepadEvent(rv, joyx, joyy, left_trigger, right_trigger);
        // Only register the mouse event if no virtual gamepad button is down
        if (!editVjoyMode && rv == 0xFFFF)
            InputDeviceManager.getInstance().mouseEvent(mouse_pos[0], mouse_pos[1], mouse_btns);
        return(true);
    }

    private class OscOnScaleGestureListener extends
            ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (editVjoyMode && selectedVjoyElement != -1) {
                vjoy_d_custom[selectedVjoyElement][2] *= detector.getScaleFactor();
                requestLayout();

                return true;
            }

            return false;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            selectedVjoyElement = -1;
        }
    }

    class EmuThread extends Thread
    {
        AudioTrack Player;
        long pos;	//write position
        long size;	//size in frames
        private boolean sound;

        EmuThread(boolean sound) {
            this.sound = sound;
        }

        @Override public void run()
        {
            if (sound) {
                int min=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);

                if (2048>min)
                    min=2048;

                Player = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        44100,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        min,
                        AudioTrack.MODE_STREAM
                );

                size=min/4;
                pos=0;

                Log.i("audcfg", "Audio streaming: buffer size " + min + " samples / " + min/44100.0 + " ms");
                Player.play();
            }

            JNIdc.run(this);
        }

        int WriteBuffer(short[] samples, int wait)
        {
            if (sound) {
                int newdata=samples.length/2;

                if (wait==0)
                {
                    //user bytes = write-read
                    //available = size - (write - play)
                    long used=pos-Player.getPlaybackHeadPosition();
                    long avail=size-used;

                    //Log.i("audcfg", "u: " + used + " a: " + avail);
                    if (avail<newdata)
                        return 0;
                }

                pos+=newdata;

                Player.write(samples, 0, samples.length);
            }

            return 1;
        }

        void showMessage(final String msg) {
            handler.post(new Runnable() {
                public void run() {
                    Log.d(context.getPackageName(), msg);
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        int coreMessage(byte[] msg) {
            try {
                showMessage(new String(msg, "UTF-8"));
            }
            catch (UnsupportedEncodingException e) {
                showMessage("coreMessage: Failed to display error");
            }
            return 1;
        }

        void Die() {
            showMessage("Something went wrong and reicast crashed.\nPlease report this on the reicast forums.");
            ((Activity) context).finish();
        }

        void reiosInfo(String reiosId, String reiosSoftware) {
            if (fileName != null) {
                String gameId = reiosId.replaceAll("[^a-zA-Z0-9]+", "").toLowerCase();
                SharedPreferences mPrefs = context.getSharedPreferences(gameId, Activity.MODE_PRIVATE);
                Emulator app = (Emulator) context.getApplicationContext();
                app.loadGameConfiguration(gameId);
//                if (context instanceof GL2JNIActivity)
//                    ((GL2JNIActivity) context).getPad().joystick[0] = mPrefs.getBoolean(
//                            Gamepad.pref_js_merged + "_A",
//                            ((GL2JNIActivity) context).getPad().joystick[0]);
                mPrefs.edit().putString(Config.game_title, reiosSoftware.trim()).apply();
            }
        }
    }

    public void stop() {
        //JNIdc.destroy();
        JNIdc.stop();
        try {
            ethd.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @TargetApi(19)
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            requestLayout();
        }
    }

    public static class EmulatorInitFailed extends RuntimeException {
    }
}