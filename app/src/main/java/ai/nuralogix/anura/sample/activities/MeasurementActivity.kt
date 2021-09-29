/*
 *              Copyright (c) 2016-2019, Nuralogix Corp.
 *                      All Rights reserved
 *
 *      THIS SOFTWARE IS LICENSED BY AND IS THE CONFIDENTIAL AND
 *      PROPRIETARY PROPERTY OF NURALOGIX CORP. IT IS
 *      PROTECTED UNDER THE COPYRIGHT LAWS OF THE USA, CANADA
 *      AND OTHER FOREIGN COUNTRIES. THIS SOFTWARE OR ANY
 *      PART THEREOF, SHALL NOT, WITHOUT THE PRIOR WRITTEN CONSENT
 *      OF NURALOGIX CORP, BE USED, COPIED, DISCLOSED,
 *      DECOMPILED, DISASSEMBLED, MODIFIED OR OTHERWISE TRANSFERRED
 *      EXCEPT IN ACCORDANCE WITH THE TERMS AND CONDITIONS OF A
 *      NURALOGIX CORP SOFTWARE LICENSE AGREEMENT.
 */

package ai.nuralogix.anura.sample.activities

import ai.nuralogix.anura.sample.BuildConfig
import ai.nuralogix.anura.sample.R
import ai.nuralogix.anura.sample.settings.CameraConfigurationFragment
import ai.nuralogix.anura.sample.settings.DfxPipeConfigurationFragment
import ai.nuralogix.anura.sample.utils.BundleUtils
import ai.nuralogix.anurasdk.camera.CameraAdapter
import ai.nuralogix.anurasdk.config.DfxPipeConfiguration
import ai.nuralogix.anurasdk.core.*
import ai.nuralogix.anurasdk.error.AnuraError
import ai.nuralogix.anurasdk.face.FaceTrackerAdapter
import ai.nuralogix.anurasdk.face.VisageFaceTracker
import ai.nuralogix.anurasdk.network.DeepAffexDataSpec
import ai.nuralogix.anurasdk.network.DeepFXClient
import ai.nuralogix.anurasdk.render.Render
import ai.nuralogix.anurasdk.render.opengl.GLSurfaceViewTracker
import ai.nuralogix.anurasdk.utils.*
import ai.nuralogix.anurasdk.views.AbstractTrackerView
import ai.nuralogix.dfx.ChunkPayload
import ai.nuralogix.dfx.Collector
import ai.nuralogix.dfx.ConstraintResult
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.*
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MeasurementActivity :
        AppCompatActivity(),
        DfxPipeListener,
        VideoSignalAnalysisListener,
        AbstractTrackerView.TrackerViewListener,
        FaceTrackerPipeListener,
        RenderingVideoSinkListener {

    companion object {
        const val TAG = "MeasurementActivity"

        //Generally, the front camera has a rotation angle, and the width and height are reversed, please note
        var IMAGE_WIDTH = 640.0f
        var IMAGE_HEIGHT = 480.0f
        var MEASUREMENT_DURATION = 30.0
        var TOTAL_NUMBER_CHUNKS = 6

        val handler = Handler(Looper.getMainLooper())

        val FACE_ENGINE_KEY = "face_engine"
    }

    private lateinit var lastStatus: ConstraintResult.ConstraintStatus
    private lateinit var lastConstraintReason: ConstraintResult.ConstraintReason
    private lateinit var core: Core
    private lateinit var visageFaceTracker: FaceTrackerAdapter
    private lateinit var cameraAdapter: CameraAdapter
    private lateinit var cloudAnalyzer: CloudAnalyzer
    private lateinit var cloudAnalyzerListener: CloudAnalyzerListener
    private lateinit var cameraSource: VideoSource
    private lateinit var preprocessPipe: VideoPipe
    private lateinit var faceTrackerPipe: VideoPipe
    private lateinit var dfxPipe: DfxPipe
    private lateinit var signalAnalysisPipe: VideoPipe
    private lateinit var renderingVideoSink: RenderingVideoSink
    private lateinit var render: Render
    private val constraintAverager = RollingConstraintAverager()

    private lateinit var viewTracker: GLSurfaceViewTracker
    private lateinit var dfxIDText: TextView
    private lateinit var countdownText: TextView
    private lateinit var trackerView: AbstractTrackerView
    private lateinit var countdown: Countdown
    private lateinit var snrTextView: TextView
    private lateinit var heartBeatTextView: TextView
    private lateinit var cancelledReasonTv: TextView
    private lateinit var meansurementIdTv: TextView
    private lateinit var msiTv: TextView
    private lateinit var bpDTv: TextView
    private lateinit var bpSTv: TextView
    private lateinit var starTv: TextView
    private var state = STATE.UNKNOWN
    private var firstFrameTimestamp = 0L
    private val showHistogramAndRegions = true
    private lateinit var dialog: AlertDialog
    private var cameraId: String? = null
    private var cameraHD: Boolean = false
    private lateinit var format: VideoFormat
    private var preSnr = 0f
    private var startFrameNumber = -1L
    private var preparingFrames = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) //FLAG_KEEP_SCREEN_ON
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_measurement)

        cameraHD = intent.getBooleanExtra(CameraConfigurationFragment.CAMERA_RESOLUTION, false)

        val previewSize = ImagePreviewResolutionUtils.getFitImagePreviewResolution()
        IMAGE_WIDTH = previewSize.width.toFloat();
        IMAGE_HEIGHT = previewSize.height.toFloat();
        if (cameraHD) {
            IMAGE_WIDTH = 1280.0f
            IMAGE_HEIGHT = 720.0f
            AnuLogUtil.d(TAG, "Change to 720p")
        }

        dfxIDText = findViewById(R.id.dfxsdkversion_tv)
        countdownText = findViewById(R.id.countdown)
        trackerView = findViewById(R.id.tracker_ui_view)
        trackerView.setImageDimension(IMAGE_HEIGHT, IMAGE_WIDTH)
        trackerView.showMask(true)
        trackerView.setListener(this)

        snrTextView = findViewById(R.id.snr_tv)
        heartBeatTextView = findViewById(R.id.heartbeat_tv)
        cancelledReasonTv = findViewById(R.id.cancelled_tv)
        meansurementIdTv = findViewById(R.id.measurementId_tv)
        msiTv = findViewById(R.id.msi_tv)
        bpDTv = findViewById(R.id.bpd_tv)
        bpSTv = findViewById(R.id.bps_tv)
        starTv = findViewById(R.id.lightstar_tv)

        // start pipeline
        if (MainActivity.deviceToken != null) {
            DeepAffexDataSpec.REST_SERVER = MainActivity.SAMPLE_REST_URL
            DeepAffexDataSpec.WS_SERVER = MainActivity.SAMPLE_WS_URL
            DeepFXClient.getInstance().setTokenAuthorisation(MainActivity.deviceToken)
            DeepFXClient.getInstance().connect()
            initPipeline()
        } else {
            Toast.makeText(this, resources.getString(R.string.ERR_SERVER_CONNECTION), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {

        menuInflater.inflate(R.menu.menus, menu)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {

        when (item?.itemId) {
            R.id.action_dfx_setting -> {
                val intent = SettingsActivity.createIntent(this, DfxPipeConfigurationFragment::class.java.name)
                intent.putExtra(BundleUtils.DFX_BUNDLE_KEY, BundleUtils.createRuntimeBundle(dfxPipe.configuration))
                startActivity(intent)
                return true
            }
        }


        return super.onOptionsItemSelected(item)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        AnuLogUtil.d(TAG, "onSizeChanged w=" + w + " h=" + h)
        if (!this::dfxPipe.isInitialized) {
            return
        }
        val targetBox = ImageUtils.getFaceTargetBox(w, h, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())
        dfxPipe.configuration.setRuntimeParameter(DfxPipeConfiguration.RuntimeKey.BOX_CENTER_X_PCT, targetBox.boxCenterX_pct.toString())
        dfxPipe.configuration.setRuntimeParameter(DfxPipeConfiguration.RuntimeKey.BOX_CENTER_Y_PCT, targetBox.boxCenterY_pct.toString())
        dfxPipe.configuration.setRuntimeParameter(DfxPipeConfiguration.RuntimeKey.BOX_WIDTH_PCT, targetBox.boxWidth_pct.toString())
        dfxPipe.configuration.setRuntimeParameter(DfxPipeConfiguration.RuntimeKey.BOX_HEIGHT_PCT, targetBox.boxHeight_pct.toString())
        dfxPipe.updateRuntimeConfig()
        trackerView.setFaceTargetBox(targetBox)
        visageFaceTracker.setTrackingRegion(w, h, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())
    }

    override fun onResume() {

        if (this::core.isInitialized) {
            reConnectCameraSource()
            renderingVideoSink.start()
        }
        state = STATE.IDLE
        super.onResume()
    }

    override fun onNewIntent(intent: Intent?) {
        val configBundle = intent?.getBundleExtra(BundleUtils.DFX_BUNDLE_KEY)
        configBundle?.let {
            AnuLogUtil.d(TAG, "on resume with bundle $configBundle")
            BundleUtils.updateRuntimeConfiguration(dfxPipe.configuration, configBundle)
            dfxPipe.updateRuntimeConfig()
            TOTAL_NUMBER_CHUNKS = dfxPipe.configuration.getRuntimeParameterInt(DfxPipeConfiguration.RuntimeKey.TOTAL_NUMBER_CHUNKS, 6)
            val duration = TOTAL_NUMBER_CHUNKS * dfxPipe.configuration.getRuntimeParameterFloat(DfxPipeConfiguration.RuntimeKey.DURATION_PER_CHUNK, 5f)
            MEASUREMENT_DURATION = duration.toDouble()
            trackerView.setMeasurementDuration(duration.toDouble())
        }
        super.onNewIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        if (state != STATE.UNKNOWN) {
            stopMeasurement(true)
            if (this::core.isInitialized) {
                renderingVideoSink.stop()
                disconnectCamera()
            }
        }
    }

    override fun onDestroy() {
        AnuLogUtil.d(TAG, "onDestroy")

        var closeStartTime = SystemClock.elapsedRealtime()
        trackerView.destroy()
        if (state != STATE.UNKNOWN && this::core.isInitialized) {

            core.close()
            var closeStep0Time = SystemClock.elapsedRealtime()
            AnuLogUtil.d(TAG, "Close step 0 consuming: ${closeStep0Time - closeStartTime}")
            cameraSource.close()
            var closeStep1Time = SystemClock.elapsedRealtime()
            AnuLogUtil.d(TAG, "Close step 1 consuming: ${closeStep1Time - closeStep0Time}")
            preprocessPipe.close()
            faceTrackerPipe.close()
            visageFaceTracker.close()
            var closeStep2Time = SystemClock.elapsedRealtime()
            AnuLogUtil.d(TAG, "Close step 1 consuming: ${closeStep2Time - closeStep1Time}")
            dfxPipe.close()
            var closeStep3Time = SystemClock.elapsedRealtime()
            AnuLogUtil.d(TAG, "Close step 2 consuming: ${closeStep3Time - closeStep2Time}")
            renderingVideoSink.close()
            var closeStep4Time = SystemClock.elapsedRealtime()
            AnuLogUtil.d(TAG, "Close step 4 consuming: ${closeStep4Time - closeStep3Time}")
        }
        if (this::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
        super.onDestroy()
        var closeStep5Time = SystemClock.elapsedRealtime()
        AnuLogUtil.d(TAG, "Close step 5 consuming: ${closeStep5Time - closeStartTime}")
    }

    override fun onConstraintReceived(status: ConstraintResult.ConstraintStatus, constraints: Map<String, ConstraintResult.ConstraintStatus>) {
        val eStatus = status
        for ((k, v) in constraints) {
            val reason = ConstraintResult.getConstraintReasonFromString(k)
            if (v != ConstraintResult.ConstraintStatus.Good) {
                constraintAverager.addReasonValue(reason.value)
            } else {
                constraintAverager.clearReason()
            }
        }

        val eReason = constraintAverager.maxOccurred

        if (this::lastStatus.isInitialized && lastStatus != eStatus) {
            AnuLogUtil.v(TAG, "Constraints Status: $eStatus Reason: $eReason State: $state")
        }
        lastStatus = eStatus

        if (eStatus == ConstraintResult.ConstraintStatus.Good) {
            runOnUiThread {
                if (state == STATE.IDLE) {
                    render.showHistograms(showHistogramAndRegions)
                    render.showLineFromRegionToHist(showHistogramAndRegions)
                    state = STATE.CALIBRATING
                } else if (state == STATE.CALIBRATING) {
                    cancelledReasonTv.text = resources.getString(R.string.WARNING_CONSTRAINT_EXPOSURE)
                    if (preparingFrames < 30) {
                        AnuLogUtil.d(TAG, "Waiting for 30 frames before calibration: $preparingFrames")
                        return@runOnUiThread
                    } else {
                        state = STATE.COUNTDOWN
                        countdown.start()
                        cancelledReasonTv.text = resources.getString(R.string.MEASURING_PERFECT)
                    }
                }
            }
        } else {
            if (eStatus == ConstraintResult.ConstraintStatus.Error && state == STATE.MEASURING) {
                setCancelledReason(ConstraintResult.ConstraintReason.values()[eReason])
                runOnUiThread {
                    stopMeasurement(true)
                }
            } else if (state == STATE.COUNTDOWN || state == STATE.CALIBRATING) {
                countdown.cancel()
                runOnUiThread {
                    stopMeasurement(false)
                }
                setCancelledReason(ConstraintResult.ConstraintReason.values()[eReason])
            }
        }
    }

    override fun onChunkPayloadReceived(payload: ChunkPayload, collector: Collector) {
        AnuLogUtil.d(TAG, "Receives chunk payload: ${payload.chunkNumber}")
        val savingPath: String = (getExternalFilesDir(null)?.absolutePath) + File.separator
        writeByteToFile(payload.payload, savingPath + "payload" + "_" + payload.chunkNumber + ".dat")
        runOnUiThread {
            cancelledReasonTv.text = resources.getString(R.string.Analyzing_Data) + " " + (payload.chunkNumber + 1)
        }
    }

    override fun onFrameRateEvent(frameNumber: Long, frameRate: Float, frameTimestamp: Long) {
        if (state == STATE.CALIBRATING) {
            if (startFrameNumber < 0) {
                startFrameNumber = frameNumber
            }
            preparingFrames = frameNumber - startFrameNumber

        }
        else if (state == STATE.MEASURING) {
            runOnUiThread {
                if (state == STATE.MEASURING) {
                    if (firstFrameTimestamp == 0L) {
                        firstFrameTimestamp = frameTimestamp
                    }
                    val totalFrameDuration = (frameTimestamp - firstFrameTimestamp) / 1000000000.0f
                    val progressPercent = totalFrameDuration * 100 / MEASUREMENT_DURATION
                    if (progressPercent >= 100.0) {
                        state = STATE.DONE
                        stopMeasurement(false)
                        if (this@MeasurementActivity::dialog.isInitialized && dialog.isShowing) {
                            dialog.dismiss()
                        }
                        val builder: AlertDialog.Builder? = this@MeasurementActivity.let {
                            AlertDialog.Builder(it)
                        }
                        builder?.setMessage("Wait for final result...")?.setCancelable(false)
                        dialog = builder?.create()!!
                        dialog.show()
                    }
                    trackerView.setMeasurementProgress(progressPercent.toFloat())
                    if (frameNumber % 10 == 0L) {
                        AnuLogUtil.d(TAG, "Frame number: $frameNumber total frame length: $totalFrameDuration seconds fps: $frameRate")
                    }
                }
            }
        } else {
            if (frameNumber % 10 == 0L) {
                AnuLogUtil.d(TAG, "Frame number: $frameNumber seconds fps: $frameRate")
            }
        }
    }

    override fun onNoFaceDetected() {
        if (state == STATE.MEASURING) {
            setCancelledReason(ConstraintResult.ConstraintReason.FACE_NONE)
            runOnUiThread {
                stopMeasurement(true)
            }
        } else if (state == STATE.COUNTDOWN || state == STATE.CALIBRATING) {
            countdown.cancel()
            runOnUiThread {
                stopMeasurement(false)
            }
        }
    }

    override fun onFaceDetected(faces: MutableList<String>) {
    }

    override fun onLightQualityScore(dcResult: DCResult, lightQAS: Float) {
        val isoQAS = Helpers.calculateQAS(-350f, -100f, -1f, cameraAdapter.iso.toFloat())
        val finalQAS = 0.1f * isoQAS / 0.9 + lightQAS

        runOnUiThread {
            if (finalQAS >= 0 && finalQAS <= 1) {
                starTv.text = "Light star: one star"
            } else if (finalQAS > 1 && finalQAS <= 2) {
                starTv.text = "Light star: two stars"
            } else if (finalQAS > 2 && finalQAS <= 3) {
                starTv.text = "Light star: three stars"
            } else if (finalQAS > 3 && finalQAS <= 4) {
                starTv.text = "Light star: four stars"
            } else if (finalQAS > 4 && finalQAS <= 5) {
                starTv.text = "Light star: five stars"
            }
        }
    }

    /**************************************Below are private methods****************************************/

    private fun initPipeline() {
        val faceIndex = intent.getIntExtra(FACE_ENGINE_KEY, 0)
        core = Core.createAnuraCore(this)
        constraintAverager.setReasonSpan(60)
        format = VideoFormat(VideoFormat.ColorFormat.BGRA, 30, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())

        //val videoFormat = VideoFormat(VideoFormat.VideoCodec.H264, VideoFormat.ColorFormat.BGRA, 30, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())
        viewTracker = findViewById(R.id.tracker_opengl_view)
        render = Render.createGL20Render(format)
        viewTracker.setRenderer(render as GLSurfaceView.Renderer)
        visageFaceTracker = VisageFaceTracker(core,
                filesDir.absolutePath + "/visage/${BuildConfig.VISAGE_LICENSE}.vlc",
                filesDir.absolutePath + "/visage/Facial Features Tracker - High.cfg", format.frequency / 2, core.faceTrackerHandler)
        AnuLogUtil.d(TAG, "Create visage face tracker")


        cloudAnalyzerListener = object : CloudAnalyzerListener {
            override fun onStartAnalyzing() {
            }

            override fun onResult(result: AnalyzerResult) {
                val jsonResult = result.jsonResult
                AnuLogUtil.d(TAG, "JSON result: $jsonResult index: ${result.resultIndex}")

                var shouldCancel = false
                if (result.resultIndex == 2) {
                    val snr15 = result.snr
                    if (!result.isSnrGood && snr15 != -90f) {
                        shouldCancel = true
                    }
                }
                else if (result.resultIndex >= 3) {
                    val snr = result.snr
                    if (!result.isSnrGood && snr != -100f || snr == -100f && preSnr == -100f) {
                        shouldCancel = true
                    }
                }
                // last result
                if (result.resultIndex + 1 == result.numberOfResults) {
                    if (!result.isSnrGood) {
                        shouldCancel = true
                    }
                }
                preSnr = result.snr

                if (shouldCancel) {
                    runOnUiThread {
                        stopMeasurement(true)
                    }
                    return
                }

                runOnUiThread {
                    meansurementIdTv.text = "Measurement ID: ${result.measurementID}"
                    snrTextView.text = "SNR: ${result.snr}"
                    heartBeatTextView.text = "Heart Beat: ${result.heartRate}"
                    msiTv.text = "MSI: ${result.msi}"
                    bpDTv.text = "Blood Pressure Diastolic: ${result.bpDiastolic}"
                    bpSTv.text = "Blood Pressure Systolic: ${result.bpSystolic}"

                    if (result.isSnrGood()) {
                        render.setHistogramsColor(255, 255, 0, 0)
                    } else {
                        render.setHistogramsColor(255, 255, 255, 255)
                    }

                    if (result.resultIndex + 1 >= TOTAL_NUMBER_CHUNKS) {
                        stopMeasurement(true)
                    }
                }
            }

            override fun onError(error: AnuraError) {
                AnuLogUtil.e(TAG, "CloudAnalyzerListener onError:" + error.name)
                runOnUiThread {
                    if (error == AnuraError.NETWORK_ERROR) {
                        DeepFXClient.getInstance().connect();
                    }
                    stopMeasurement(true)
                }
            }
        }

//        fileVideoSource = FileVideoSourceImpl("FileVideoSource", core, videoFormat)
//        val videoDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
//        AnuLogUtil.d(TAG, "Video file path: ${videoDir.absolutePath}")
//        fileVideoSource.setFileSource(videoDir.absolutePath + "/Camera/1583406136199.mp4")

        val dfxConfig = DfxPipeConfiguration(this, null)
        TOTAL_NUMBER_CHUNKS = dfxConfig.getRuntimeParameterInt(DfxPipeConfiguration.RuntimeKey.TOTAL_NUMBER_CHUNKS, 6)
        val duration = TOTAL_NUMBER_CHUNKS * dfxConfig.getRuntimeParameterFloat(DfxPipeConfiguration.RuntimeKey.DURATION_PER_CHUNK, 5f)
        MEASUREMENT_DURATION = duration.toDouble()
        trackerView.setMeasurementDuration(duration.toDouble())

        cameraId = intent.getStringExtra(CameraConfigurationFragment.CAMERA_ID_KEY)
        cloudAnalyzer = CloudAnalyzer.createCloudAnalyzer(core, DeepFXClient.getInstance(), cloudAnalyzerListener)
        preprocessPipe = VideoPipe.createPreprocessPipe("PreprocessPipe", core, format)
        faceTrackerPipe = VideoPipe.createFaceTrackerPipe("FaceTrackerPipe", core, format, visageFaceTracker, this)
        renderingVideoSink = RenderingVideoSink.createRenderingVideoSink("RenderingSink", core, format, render, this)
        //dumpVideoSink = VideoSink.createDumpVideoSink("DumpVideoSink", core, format)

        dfxPipe = DfxPipe.createDfxPipe("DfxPipe", core, format,
                core.createDFXFactory(getConfigFile(), "discrete")!!, dfxConfig.toJSONObject().toString(), cloudAnalyzer, this, renderingVideoSink)

        signalAnalysisPipe = VideoPipe.createVideoSignalAnalysisPipe("AnalysisPipe", core, format, this)
        preprocessPipe.connect(signalAnalysisPipe)
        signalAnalysisPipe.connect(faceTrackerPipe)
        faceTrackerPipe.connect(dfxPipe)
        dfxPipe.connect(renderingVideoSink)

        countdown = DefaultCountdown(3
                , object : Countdown.Listener {
            override fun onCountdownTick(value: Int) {
                runOnUiThread {
                    countdownText.text = value.toString()
                }
            }

            override fun onCountdownEnd() {
                if (MainActivity.deviceToken != null) {
                    runOnUiThread {
                        startMeasurement()
                    }
                } else {
                    runOnUiThread {
                        stopMeasurement(true)
                        countdown.start()
                        AnuLogUtil.d(TAG, "No user token restart...")
                    }
                }
            }

            override fun onCountdownCancel() {

                runOnUiThread {
                    stopMeasurement(true)
                    countdownText.text = "0"
                }
            }

        })

        dfxIDText.text = "DFX ID: ${core.dfxSdkID}"
        trackerView.setMeasurementDuration(MEASUREMENT_DURATION)
    }


    private fun startMeasurement() {
        if (lastStatus != ConstraintResult.ConstraintStatus.Good) {
            AnuLogUtil.e(TAG, "lastStatus=" + lastStatus.name + " !=Good")
            state = STATE.IDLE
            return
        }
        cameraAdapter.lockExposure(true)
        cameraAdapter.lockWhiteBalance(true)
        cameraAdapter.lockFocus(true)
        firstFrameTimestamp = 0L
        trackerView.showMask(false)
        trackerView.showMeasurementProgress(true)
        trackerView.setMeasurementProgress(0.0f)
        cancelledReasonTv.text = resources.getString(R.string.MEASURING_STARTED)
        countdownText.visibility = View.GONE
        meansurementIdTv.text = "Measurement ID: "
        snrTextView.text = "SNR: "
        heartBeatTextView.text = "Heart Beat: "
        msiTv.text = "MSI: "
        bpDTv.text = "Blood Pressure Diastolic: "
        bpSTv.text = "Blood Pressure Systolic: "

        state = STATE.MEASURING
        // We can send ground truth of demographics to DeepAffex, so that
        // it increases the accuracy of predicted results. Below is an example
        // of demographics, where the keys should be kept the same.
        val dummy_demographics = mapOf(
                "gender:1" to "male",
                "age:1" to "20",
                "height:1" to "175",
                "weight:1" to "75",
                "smoking:1" to "0",
                "diabetes:1" to "0",
                "bloodpressuremedication:1" to "0")
        dfxPipe.startCollect(dummy_demographics)
        cloudAnalyzer.startAnalyzing(MainActivity.STUDY_ID, "", MainActivity.PARTNER_ID)
    }

    private fun stopMeasurement(stopResult: Boolean) {
        AnuLogUtil.d(TAG, "Stop measurement: $stopResult")
        if (!this::core.isInitialized) {
            return
        }
        handler.removeCallbacksAndMessages(null)
        countdown.stop()
        cameraAdapter.lockWhiteBalance(false)
        cameraAdapter.lockExposure(false)
        cameraAdapter.lockFocus(false)

        startFrameNumber = -1L
        preparingFrames = 0L

        render.showHistograms(false)
        render.showLineFromRegionToHist(false)
        render.setHistogramsColor(255, 255, 255, 255);
        trackerView.showMask(true)
        trackerView.showMeasurementProgress(false)
        trackerView.setMeasurementProgress(0.0f)
        countdownText.text = "0"
        countdownText.visibility = View.VISIBLE
        cancelledReasonTv.text = ""

        if (stopResult) {
            state = STATE.IDLE
            cloudAnalyzer.stopAnalyzing()
            dfxPipe.stopCollect()
            if (this@MeasurementActivity::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private fun setCancelledReason(eReason: ConstraintResult.ConstraintReason) {

        var reason: String

        when (eReason) {
            ConstraintResult.ConstraintReason.UNKNOWN, ConstraintResult.ConstraintReason.FACE_NONE -> {
                reason = resources.getString(R.string.ERR_CONSTRAINT_POSITION)
            }
            ConstraintResult.ConstraintReason.FACE_FAR ->
                reason = resources.getString(R.string.ERR_CONSTRAINT_DISTANCE)
            ConstraintResult.ConstraintReason.FACE_OFFTARGET ->
                reason = resources.getString(R.string.ERR_CONSTRAINT_POSITION)
            ConstraintResult.ConstraintReason.FACE_DIRECTION ->
                reason = resources.getString(R.string.WARNING_CONSTRAINT_POSITION)
            ConstraintResult.ConstraintReason.FACE_MOVEMENT, ConstraintResult.ConstraintReason.CAMERA_MOVEMENT ->
                reason = resources.getString(R.string.ERR_CONSTRAINT_MOVEMENT)
            ConstraintResult.ConstraintReason.LOW_FPS ->
                reason = resources.getString(R.string.ERR_CONSTRAINT_FPS)
            ConstraintResult.ConstraintReason.IMAGE_BACKLIT ->
                reason = resources.getString(R.string.ERR_CONSTRAINT_BRIGHTNESS)
            ConstraintResult.ConstraintReason.IMAGE_DARK ->
                reason = resources.getString(R.string.ERR_CONSTRAINT_DARKNESS)
            else -> reason = ""
        }

        if (!this::lastConstraintReason.isInitialized) {
            cameraAdapter.lockExposure(false)
            cameraAdapter.lockWhiteBalance(false)
            cameraAdapter.lockFocus(false)
        } else if (this::lastConstraintReason.isInitialized && lastConstraintReason != eReason) {
            AnuLogUtil.e(TAG, reason)
            cameraAdapter.lockExposure(false)
            cameraAdapter.lockWhiteBalance(false)
            cameraAdapter.lockFocus(false)
        }
        lastConstraintReason = eReason

        runOnUiThread {
            cancelledReasonTv.text = reason
        }
    }

    private fun getConfigFile(): String {
        val netFilePath = this.filesDir.absolutePath + File.separator + ConfigActivity.CONFIG_FILE_NAME
        val netFile = File(netFilePath)
        if (netFile.exists() && netFile.isFile) {
            AnuLogUtil.d(TAG, "getConfigFile use netFile path=$netFilePath")
            return netFilePath
        }
        return this.filesDir.absolutePath + File.separator + "mobile20210122.dat"
    }

    private fun disconnectCamera() {
        cameraSource.disconnect(preprocessPipe)
        cameraSource.close()
    }

    private fun reConnectCameraSource() {
        cameraAdapter = CameraAdapter.createAndroidCamera2Adapter(cameraId, core, null, null)
        cameraSource = VideoSource.createCameraSource("CameraSource", core, format, cameraAdapter)
        cameraSource.connect(preprocessPipe)
    }

    protected fun writeByteToFile(data: ByteArray, filePath: String) {
        try {
            val outputStreamWriter = FileOutputStream(filePath)
            outputStreamWriter.write(data)
            outputStreamWriter.close()
        } catch (e: IOException) {
            AnuLogUtil.e("Exception", "File write failed: $e")
        }
    }

    private enum class STATE {
        UNKNOWN,
        IDLE,
        CALIBRATING,
        COUNTDOWN,
        MEASURING,
        DONE,
    }
}