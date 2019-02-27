package giorgioghisotti.unipr.it.gotcha

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import giorgioghisotti.unipr.it.gotcha.NetCameraView.Companion.getPath
import org.opencv.android.BaseLoaderCallback
import org.opencv.android.LoaderCallbackInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils.bitmapToMat
import org.opencv.android.Utils.matToBitmap
import org.opencv.core.*
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc

class ImageEditor : AppCompatActivity() {

    private var mImageView: ImageView? = null
    private var mOpenImageButton: Button? = null
    private var mFindObjectButton: Button? = null
    private var sourceImage: Bitmap? = null
    private var currentImage: Bitmap? = null
    private var imagePreview: Bitmap? = null
    private var net: Net? = null
    private var busy: Boolean = false

    // Initialize OpenCV manager.
    private val mLoaderCallback = object : BaseLoaderCallback(this) {
        override fun onManagerConnected(status: Int) {
            when (status) {
                LoaderCallbackInterface.SUCCESS -> {
                    if (net == null) {
                        val proto = getPath("MobileNetSSD_deploy.prototxt", this@ImageEditor)
                        val weights = getPath("mobilenet.caffemodel", this@ImageEditor)
                        net = Dnn.readNetFromCaffe(proto, weights)
                    }
                }
                else -> {
                    super.onManagerConnected(status)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_editor)

        mImageView = findViewById(R.id.image_editor_view)
        mOpenImageButton = findViewById(R.id.select_image_button)
        mOpenImageButton!!.setOnClickListener {
            getImgFromGallery()
        }
        mFindObjectButton = findViewById(R.id.find_object_button)
        mFindObjectButton!!.setOnClickListener {
            if(!busy) {
                try {
                    NetProcessing(net, sourceImage)
                } catch (e: OutOfMemoryError) {
                    Toast.makeText(this, "The image is too large!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (mImageView != null) mImageView!!.setImageBitmap(imagePreview)
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        genPreview()
    }

    override fun onResume() {
        super.onResume()
        OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, mLoaderCallback)
        genPreview()
    }

    private fun getImgFromGallery() {
        val galleryIntent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(galleryIntent, RESULT_LOAD_IMG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        try{
            if(requestCode == RESULT_LOAD_IMG && resultCode == Activity.RESULT_OK){
                if (data != null && data.data != null && mImageView != null) {
                    sourceImage = MediaStore.Images.Media.getBitmap(
                            this.contentResolver, data.data
                    )
                    currentImage = sourceImage
                } else return

                genPreview()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Something went wrong: "+e.toString(), Toast.LENGTH_LONG).show()
        }

    }

    /**
     * Scale image so it can be shown in an ImageView
     */
    private fun genPreview(){
        if (mImageView == null || currentImage == null) return

        if (mImageView!!.height < currentImage!!.height || mImageView!!.width < currentImage!!.width) {
            val scale = mImageView!!.height.toFloat() / currentImage!!.height.toFloat()
            if (currentImage != null) {
                imagePreview = Bitmap.createScaledBitmap(currentImage!!,
                        (scale * currentImage!!.width).toInt(),
                        (scale * currentImage!!.height).toInt(), true)
            }
        } else imagePreview = currentImage

        mImageView!!.setImageBitmap(imagePreview)

    }

    private fun NetProcessing(nnet: Net?, bbmp: Bitmap?) {
        var detections: Mat = Mat(0,0,0)
        val net: Net? = nnet
        var bmp: Bitmap? = bbmp

        busy = true

        object: Thread() {
            override fun run() {
                if (bmp == null) return
                super.run()
                this@ImageEditor.currentImage = null    //avoid filling the heap
                val bmp32 = bmp!!.copy(Bitmap.Config.ARGB_8888, false)  //required format for bitmaptomat
                val frame = Mat(0,0,0)
                bitmapToMat(bmp32, frame)
                Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2RGB)

                if (net == null) return
                val blob: Mat = Dnn.blobFromImage(frame, IN_SCALE_FACTOR,
                        Size(IN_WIDTH.toDouble(), IN_HEIGHT.toDouble()),
                        Scalar(MEAN_VAL, MEAN_VAL, MEAN_VAL), false)
                net.setInput(blob)
                detections = net.forward()
                blob.release()
                detections = detections.reshape(1, detections.total().toInt() / 7)

                /**
                 * Draw rectangles around detected objects (above a certain level of confidence)
                 */
                for (i in 0 until detections.rows()) {
                    val confidence = detections.get(i, 2)[0]
                    if (confidence > THRESHOLD) {
                        println("Confidence: $confidence")
                        val classId = detections.get(i, 1)[0].toInt()
                        val xLeftBottom = (detections.get(i, 3)[0] * frame.cols()).toInt()
                        val yLeftBottom = (detections.get(i, 4)[0] * frame.rows()).toInt()
                        val xRightTop = (detections.get(i, 5)[0] * frame.cols()).toInt()
                        val yRightTop = (detections.get(i, 6)[0] * frame.rows()).toInt()

                        Imgproc.rectangle(frame, Point(xLeftBottom.toDouble(), yLeftBottom.toDouble()),
                                Point(xRightTop.toDouble(), yRightTop.toDouble()),
                                Scalar(0.0, 255.0, 0.0))
                        val label = classNames[classId] + ": " + confidence
                        val baseLine = IntArray(1)
                        val labelSize = Imgproc.getTextSize(label, Core.FONT_HERSHEY_SIMPLEX, FONT_SCALE.toDouble(), 1, baseLine)

                        Imgproc.rectangle(frame, Point(xLeftBottom.toDouble(), yLeftBottom - labelSize.height),
                                Point(xLeftBottom + labelSize.width, (yLeftBottom + baseLine[0]).toDouble()),
                                Scalar(0.0, 0.0, 0.0), Core.FILLED)

                        Imgproc.putText(frame, label, Point(xLeftBottom.toDouble(), yLeftBottom.toDouble()),
                                Core.FONT_HERSHEY_SIMPLEX, FONT_SCALE.toDouble(), Scalar(255.0, 255.0, 255.0))
                    }
                }
                bmp = Bitmap.createBitmap(frame.cols(), frame.rows(), Bitmap.Config.ARGB_8888)
                matToBitmap(frame, bmp)
                frame.release()
                detections.release()
                this@ImageEditor.runOnUiThread(object: Runnable {
                    override fun run() {
                        this@ImageEditor.currentImage = bmp
                        genPreview()
                    }
                })
                this@ImageEditor.busy = false
            }
        }.start()
    }

    companion object {
        private const val FONT_SCALE = 1.0.toFloat()
        private const val THRESHOLD = 0.2
        private const val IN_WIDTH = 300
        private const val IN_HEIGHT = 300
        private const val WH_RATIO = IN_WIDTH.toFloat() / IN_HEIGHT
        private const val IN_SCALE_FACTOR = 0.007843
        private const val MEAN_VAL = 127.5

        private val classNames = arrayOf("background", "aeroplane", "bicycle", "bird", "boat", "bottle", "bus", "car", "cat", "chair", "cow", "diningtable", "dog", "horse", "motorbike", "person", "pottedplant", "sheep", "sofa", "train", "tvmonitor")

        private const val RESULT_LOAD_IMG = 1

    }
}