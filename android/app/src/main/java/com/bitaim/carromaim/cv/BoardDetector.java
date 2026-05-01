package com.bitaim.carromaim.cv;

  import android.graphics.Bitmap;
  import android.graphics.PointF;
  import android.graphics.RectF;
  import android.util.Log;

  import com.bitaim.carromaim.MainApplication;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * BoardDetector — v3 with safe OpenCV guard.
   * If OpenCV native libs failed to load, returns a synthetic fallback state
   * so the rest of the app still runs without crashing.
   */
  public class BoardDetector {

      private static final String TAG = "BoardDetector";
      private static final int PROC_W = 640;
      private static final float EMA_BOARD = 0.15f;

      private float minRadiusFrac = 0.013f;
      private float maxRadiusFrac = 0.042f;
      private double param2 = 20;
      private RectF smoothedBoard = null;

      // OpenCV Mats — only allocated when CV is available
      private Object frameMat, smallMat, grayMat, hsvMat, circlesMat;
      private boolean matsInitialized = false;

      public void setMinRadiusFrac(float v) { minRadiusFrac = Math.max(0.005f, Math.min(v, 0.05f)); }
      public void setMaxRadiusFrac(float v) { maxRadiusFrac = Math.max(0.02f, Math.min(v, 0.10f)); }
      public void setParam2(double v)       { param2 = Math.max(10, Math.min(v, 60)); }

      public synchronized GameState detect(Bitmap bitmap) {
          if (bitmap == null) return null;

          // If OpenCV unavailable, return synthetic fallback
          if (!MainApplication.cvReady) {
              return syntheticState(bitmap.getWidth(), bitmap.getHeight());
          }

          try {
              return detectWithCV(bitmap);
          } catch (Throwable t) {
              Log.e(TAG, "CV detect error: " + t.getMessage());
              return syntheticState(bitmap.getWidth(), bitmap.getHeight());
          }
      }

      private GameState detectWithCV(Bitmap bitmap) throws Throwable {
          int srcW = bitmap.getWidth(), srcH = bitmap.getHeight();
          if (srcW == 0 || srcH == 0) return null;

          // Lazy init Mats
          if (!matsInitialized) {
              frameMat   = new org.opencv.core.Mat();
              smallMat   = new org.opencv.core.Mat();
              grayMat    = new org.opencv.core.Mat();
              hsvMat     = new org.opencv.core.Mat();
              circlesMat = new org.opencv.core.Mat();
              matsInitialized = true;
          }

          org.opencv.core.Mat frame   = (org.opencv.core.Mat) frameMat;
          org.opencv.core.Mat small   = (org.opencv.core.Mat) smallMat;
          org.opencv.core.Mat gray    = (org.opencv.core.Mat) grayMat;
          org.opencv.core.Mat hsv     = (org.opencv.core.Mat) hsvMat;
          org.opencv.core.Mat circles = (org.opencv.core.Mat) circlesMat;

          org.opencv.android.Utils.bitmapToMat(bitmap, frame);
          float scale = (float) PROC_W / srcW;
          int procH = Math.round(srcH * scale);
          org.opencv.imgproc.Imgproc.resize(frame, small,
                  new org.opencv.core.Size(PROC_W, procH), 0, 0, org.opencv.imgproc.Imgproc.INTER_AREA);
          org.opencv.imgproc.Imgproc.cvtColor(small, hsv,  org.opencv.imgproc.Imgproc.COLOR_RGB2HSV);
          org.opencv.imgproc.Imgproc.cvtColor(small, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY);
          org.opencv.imgproc.Imgproc.medianBlur(gray, gray, 5);

          RectF rawBoard = detectBoardRect(hsv, small, srcW, srcH, scale, procH);
          smoothedBoard  = smoothRect(smoothedBoard, rawBoard);
          RectF board    = smoothedBoard;

          // ROI within board
          org.opencv.core.Mat roiGray = gray;
          org.opencv.core.Mat roiHsv  = hsv;
          float roiOffX = 0, roiOffY = 0;
          org.opencv.core.Rect roiRect = null;
          if (board != null) {
              float inset = board.width() * 0.06f;
              int rx = Math.max(0, Math.round((board.left + inset) * scale));
              int ry = Math.max(0, Math.round((board.top  + inset) * scale));
              int rw = Math.min(PROC_W - rx, Math.round((board.width()  - 2*inset) * scale));
              int rh = Math.min(procH  - ry, Math.round((board.height() - 2*inset) * scale));
              if (rw > 20 && rh > 20) {
                  roiRect = new org.opencv.core.Rect(rx, ry, rw, rh);
                  roiGray = gray.submat(roiRect);
                  roiHsv  = hsv .submat(roiRect);
                  roiOffX = rx; roiOffY = ry;
              }
          }

          int minR    = Math.round(PROC_W * minRadiusFrac);
          int maxR    = Math.round(PROC_W * maxRadiusFrac);
          int minDist = (int)(minR * 1.8);
          org.opencv.imgproc.Imgproc.HoughCircles(roiGray, circles,
                  org.opencv.imgproc.Imgproc.HOUGH_GRADIENT, 1.2, minDist, 100, param2, minR, maxR);

          GameState state = new GameState();
          List<Coin> all = new ArrayList<>();

          if (!circles.empty()) {
              int n = circles.cols();
              for (int i = 0; i < n; i++) {
                  double[] c = circles.get(0, i);
                  if (c == null || c.length < 3) continue;
                  int colorClass = classifyColor(roiHsv, (int)c[0], (int)c[1], (int)c[2]);
                  if (colorClass < 0) continue;
                  all.add(new Coin(
                      (c[0] + roiOffX) / scale, (c[1] + roiOffY) / scale,
                      c[2] / scale, colorClass, false));
              }
          }
          if (roiRect != null) { roiGray.release(); roiHsv.release(); }

          float lowerThreshold = srcH * 0.55f;
          Coin striker = null; float strikerScore = -1;
          for (Coin c : all) {
              if (c.color != Coin.COLOR_WHITE) continue;
              float score = c.radius * (c.pos.y > lowerThreshold ? 2.0f : 1.0f);
              if (score > strikerScore) { strikerScore = score; striker = c; }
          }
          if (striker != null) {
              striker.isStriker = true; striker.color = Coin.COLOR_STRIKER;
              state.striker = striker;
          }
          for (Coin c : all) if (c != striker) state.coins.add(c);

          state.board = board != null ? board : fallbackBoard(srcW, srcH);
          addPockets(state);
          return state;
      }

      private RectF detectBoardRect(org.opencv.core.Mat hsv,
                                    org.opencv.core.Mat small,
                                    int srcW, int srcH, float scale, int procH) {
          org.opencv.core.Mat woodMask = new org.opencv.core.Mat();
          org.opencv.core.Core.inRange(hsv,
                  new org.opencv.core.Scalar(8, 35, 80),
                  new org.opencv.core.Scalar(28, 210, 225), woodMask);

          org.opencv.core.Mat kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
                  org.opencv.imgproc.Imgproc.MORPH_RECT, new org.opencv.core.Size(9, 9));
          org.opencv.imgproc.Imgproc.morphologyEx(woodMask, woodMask,
                  org.opencv.imgproc.Imgproc.MORPH_CLOSE, kernel);
          org.opencv.imgproc.Imgproc.morphologyEx(woodMask, woodMask,
                  org.opencv.imgproc.Imgproc.MORPH_OPEN,  kernel);
          kernel.release();

          List<org.opencv.core.MatOfPoint> contours = new ArrayList<>();
          org.opencv.imgproc.Imgproc.findContours(woodMask, contours, new org.opencv.core.Mat(),
                  org.opencv.imgproc.Imgproc.RETR_EXTERNAL,
                  org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE);
          woodMask.release();

          double bestArea = 0;
          org.opencv.core.Rect bestRect = null;
          for (org.opencv.core.MatOfPoint c : contours) {
              org.opencv.core.Rect r = org.opencv.imgproc.Imgproc.boundingRect(c);
              double area = r.width * (double) r.height;
              if (area > bestArea) { bestArea = area; bestRect = r; }
              c.release();
          }

          if (bestRect == null || bestArea < 0.04 * PROC_W * PROC_W) return null;

          float l = bestRect.x / scale, t = bestRect.y / scale;
          float r = (bestRect.x + bestRect.width)  / scale;
          float b = (bestRect.y + bestRect.height) / scale;
          float side = Math.max(r - l, b - t);
          float cx = (l + r) / 2f, cy = (t + b) / 2f;
          return new RectF(
              Math.max(0, cx - side/2f), Math.max(0, cy - side/2f),
              Math.min(srcW, cx + side/2f), Math.min(srcH, cy + side/2f));
      }

      private int classifyColor(org.opencv.core.Mat hsvMat, int x, int y, int r) {
          if (r < 2) return -1;
          if (x-r<0||y-r<0||x+r>=hsvMat.cols()||y+r>=hsvMat.rows()) return -1;
          int s = Math.max(1, r / 4);
          org.opencv.core.Mat patch = hsvMat.submat(
                  Math.max(0,y-s), Math.min(hsvMat.rows(),y+s),
                  Math.max(0,x-s), Math.min(hsvMat.cols(),x+s));
          org.opencv.core.Scalar mean = org.opencv.core.Core.mean(patch);
          patch.release();
          double h = mean.val[0], sat = mean.val[1], v = mean.val[2];
          if (v < 65) return Coin.COLOR_BLACK;
          if (v > 170 && sat < 65) return Coin.COLOR_WHITE;
          if (sat > 85 && (h < 14 || h > 162)) return Coin.COLOR_RED;
          if (h >= 8 && h <= 28 && sat > 35) return -1;
          return -1;
      }

      /** Synthetic state used when OpenCV is unavailable — places striker at bottom centre */
      private GameState syntheticState(int w, int h) {
          GameState s = new GameState();
          float side = w * 0.70f;
          float cx = w / 2f, cy = h * 0.50f;
          s.board = new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
          s.striker = new Coin(cx, cy + side*0.35f, side*0.025f, Coin.COLOR_STRIKER, true);
          addPockets(s);
          return s;
      }

      private RectF fallbackBoard(int w, int h) {
          float side = w * 0.70f;
          float cx = w / 2f, cy = h * 0.50f;
          return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
      }

      private void addPockets(GameState s) {
          if (s.board == null) return;
          float i = s.board.width() * 0.03f;
          s.pockets.add(new PointF(s.board.left+i,  s.board.top+i));
          s.pockets.add(new PointF(s.board.right-i, s.board.top+i));
          s.pockets.add(new PointF(s.board.left+i,  s.board.bottom-i));
          s.pockets.add(new PointF(s.board.right-i, s.board.bottom-i));
      }

      private RectF smoothRect(RectF p, RectF n) {
          if (p == null) return n; if (n == null) return p;
          float a = EMA_BOARD;
          return new RectF(p.left+a*(n.left-p.left), p.top+a*(n.top-p.top),
                           p.right+a*(n.right-p.right), p.bottom+a*(n.bottom-p.bottom));
      }
  }
  