package com.todobom.opennotescanner;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.shapes.PathShape;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.todobom.opennotescanner.helpers.OpenNoteMessage;
import com.todobom.opennotescanner.helpers.PreviewFrame;
import com.todobom.opennotescanner.helpers.Quadrilateral;
import com.todobom.opennotescanner.helpers.ScannedDocument;
import com.todobom.opennotescanner.helpers.Utils;
import com.todobom.opennotescanner.views.HUDCanvasView;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;

import androidx.annotation.NonNull;

/**
 * Created by allgood on 05/03/16.
 */
public class ImageProcessor extends Handler {

    private static final String TAG = "ImageProcessor";
    private final OpenNoteScannerActivity mMainActivity;
    private boolean mBugRotate;
    private boolean colorMode=false;
    private boolean filterMode=true;
    private static final double colorGain = 1.5;       // contrast
    private static final double colorBias = 0;         // bright
    private static final int colorThresh = 110;        // threshold
    private Size mPreviewSize;
    private Point[] mPreviewPoints;

    private double mDocumentAspectRatio;

    public ImageProcessor(Looper looper, OpenNoteScannerActivity mainActivity) {
        super(looper);
        mMainActivity = mainActivity;

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mainActivity);
        mBugRotate = sharedPref.getBoolean("bug_rotate",false);

