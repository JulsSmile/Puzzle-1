/**
 * 
 */
package sk.flexoft.android.puzzle;

import java.util.TreeMap;

import sk.flexoft.android.puzzle.util.AndroidExtensions;
import sk.flexoft.android.puzzle.util.AndroidExtensions.LogType;

import android.R.bool;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

/**
 * The Class PuzzleView encapsulates the surface drawing.
 */
public class PuzzleView extends SurfaceView implements Runnable, SurfaceHolder.Callback, View.OnTouchListener {

	/** The Constant TAG. */
	private static final String TAG = "PuzzleView";
	
	/** Multiplier used for hashing rectangle size. */
	private static final int RECT_HASH_MULTIPLIER = 10000;
	
	/** The bitmap. */
	Bitmap bitmap;
	
	/** The surface holder. */
	SurfaceHolder holder;

	/** The paint defining raster color, thickness, etc. */
	Paint rasterPaint;
	
	/** The paint defining raster border color, thickness, etc. */
	Paint rasterBorderPaint;
	
	/** The paint used for interaction related painting. */
	Paint touchPaint;
	
	/** Flag indicating if application is actively running. */
	boolean isRunning = false;
	
	/** The rendering thread. */
	Thread t;
	
	/** Currently active rectangle. */
	Rect activeRect = null;
	
	/** The active rectangle hash. */
	int activeRectHash = 0;
	
	/** The map of views to view related info to avoid recalculating bitmap info when view has changed. */
	TreeMap<Integer, ScreenInfo> viewMap = new TreeMap<Integer, ScreenInfo>();

	/** The main application activity. */
	PuzzleActivity puzzleActivity;
	
	/** The identifier of a gesture starting pointer. */
	private int gesturePointerId = -1; 
	
	/** The gesture rectangle indexes. */
	private int gestureRectIdx = -1;
	
	/** The time when gesture was first time observed on a field. Used to measure the time on a field to perform a move.*/
	private long gestureOnFieldStartTime = -1;
	
	public PuzzleView(Context context) {
		super(context);
		
		if (context == null)
		{
			throw new IllegalArgumentException();
		}
		
		puzzleActivity = (PuzzleActivity)context;
		
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = 1;
		bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.puzzle, options);
		
		rasterPaint = new Paint();
		rasterPaint.setColor(Color.LTGRAY);
		rasterPaint.setAlpha(128);
		rasterPaint.setStrokeWidth(1);
		
		touchPaint = new Paint();
		touchPaint.setColor(Color.LTGRAY);
		touchPaint.setAlpha(64);
		touchPaint.setStrokeWidth(1);
		
		rasterBorderPaint = new Paint();
		rasterBorderPaint.setColor(Color.LTGRAY);
		rasterBorderPaint.setStrokeWidth(1);
		rasterBorderPaint.setStyle(Paint.Style.STROKE);
		
		holder = getHolder();
		holder.addCallback(this);
		
		setOnTouchListener(this);
		
