package at.jku.cp.feichtinger.sensorLogger.services;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import at.jku.cp.feichtinger.sensorLogger.model.ApplicationConstants;
import at.jku.cp.feichtinger.sensorLogger.model.EnumeratedSensor;

public class RecorderService extends Service {
	private static final String TAG = "at.jku.cp.feichtinger.acceleromterRecorder.RecorderService";
	public static boolean isRunning = false;

	private final Binder mBinder = new RecorderBinder();

	private final Map<String, BlockingQueue<SensorEvent>> data = new HashMap<String, BlockingQueue<SensorEvent>>();
	private final Map<String, Thread> consumers = new HashMap<String, Thread>();
	private final Map<String, Sensor> sensors = new HashMap<String, Sensor>();

	private final BlockingQueue<SensorEvent> accelerationData = new LinkedBlockingQueue<SensorEvent>();
	private final BlockingQueue<SensorEvent> gravityData = new LinkedBlockingQueue<SensorEvent>();

	private SensorManager sensorManager;
	private Sensor accelerometerSensor;
	private Sensor gravitySensor;

	private Thread gravityWriterThread;
	private Thread accelerometerWriterThread;

	/**
	 * Listens for accelerometer events and stores them.
	 */
	private final SensorEventListener sensorEventListener = new SensorEventListener() {
		@Override
		public void onSensorChanged(final SensorEvent event) {
			if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
				try {
					accelerationData.put(event);
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}
			} else if (event.sensor.getType() == Sensor.TYPE_GRAVITY) {
				try {
					gravityData.put(event);
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		public void onAccuracyChanged(final Sensor sensor, final int accuracy) {
			// TODO
		}
	};

	/* ***************************************************************
	 * service life-cycle methods
	 */

	/**
	 * Called by the system when the service is first created. Do not call this
	 * method directly.
	 */
	@Override
	public void onCreate() {
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

	}

	/**
	 * Called by the system every time a client explicitly starts the service by
	 * calling startService(Intent), providing the arguments it supplied and a
	 * unique integer token representing the start request. Do not call this
	 * method directly.
	 */
	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId) {
		showToast("service started");
		accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);

		sensorManager.registerListener(sensorEventListener, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
		sensorManager.registerListener(sensorEventListener, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);

		finished = false;
		isRunning = true;

		try {

			final String[] activeSensors = intent.getExtras().getStringArray(ApplicationConstants.ACTIVE_SENSORS);
			for (final String key : activeSensors) {
				final EnumeratedSensor enumSensor = EnumeratedSensor.fromKey(key);
				final LinkedBlockingQueue<SensorEvent> dataQueue = new LinkedBlockingQueue<SensorEvent>();
				final Consumer consumer = new Consumer(dataQueue, getFileName(key));
				final Sensor sensor = sensorManager.getDefaultSensor(enumSensor.getSensorId());

				data.put(key, dataQueue);
				sensors.put(key, sensor);
				consumers.put(key, new Thread(consumer));

				sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
			}

			accelerometerWriterThread = new Thread(new Consumer(accelerationData,
					getFileName(accelerometerSensor.getName())));
			gravityWriterThread = new Thread(new Consumer(gravityData, getFileName(gravitySensor.getName())));

			accelerometerWriterThread.start();
			gravityWriterThread.start();
		} catch (final IOException e) {
			Log.e(TAG, e.getMessage(), e);
		}
		return START_STICKY;
	}

	/**
	 * Return the communication channel to the service. May return null if
	 * clients can not bind to the service.
	 */
	@Override
	public IBinder onBind(final Intent arg0) {
		return mBinder;
	}

	/**
	 * Called by the system to notify a Service that it is no longer used and is
	 * being removed. The service should clean up an resources it holds
	 * (threads, registered receivers, etc) at this point. Upon return, there
	 * will be no more calls in to this Service object and it is effectively
	 * dead. Do not call this method directly.
	 */
	@Override
	public void onDestroy() {
		Log.i(TAG, "onDestroy called");
		sensorManager.unregisterListener(sensorEventListener);
		Log.i(TAG, "setting finished to true");
		finished = true;
		accelerometerWriterThread.interrupt();
		gravityWriterThread.interrupt();

		showToast("service stopped");
		isRunning = false;
	}

	/* ***************************************************************
	 * helper methods
	 */

	/**
	 * This method just shows a toast (i.e. a small pop-up message) displaying
	 * the specified message.
	 * 
	 * @param message
	 *            The message to be shown.
	 */
	private void showToast(final String message) {
		Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
	}

	private String getFileName(final String sensorName) {
		final DateFormat dateFormat = new SimpleDateFormat("ddMMyy_hhmmss");
		return sensorName + "_" + dateFormat.format(new Date()) + ".csv";
	}

	/* ***************************************************************
	 * helper classes
	 */

	// consumer threads will check this variable
	private volatile boolean finished = false;

	/**
	 * Writes the contents of the specified queue to the specified file.
	 * 
	 * @param queue
	 *            The data queue
	 * @param file
	 *            The target file.
	 */

	private class Consumer implements Runnable {
		private final BlockingQueue<SensorEvent> queue;
		private final BufferedWriter out;

		public Consumer(final BlockingQueue<SensorEvent> queue, final String fileName) throws IOException {
			final File file = new File(getExternalFilesDir(null), fileName);
			if (!file.exists()) {
				file.createNewFile();
			}
			this.out = new BufferedWriter(new FileWriter(file));
			this.queue = queue;
		}

		@Override
		public void run() {
			Log.i(TAG, this.toString() + ".run()");
			while (!finished) {
				try {
					out.write(toCSVString(queue.take()));
				} catch (final IOException e) {
					Log.e(TAG, e.getMessage(), e);
				} catch (final InterruptedException e) {
					continue;
				}
			}

			cleanUp();
		}

		private void cleanUp() {
			Log.i(TAG, this.toString() + ".cleanUp()");

			try {
				while (!queue.isEmpty()) {
					out.write(toCSVString(queue.take()));
				}
				out.flush();
				out.close();
			} catch (final IOException e) {
				Log.e(TAG, e.getMessage(), e);
			} catch (final InterruptedException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}

		private String toCSVString(final SensorEvent event) {
			return (event.timestamp) + "," + event.values[0] + ", " + event.values[1] + "," + event.values[2] + "\n";
		}
	}

	public class RecorderBinder extends Binder {
		public RecorderService getService() {
			// Return this instance of RecorderService so clients can call
			// public methods
			return RecorderService.this;
		}

	}
}