        String docPageFormat = sharedPref.getString("document_page_format", "0");
        mDocumentAspectRatio = 0;
        if (docPageFormat.equals("0.0001")) {
            float customPageWidth = Float.parseFloat(sharedPref.getString("custom_pageformat_width" , "0" ));
            float customPageHeight = Float.parseFloat(sharedPref.getString("custom_pageformat_height" , "0" ));
            if (customPageWidth > 0 && customPageHeight > 0) {
                mDocumentAspectRatio = customPageHeight / customPageWidth;
            }
        } else {
            mDocumentAspectRatio = Float.parseFloat(docPageFormat);
        }
    }

    public void handleMessage ( Message msg ) {

        if (msg.obj.getClass() == OpenNoteMessage.class) {

            OpenNoteMessage obj = (OpenNoteMessage) msg.obj;

            String command = obj.getCommand();

            Log.v(TAG, "Message Received: " + command + " - " + obj.getObj().toString() );

            if ( command.equals("previewFrame")) {
                processPreviewFrame((PreviewFrame) obj.getObj());
            } else if ( command.equals("pictureTaken")) {
                processPicture((Mat) obj.getObj());
            } else if ( command.equals("colorMode")) {
                colorMode=(Boolean) obj.getObj();
            } else if ( command.equals("filterMode")) {
                filterMode=(Boolean) obj.getObj();
            }
        }
    }

    private void processPreviewFrame( PreviewFrame previewFrame ) {

        Result[] results = {};

        Mat frame = previewFrame.getFrame();

        try {
            results = zxing(frame);
        } catch (ChecksumException | FormatException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        boolean qrOk = false;
        String currentQR = null;

        for (Result result: results) {
            String qrText = result.getText();
            if ( Utils.isMatch(qrText, "^P.. V.. S[0-9]+") && checkQR(qrText)) {
                Log.d(TAG, "QR Code valid: " + result.getText());
                qrOk = true;
                currentQR = qrText;
                break;
            } else {
                Log.d(TAG, "QR Code ignored: " + result.getText());
            }
        }

        boolean autoMode = previewFrame.isAutoMode();
        boolean previewOnly = previewFrame.isPreviewOnly();

        // request picture if document is detected and either scan button is clicked and not in auto mode or qr code is detected in auto mode
        // FIXME: consider simplifying this. isPreviewOnly contains isAutoMode, e.g if autoMode is true, isPreviewOnly will always be false
        if ( detectPreviewDocument(frame) && ( (!autoMode && !previewOnly ) || ( autoMode && qrOk ) ) ) {
            mMainActivity.waitSpinnerVisible();
            mMainActivity.requestPicture();

            if (qrOk) {
                pageHistory.put(currentQR, new Date().getTime() / 1000);
                Log.d(TAG, "QR Code scanned: " + currentQR);
            }
        }

        frame.release();
        mMainActivity.setImageProcessorBusy(false);
    }

    public void processPicture( Mat picture ) {
        Log.d(TAG, "processPicture - imported image " + picture.size().width + "x" + picture.size().height);

        if (mBugRotate) {
            Core.flip(picture, picture, 1 );
            Core.flip(picture, picture, 0 );
        }

        ScannedDocument doc = detectDocument(picture);
        mMainActivity.saveDocument(doc);

        doc.release();
        picture.release();

        mMainActivity.setImageProcessorBusy(false);
        mMainActivity.setAttemptToFocus(false);
        mMainActivity.waitSpinnerInvisible();
    }


    private ScannedDocument detectDocument(Mat inputRgba) {
        ArrayList<MatOfPoint> contours = findContours(inputRgba);

        ScannedDocument sd = new ScannedDocument(inputRgba);

        Quadrilateral quad = getQuadrilateral(contours, inputRgba.size());

        Mat doc;

        if (quad != null) {

            MatOfPoint c = quad.getContour();

            sd.setQuadrilateral(quad);
            sd.setPreviewPoints(mPreviewPoints);
            sd.setPreviewSize(mPreviewSize);

            doc = fourPointTransform(inputRgba, quad.getPoints());

        } else {
            doc = new Mat( inputRgba.size() , CvType.CV_8UC4 );
            inputRgba.copyTo(doc);
        }

        enhanceDocument(doc);

        sd.setProcessed(doc);

        return sd;
    }


    private HashMap<String,Long> pageHistory = new HashMap<>();

    private boolean checkQR(String qrCode) {

        return ! ( pageHistory.containsKey(qrCode) &&
                pageHistory.get(qrCode) > new Date().getTime()/1000-15) ;

    }

    private boolean detectPreviewDocument(Mat inputRgba) {

        ArrayList<MatOfPoint> contours = findContours(inputRgba);

        Quadrilateral quad = getQuadrilateral(contours, inputRgba.size());

        mPreviewPoints = null;
        mPreviewSize = inputRgba.size();
        drawDocumentArea(mPreviewSize);

        if (quad != null) {

            Point[] rescaledPoints = new Point[4];

            double ratio = inputRgba.size().height / 500;

            for ( int i=0; i<4 ; i++ ) {
                int x = Double.valueOf(quad.getPoints()[i].x*ratio).intValue();
                int y = Double.valueOf(quad.getPoints()[i].y*ratio).intValue();
                if (mBugRotate) {
                    rescaledPoints[(i+2)%4] = new Point( Math.abs(x- mPreviewSize.width), Math.abs(y- mPreviewSize.height));
                } else {
                    rescaledPoints[i] = new Point(x, y);
                }
            }

            mPreviewPoints = rescaledPoints;

            drawDocumentBox(mPreviewPoints, mPreviewSize);

//            Log.d(TAG, quad.getPoints()[0].toString() + " , " + quad.getPoints()[1].toString() + " , " + quad.getPoints()[2].toString() + " , " + quad.getPoints()[3].toString());

            return true;

        }

        mMainActivity.getHUD().clear();
        mMainActivity.invalidateHUD();

        return false;

    }

    private void drawDocumentArea(Size stdSize) {
        HUDCanvasView hud = mMainActivity.getHUD();

        if (mDocumentAspectRatio == 0) {
            hud.setDocumentBoxShape(null, null, null);
            return;
        }

        /* SAVING TO USE ON ANOTHER PLACE * /

        final float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrix(rotationMatrix, null,
                mMainActivity.accelerometerReading, mMainActivity.magnetometerReading);

        // Express the updated rotation matrix as three orientation angles.
        final float[] orientationAngles = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        // Pitch will vary from -1,57 (full vertical) to 0 (full horizontal) to +1,57 (upside down)
        float pitch = 0; // orientationAngles[1]; // for now, ignore inclination

        // force 0 to 45 degrees
        if (pitch < -0.78) {
            pitch = (float) -0.78;
        } else if (pitch > 0) {
            pitch = 0;
        }

        /*   */

        // ATTENTION: axis are swapped
        float previewWidth = (float) stdSize.height;
        float previewHeight = (float) stdSize.width;

        Path path = new Path();

        path.moveTo( 0 ,0 );
        path.lineTo( previewWidth , 0 );
        path.lineTo( previewWidth, previewHeight  );
        path.lineTo( 0, previewHeight );
        path.close();

        float previewAspectRatio = previewHeight/previewWidth;

        int[] documentArea = Utils.getDocumentArea((int) previewHeight, (int) previewWidth, mMainActivity);

        path.moveTo( documentArea[1] , documentArea[0] );
        path.lineTo( documentArea[1] , documentArea[2] );
        path.lineTo( documentArea[3] , documentArea[2] );
        path.lineTo( documentArea[3] , documentArea[0] );
        path.close();

        path.setFillType(Path.FillType.EVEN_ODD);

        PathShape newBox = new PathShape(path , previewWidth , previewHeight);

        Paint paint = new Paint();
        paint.setColor(Color.argb(64, 255, 255, 255));

        Paint border = new Paint();
        border.setColor(Color.argb( 32, 255, 255, 255));
        border.setStrokeWidth(5);

        hud.setDocumentBoxShape(newBox, paint, border);
        mMainActivity.invalidateHUD();
    }

    private void drawDocumentBox(Point[] points, Size stdSize) {

        Path path = new Path();

        HUDCanvasView hud = mMainActivity.getHUD();

        // ATTENTION: axis are swapped
        float previewWidth = (float) stdSize.height;
        float previewHeight = (float) stdSize.width;

        path.moveTo( previewWidth - (float) points[0].y, (float) points[0].x );
        path.lineTo( previewWidth - (float) points[1].y, (float) points[1].x );
        path.lineTo( previewWidth - (float) points[2].y, (float) points[2].x );
        path.lineTo( previewWidth - (float) points[3].y, (float) points[3].x );
        path.close();

        PathShape newBox = new PathShape(path , previewWidth , previewHeight);

        Paint paint = new Paint();
        paint.setColor(Color.argb(64, 0, 255, 0));

        Paint border = new Paint();
        border.setColor(Color.rgb(0, 255, 0));
        border.setStrokeWidth(5);

        hud.clear();
        hud.setDetectedShape(newBox, paint, border);
        mMainActivity.invalidateHUD();
    }

    private Quadrilateral getQuadrilateral( ArrayList<MatOfPoint> contours , Size srcSize ) {

        double ratio = srcSize.height / 500;
        int height = Double.valueOf(srcSize.height / ratio).intValue();
        int width = Double.valueOf(srcSize.width / ratio).intValue();
        Size size = new Size(width,height);

        for ( MatOfPoint c: contours ) {
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
            double peri = Imgproc.arcLength(c2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);

            Point[] points = approx.toArray();

            // select biggest 4 angles polygon
            if (points.length == 4) {
                Point[] foundPoints = sortPoints(points);

                if (insideHotArea(foundPoints, size)) {
                    return new Quadrilateral( c , foundPoints );
                }
            }
        }

        return null;
    }

    private Point[] sortPoints( Point[] src ) {

        ArrayList<Point> srcPoints = new ArrayList<>(Arrays.asList(src));

        Point[] result = { null , null , null , null };

        Comparator<Point> sumComparator = (lhs, rhs) -> Double.valueOf(lhs.y + lhs.x).compareTo(rhs.y + rhs.x);

        Comparator<Point> diffComparator = (lhs, rhs) -> Double.valueOf(lhs.y - lhs.x).compareTo(rhs.y - rhs.x);

        // top-left corner = minimal sum
        result[0] = Collections.min(srcPoints, sumComparator);

        // bottom-right corner = maximal sum
        result[2] = Collections.max(srcPoints, sumComparator);

        // top-right corner = minimal diference
        result[1] = Collections.min(srcPoints, diffComparator);

        // bottom-left corner = maximal diference
        result[3] = Collections.max(srcPoints, diffComparator);

        return result;
    }

    private boolean insideHotArea(Point[] rp, Size size) {

        int[] hotArea = Utils.getHotArea((int) size.width, (int) size.height, this.mMainActivity);

        return (
                rp[0].x <= hotArea[0] && rp[0].y <= hotArea[1]
                        && rp[1].x >= hotArea[2] && rp[1].y <= hotArea[1]
                        && rp[2].x >= hotArea[2] && rp[2].y >= hotArea[3]
                        && rp[3].x <= hotArea[0] && rp[3].y >= hotArea[3]
        );
    }

    private void enhanceDocument( Mat src ) {
        if (colorMode && filterMode) {
            src.convertTo(src,-1, colorGain , colorBias);
            Mat mask = new Mat(src.size(), CvType.CV_8UC1);
            Imgproc.cvtColor(src,mask,Imgproc.COLOR_RGBA2GRAY);

            Mat copy = new Mat(src.size(), CvType.CV_8UC3);
            src.copyTo(copy);

            Imgproc.adaptiveThreshold(mask,mask,255,Imgproc.ADAPTIVE_THRESH_MEAN_C,Imgproc.THRESH_BINARY_INV,225,15);

            src.setTo(new Scalar(255,255,255));
            copy.copyTo(src,mask);

            copy.release();
            mask.release();

            // special color threshold algorithm
            colorThresh(src,colorThresh);
        } else if (!colorMode) {
            Imgproc.cvtColor(src,src,Imgproc.COLOR_RGBA2GRAY);
            if (filterMode) {
                Imgproc.adaptiveThreshold(src, src, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 225, 15);
            }
        }
    }

    /**
     * When a pixel have any of its three elements above the threshold
     * value and the average of the three values are less than 80% of the
     * higher one, brings all three values to the max possible keeping
     * the relation between them, any absolute white keeps the value, all
     * others go to absolute black.
     *
     * src must be a 3 channel image with 8 bits per channel
     *
     * @param src
     * @param threshold
     */
    private void colorThresh(Mat src, int threshold) {
        Size srcSize = src.size();
        int size = (int) (srcSize.height * srcSize.width)*3;
        byte[] d = new byte[size];
        src.get(0,0,d);

        for (int i=0; i < size; i+=3) {

            // the "& 0xff" operations are needed to convert the signed byte to double

            // avoid unneeded work
            if ( (double) (d[i] & 0xff) == 255 ) {
                continue;
            }

            double max = Math.max(Math.max((double) (d[i] & 0xff), (double) (d[i + 1] & 0xff)),
                    (double) (d[i + 2] & 0xff));
            double mean = ((double) (d[i] & 0xff) + (double) (d[i + 1] & 0xff)
                    + (double) (d[i + 2] & 0xff)) / 3;

            if (max > threshold && mean < max * 0.8) {
                d[i] = (byte) ((double) (d[i] & 0xff) * 255 / max);
                d[i + 1] = (byte) ((double) (d[i + 1] & 0xff) * 255 / max);
                d[i + 2] = (byte) ((double) (d[i + 2] & 0xff) * 255 / max);
            } else {
                d[i] = d[i + 1] = d[i + 2] = 0;
            }
        }
        src.put(0,0,d);
    }

    @NonNull
    private Mat fourPointTransform( Mat src , Point[] pts ) {

        double ratio = src.size().height / 500;

        Point tl = pts[0];
        Point tr = pts[1];
        Point br = pts[2];
        Point bl = pts[3];

        double widthA = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
        double widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));

        double dw = Math.max(widthA, widthB)*ratio;
        int maxWidth = Double.valueOf(dw).intValue();


        double heightA = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
        double heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));

        double dh = Math.max(heightA, heightB)*ratio;
        int maxHeight = Double.valueOf(dh).intValue();

        Mat doc = new Mat(maxHeight, maxWidth, CvType.CV_8UC4);

        Mat src_mat = new Mat(4, 1, CvType.CV_32FC2);
        Mat dst_mat = new Mat(4, 1, CvType.CV_32FC2);

        src_mat.put(0, 0, tl.x*ratio, tl.y*ratio, tr.x*ratio, tr.y*ratio, br.x*ratio, br.y*ratio, bl.x*ratio, bl.y*ratio);
        dst_mat.put(0, 0, 0.0, 0.0, dw, 0.0, dw, dh, 0.0, dh);

        Mat m = Imgproc.getPerspectiveTransform(src_mat, dst_mat);

        Imgproc.warpPerspective(src, doc, m, doc.size());

        return doc;
    }

    private ArrayList<MatOfPoint> findContours(Mat src) {

        Mat grayImage = null;
        Mat cannedImage = null;
        Mat resizedImage = null;

        double ratio = src.size().height / 500;
        int height = Double.valueOf(src.size().height / ratio).intValue();
        int width = Double.valueOf(src.size().width / ratio).intValue();
        Size size = new Size(width,height);

        resizedImage = new Mat(size, CvType.CV_8UC4);
        grayImage = new Mat(size, CvType.CV_8UC4);
        cannedImage = new Mat(size, CvType.CV_8UC1);

        Imgproc.resize(src,resizedImage,size);
        Imgproc.cvtColor(resizedImage, grayImage, Imgproc.COLOR_RGBA2GRAY, 4);
        Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);
        Imgproc.Canny(grayImage, cannedImage, 75, 200);

        ArrayList<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(cannedImage, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        hierarchy.release();

        Collections.sort(contours, (lhs, rhs) -> Double.valueOf(Imgproc.contourArea(rhs)).compareTo(Imgproc.contourArea(lhs)));

        resizedImage.release();
        grayImage.release();
        cannedImage.release();

        return contours;
    }

    private QRCodeMultiReader qrCodeMultiReader = new QRCodeMultiReader();



    public Result[] zxing( Mat inputImage ) throws ChecksumException, FormatException {

        int w = inputImage.width();
        int h = inputImage.height();

        Mat southEast;

        if (mBugRotate) {
            southEast = inputImage.submat(h-h/4 , h , 0 , w/2 - h/4 );
        } else {
            southEast = inputImage.submat(0, h / 4, w / 2 + h / 4, w);
        }

        Bitmap bMap = Bitmap.createBitmap(southEast.width(), southEast.height(), Bitmap.Config.ARGB_8888);
        org.opencv.android.Utils.matToBitmap(southEast, bMap);
        southEast.release();
        int[] intArray = new int[bMap.getWidth()*bMap.getHeight()];
        //copy pixel data from the Bitmap into the 'intArray' array
        bMap.getPixels(intArray, 0, bMap.getWidth(), 0, 0, bMap.getWidth(), bMap.getHeight());

        LuminanceSource source = new RGBLuminanceSource(bMap.getWidth(), bMap.getHeight(),intArray);

        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        Result[] results = {};
        try {
            results = qrCodeMultiReader.decodeMultiple(bitmap);
        } catch (NotFoundException e) {
        }

        return results;

    }

    public void setBugRotate(boolean bugRotate) {
        mBugRotate = bugRotate;
    }
}