		resume();
	}

	public void pause()
	{
		isRunning = false;
		
		if (t == null)
		{
			return;
		}
		
		try {
			t.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		Log.d(TAG, "Paused");
		t = null;
	}
	
	public void resume()
	{
		if (t != null)
		{
			pause();
		}
		
		t = new Thread(this);
		isRunning = true;
		t.start();
		
		Log.d(TAG, "Resumed");
	}
	
	
	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		
		while (isRunning)
		{
			Surface s = holder.getSurface(); 
			if (s == null || !s.isValid() || activeRect == null || activeRectHash ==0 || !viewMap.containsKey(activeRectHash))
			{
				continue;
			}
			
			ScreenInfo info = viewMap.get(activeRectHash);

			if (info == null)
			{
				continue;
			}
			
			Canvas c = holder.lockCanvas();
			drawScreen(c, info);
			holder.unlockCanvasAndPost(c);
		}
 	}

	
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		
		AndroidExtensions.Log(LogType.Debug, TAG, "surfaceChanged: [%d, %d]", width, height);
		activeRect = new Rect(0, 0, width, height);
		activeRectHash = hashRectSize(activeRect);
		if (!viewMap.containsKey(activeRectHash))
		{
			viewMap.put(activeRectHash, new ScreenInfo(bitmap, activeRect.width(), activeRect.height()));
		}
		
		AndroidExtensions.Log(LogType.Debug, TAG, "view contains %d items", viewMap.size());
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		
		Log.d(TAG, "surfaceCreated");
		
		Rect rect = holder.getSurfaceFrame();
		if (rect != null && rect.width() != 0 && rect.height() != 0)
		{
			AndroidExtensions.Log(LogType.Debug, TAG, "[%d, %d]", rect.width(), rect.height());
			
			activeRect = new Rect(rect);
			activeRectHash = hashRectSize(activeRect);
			if (!viewMap.containsKey(activeRectHash))
			{
				viewMap.put(activeRectHash, new ScreenInfo(bitmap, activeRect.width(), activeRect.height()));
			}
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {

		Log.d(TAG, "surfaceDestroyed");
		
		activeRect = null;
	}
	
	/**
	 * On touch event handling.
	 *
	 * @param v The view sending the event.
	 * @param m The motion event descriptor.
	 * @return true, if handled successfully 
	 */
	public boolean onTouch(View v, MotionEvent m) {
		// printSamples(m);
		switch (m.getActionMasked())
		{
			case MotionEvent.ACTION_DOWN: handleTouchDown(m); 
				break;
			case MotionEvent.ACTION_MOVE: handleTouchMove(m);
				break;
			case MotionEvent.ACTION_CANCEL: handleTouchCancel(m);
				break;
			case MotionEvent.ACTION_UP: 
			case MotionEvent.ACTION_POINTER_UP: handleTouchPointerUp(m);
			break;
		}
		
		return true;
	}
	
	/**
	 * Hashes rectangle size into integer.
	 *
	 * @param rect the rectangle to be hashed
	 * @return hashed size of the rectangle in string
	 */
	private int hashRectSize(Rect rect)
	{
		assert rect != null;
		
		return rect.width() * RECT_HASH_MULTIPLIER + rect.height();
	}
	
	private void drawScreen(Canvas canvas, ScreenInfo scrInfo)
	{
		assert canvas != null;
		assert scrInfo != null;
		
		canvas.drawColor(Color.BLACK);

		// canvas.drawBitmap(bitmap, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()) , scrInfo.targetRect, null);
		for (int i = 0; i < PuzzleActivity.RASTER_SIZE; i++)
		{
			for(int j = 0; j < PuzzleActivity.RASTER_SIZE; j++)
			{
				int bmpCompositeIndex = puzzleActivity.getBoard().getPuzzleIndexAt(i, j);
				assert bmpCompositeIndex >= 0 || bmpCompositeIndex < Math.pow(PuzzleActivity.RASTER_SIZE, 2) || bmpCompositeIndex == PuzzleBoard.EMPTY_FIELD_IDX;
				
				// empty field is left black
				if (bmpCompositeIndex != PuzzleBoard.EMPTY_FIELD_IDX)
				{
					Bitmap bmpPart = scrInfo.bmpParts[bmpCompositeIndex / PuzzleActivity.RASTER_SIZE][bmpCompositeIndex % PuzzleActivity.RASTER_SIZE];
					Rect targetPart = scrInfo.targetParts[i][j]; 
					canvas.drawBitmap(bmpPart, targetPart.left, targetPart.top, null);
				}
			}
		}
		
		int horStep = scrInfo.targetRect.width() / PuzzleActivity.RASTER_SIZE;
		int verStep = scrInfo.targetRect.height() / PuzzleActivity.RASTER_SIZE;
		for (int i = 1; i < PuzzleActivity.RASTER_SIZE; i++)
		{
			int vertPos = scrInfo.targetRect.top + i * verStep;
			canvas.drawLine(scrInfo.targetRect.left, vertPos , scrInfo.targetRect.right, vertPos, rasterPaint);
			
			int horPos = scrInfo.targetRect.left + i * horStep;
			canvas.drawLine(horPos, scrInfo.targetRect.top , horPos, scrInfo.targetRect.bottom, rasterPaint);
		}
		
		canvas.drawRect(scrInfo.targetRect, rasterBorderPaint);
		
		if (gestureRectIdx != -1)
		{
			int[] indexes = PuzzleActivity.int2Indexes(gestureRectIdx);
			if (puzzleActivity.getBoard().isFieldExchangeable(indexes[0], indexes[1]))
			{
				touchPaint.setColor(Color.LTGRAY);
				touchPaint.setAlpha(64);
			}
			else
			{
				touchPaint.setColor(Color.RED);
				touchPaint.setAlpha(96);
			}
			
			canvas.drawRect(scrInfo.targetParts[indexes[0]][indexes[1]], touchPaint);
		}
	}
	
	private void printSamples(MotionEvent ev) {
	     final int historySize = ev.getHistorySize();
	     final int pointerCount = ev.getPointerCount();
	     
	     /*
	     for (int h = 0; h < historySize; h++) {
	         AndroidExtensions.Log(LogType.Debug, TAG, "At time %d:", ev.getHistoricalEventTime(h));
	         for (int p = 0; p < pointerCount; p++) {
	        	 AndroidExtensions.Log(LogType.Debug, TAG, "  pointer %d: (%f,%f)",
	                 ev.getPointerId(p), ev.getHistoricalX(p, h), ev.getHistoricalY(p, h));
	         }
	     }
	     */
	     
	     int action = ev.getActionMasked();
	     if (action == MotionEvent.ACTION_MOVE)
	     {
	    	 return;
	     }
	     
	     AndroidExtensions.Log(LogType.Debug, TAG, "%s at time %d:", convertAction(action), ev.getEventTime());
	     for (int p = 0; p < pointerCount; p++) {
	         AndroidExtensions.Log(LogType.Debug, TAG, "  pointer %d: (%f,%f)",
	             ev.getPointerId(p), ev.getX(p), ev.getY(p));
	     }
	 }
	
	private String convertAction(int a)
	{
		if (a == MotionEvent.ACTION_DOWN)
		{
			return "DOWN";
		}
		
		if (a == MotionEvent.ACTION_MOVE)
		{
			return "MOVE";
		}
		
		if (a == MotionEvent.ACTION_UP)
		{
			return "UP";
		}
		
		if (a == MotionEvent.ACTION_CANCEL)
		{
			return "CANCEL";
		}
		
		return String.format("%d", a);
	}
	
	private void handleTouchPointerUp(MotionEvent m) {
		int i = m.getActionIndex();
		
		AndroidExtensions.Log(LogType.Debug, TAG, "Pointer %d [id %d] up -> finishing gesture. gesturePointerId is %d", i, m.getPointerId(i), gesturePointerId);
		
		if (m.getPointerId(i) == gesturePointerId)
		{
			AndroidExtensions.Log(LogType.Debug, TAG, "Pointer %d up -> finishing gesture.", gesturePointerId);
			
			if (activeRect != null)
			{
				int key = activeRectHash;
				if (viewMap.containsKey(key))
				{
					ScreenInfo info = viewMap.get(key);
					assert info != null;
					gestureRectIdx = info.getRectIdxFromPoint((int)m.getX(), (int)m.getY());
					
					
					if (puzzleActivity.getBoard().isFieldExchangeable(gestureRectIdx))
					{
						puzzleActivity.getBoard().exchange(gestureRectIdx);
					}
				}				
			}
			
			gesturePointerId = gestureRectIdx = -1;
		}
	}
	
	private void handleTouchCancel(MotionEvent m) {
		gesturePointerId = gestureRectIdx = -1;
	}

	private void handleTouchMove(MotionEvent m) {
		for(int i = 0; i < m.getPointerCount(); i++)
		{
			if (m.getPointerId(i) == gesturePointerId && activeRect != null)
			{
				int key = activeRectHash;
				if (viewMap.containsKey(key))
				{
					ScreenInfo info = viewMap.get(key);
					assert info != null;
					int lastGestureIdx = gestureRectIdx; 
					gestureRectIdx = info.getRectIdxFromPoint((int)m.getX(), (int)m.getY());
					
					if (gestureRectIdx == -1)
					{
						gestureOnFieldStartTime = -1;
					}
					else if (lastGestureIdx != gestureRectIdx)
					{
						gestureOnFieldStartTime = m.getEventTime();						
					}
					
					break;
				}
			}
		}
		
	}

	private void handleTouchDown(MotionEvent m) {
		assert m.getPointerCount() == 0;
		gesturePointerId = m.getPointerId(m.getActionIndex());
		if (activeRect != null)
		{
			int key = activeRectHash;
			if (viewMap.containsKey(key))
			{
				AndroidExtensions.Log(LogType.Debug, TAG, "Pointer %d down -> starting gesture.", gesturePointerId);
				ScreenInfo info = viewMap.get(key);
				assert info != null;
				gestureRectIdx = info.getRectIdxFromPoint((int)m.getX(), (int)m.getY());
				
				if (gestureRectIdx != -1)
				{
					gestureOnFieldStartTime = m.getEventTime();
				}
				else
				{
					gestureOnFieldStartTime = -1;
				}
			}
		}
	}
}
