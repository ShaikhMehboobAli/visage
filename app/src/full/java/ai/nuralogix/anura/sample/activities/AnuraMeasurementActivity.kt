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
import ai.nuralogix.anuracream.ui.views.MeasurementView
import ai.nuralogix.anurasdk.config.CameraConfiguration
import ai.nuralogix.anurasdk.config.DfxPipeConfiguration
import ai.nuralogix.anurasdk.core.*
import ai.nuralogix.anurasdk.error.AnuraError
import ai.nuralogix.anurasdk.face.FaceTrackerAdapter
import ai.nuralogix.anurasdk.face.VisageFaceTracker
import ai.nuralogix.anurasdk.network.DeepAffexDataSpec
import ai.nuralogix.anurasdk.network.DeepFXClient
import ai.nuralogix.anurasdk.render.Render
import ai.nuralogix.anurasdk.utils.*
import ai.nuralogix.anurasdk.views.AbstractTrackerView
import ai.nuralogix.dfx.ChunkPayload
import ai.nuralogix.dfx.ConstraintResult
import ai.nuralogix.dfx.ConstraintResult.ConstraintReason
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.*

class AnuraMeasurementActivity :
        AppCompatActivity(),
        MeasurementPipelineListener,
        AbstractTrackerView.TrackerViewListener {

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
    private lateinit var core: Core
    private lateinit var visageFaceTracker: FaceTrackerAdapter
    private lateinit var render: Render
    private lateinit var measurementPipeline: MeasurementPipeline
    private val constraintAverager = RollingConstraintAverager()

    private lateinit var measurementView: MeasurementView
    private lateinit var countdown: Countdown
    private lateinit var trackerView: AbstractTrackerView
    private lateinit var dfxIDText: TextView
    private lateinit var snrTextView: TextView
    private lateinit var meansurementIdTv: TextView
    private lateinit var msiTv: TextView
    private lateinit var bpDTv: TextView
    private lateinit var bpSTv: TextView
    private lateinit var dialog: AlertDialog
    private var cameraId: String? = null
    private lateinit var format: VideoFormat
    private var preSnr = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) //FLAG_KEEP_SCREEN_ON
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_anura_measurement)

        dfxIDText = findViewById(R.id.dfxsdkversion_tv)
        measurementView = findViewById(R.id.measurement_view)
        trackerView = measurementView.tracker
        trackerView.setImageDimension(IMAGE_HEIGHT, IMAGE_WIDTH)
        trackerView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        trackerView.setListener(this)

        snrTextView = findViewById(R.id.snr_tv)
        meansurementIdTv = findViewById(R.id.measurementId_tv)
        msiTv = findViewById(R.id.msi_tv)
        bpDTv = findViewById(R.id.bpd_tv)
        bpSTv = findViewById(R.id.bps_tv)

        // start pipeline
        if (MainActivity.deviceToken != null) {
            DeepAffexDataSpec.REST_SERVER = MainActivity.SAMPLE_REST_URL
            DeepAffexDataSpec.WS_SERVER = MainActivity.SAMPLE_WS_URL
            DeepFXClient.getInstance().setTokenAuthorisation(MainActivity.deviceToken)
            DeepFXClient.getInstance().connect()
            format = VideoFormat(VideoFormat.ColorFormat.BGRA, 30, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())
            render = Render.createGL20Render(format)
            measurementView.trackerImageView.setRenderer(render as GLSurfaceView.Renderer)
            initPipeline()

        } else {
            Toast.makeText(this, resources.getString(R.string.ERR_SERVER_CONNECTION), Toast.LENGTH_LONG).show()
        }
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        AnuLogUtil.d(TAG, "onSizeChanged w=" + w + " h=" + h)
        val targetBox = ImageUtils.getFaceTargetBox(w, h, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())
        trackerView.setFaceTargetBox(targetBox)
        visageFaceTracker.setTrackingRegion(w, h, IMAGE_HEIGHT.toInt(), IMAGE_WIDTH.toInt())
    }

    override fun onResume() {

        if (this::core.isInitialized) {
            measurementPipeline.prepare(true)
            measurementPipeline.setListener(this)
        }
        trackerView.showMask(true)
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)

        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val xScale = screenWidth.toFloat() / IMAGE_HEIGHT
        val yScale = screenHeight.toFloat() / IMAGE_WIDTH
        val scale = Math.max(xScale, yScale)
        val top = (430 * scale).toInt()
        measurementView.setStarsPosition(30, top, 30, 0)
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        stopMeasurement(true)
        if (this::core.isInitialized) {
            measurementPipeline.unprepare()
        }
    }

    override fun onDestroy() {

        AnuLogUtil.d(TAG, "Measurement screen destroyed")
        countdown.stop()
        trackerView.destroy()
        if (this::core.isInitialized) {

            try {
                measurementPipeline.close()
                visageFaceTracker.close()
            } catch (e: Exception) {
                AnuLogUtil.e(TAG, "Failed on closing pipeline: " + e.message)
            }
        }
        if (this::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
        super.onDestroy()
    }

    override fun onMeasurementPreparing(dcResult: DCResult?) {
        lastStatus = ConstraintResult.ConstraintStatus.Good
        trackerView.flipTrackerColor(true)
        trackerView.showHistograms(true)
        measurementView.showStars(true)
        measurementView.setStars(-1f)
        measurementView.setPromptMsg(resources.getString(R.string.MEASURING_PERFECT))
        measurementView.showHeartRate(false)
    }

    override fun onMeasurementPrepared() {
        countdown.start()
        measurementView.setPromptMsg(resources.getString(R.string.MEASURING_COUNTDOWN))
    }

    override fun onMeasurementStarted() {
        measurementView.showHeartRate(true)
        measurementView.setHeartRate("--")
        measurementView.setPromptMsg("")
    }

    override fun onMeasurementStopped() {
        constraintAverager.clearReason()
        stopMeasurement(true)
    }

    override fun onMeasurementLightParam(dcResult: DCResult, ISO: Int, maxISO: Int, exposureDuration: Long, aeCompensation: Int, lightQAS: Float) {
        measurementView.setStars(lightQAS)
    }

    override fun onHistogramReceived(regionCenters: FloatArray?, histograms: FloatArray?) {
        trackerView.setHistograms(histograms, regionCenters)
    }

    override fun onMeasurementConstraint(isMeasuring: Boolean, status: ConstraintResult.ConstraintStatus?, constraints: MutableMap<String, ConstraintResult.ConstraintStatus>?) {
        val eStatus = status
        for ((k, v) in constraints!!) {
            val reason = ConstraintResult.getConstraintReasonFromString(k)
            if (v != ConstraintResult.ConstraintStatus.Good) {
                constraintAverager.addReasonValue(reason.value)
            } else {
                constraintAverager.clearReason()
            }
        }

        val eReason = constraintAverager.maxOccurred

        if (this::lastStatus.isInitialized && lastStatus != eStatus) {
            AnuLogUtil.v(TAG, "Constraints Status: $eStatus Reason: $eReason")
        }
        lastStatus = eStatus!!

        if (!isMeasuring) {
            countdown.cancel()
        }
        setCancelledReason(isMeasuring, status, ConstraintReason.values()[eReason])
    }

    override fun onMeasurementFinishAnalyzing() {
        super.onMeasurementFinishAnalyzing()
    }

    override fun onMeasurementPartialResult(payload: ChunkPayload?, result: AnalyzerResult) {
        handleResultSNR(result)
    }

    override fun onMeasurementDone(payload: ChunkPayload?, result: AnalyzerResult) {
        handleResultSNR(result)
    }

    override fun onMeaurementError(error: AnuraError) {
        handleMeasurementError(error)
    }

    override fun onMeasurementProgress(progressPercent: Float, frameRate: Float) {
        trackerView.setMeasurementProgress(progressPercent)
        if (progressPercent >= 100.0f) {
            stopMeasurement(false)
            if (progressPercent >= 100.0) {
                stopMeasurement(false)
                if (this@AnuraMeasurementActivity::dialog.isInitialized && dialog.isShowing) {
                    dialog.dismiss()
                }
                val builder: AlertDialog.Builder? = this@AnuraMeasurementActivity.let {
                    AlertDialog.Builder(it)
                }
                builder?.setMessage("Wait for final result...")?.setCancelable(false)
                dialog = builder?.create()!!
                dialog.show()
            }
        }
    }

    /**************************************Below are private methods****************************************/
    private fun initPipeline() {

        val faceIndex = intent.getIntExtra(FACE_ENGINE_KEY, 0)
        core = Core.createAnuraCore(this)
        constraintAverager.setReasonSpan(60)

        visageFaceTracker = VisageFaceTracker(core,
                filesDir.absolutePath + "/visage/${BuildConfig.VISAGE_LICENSE}.vlc",
                getFilesDir().getAbsolutePath() + "/visage/Facial Features Tracker - High.cfg", format.frequency / 2, core.faceTrackerHandler)

        val dfxConfig = DfxPipeConfiguration(this, null)
        TOTAL_NUMBER_CHUNKS = dfxConfig.getRuntimeParameterInt(DfxPipeConfiguration.RuntimeKey.TOTAL_NUMBER_CHUNKS, 6)
        val duration = TOTAL_NUMBER_CHUNKS * dfxConfig.getRuntimeParameterFloat(DfxPipeConfiguration.RuntimeKey.DURATION_PER_CHUNK, 5f)
        MEASUREMENT_DURATION = duration.toDouble()
        trackerView.setMeasurementDuration(duration.toDouble())

        cameraId = intent.getStringExtra(CameraConfigurationFragment.CAMERA_ID_KEY)

        countdown = DefaultCountdown(3, object : Countdown.Listener {
            override fun onCountdownTick(value: Int) {
                runOnUiThread {
                    measurementView.setCountdown(value.toString())
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
                    measurementView.setCountdown("0")
                }
            }

        })

        val cameraConfigStr = "{\n" +
                "   \"Camera\": {\n" +
                "    \"CAMERA_ID\": " + "\"" + cameraId + "\"" + "\n" +
                "    } \n" +
                "} \n"
        val configuration = CameraConfiguration(core.context, cameraConfigStr)
        measurementPipeline = MeasurementPipeline.createMeasurementPipeline(core, MEASUREMENT_DURATION.toInt(), format,
                getConfigFile(), render, configuration, visageFaceTracker, this)

        dfxIDText.text = "DFX ID: ${core.dfxSdkID}"
        trackerView.setMeasurementDuration(MEASUREMENT_DURATION)
    }


    private fun startMeasurement() {
        if (lastStatus != ConstraintResult.ConstraintStatus.Good) {
            AnuLogUtil.e(TAG, "lastStatus=" + lastStatus.name + " !=Good")
            return
        }

        trackerView.showMask(true)
        trackerView.showMeasurementProgress(true)
        trackerView.setMeasurementProgress(0.0f)
        measurementView.setPromptMsg("")
        measurementView.setCountdown("")

        meansurementIdTv.text = "Measurement ID: "
        snrTextView.text = "SNR: "
        msiTv.text = "MSI: "
        bpDTv.text = "Blood Pressure Diastolic: "
        bpSTv.text = "Blood Pressure Systolic: "

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
        measurementPipeline.startMeasurement(dummy_demographics, MainActivity.STUDY_ID, "", MainActivity.PARTNER_ID)
    }

    private fun stopMeasurement(stopResult: Boolean) {
        AnuLogUtil.d(TAG, "Stop measurement: $stopResult")
        if (!this::core.isInitialized) {
            return
        }
        handler.removeCallbacksAndMessages(null)
        if (stopResult) {
            measurementPipeline.stopMeasurement()
            if (this@AnuraMeasurementActivity::dialog.isInitialized && dialog.isShowing) {
                dialog.dismiss()
            }
        }
        countdown.stop()

        trackerView.showMask(true);
        trackerView.showMeasurementProgress(false);
        trackerView.setMeasurementProgress(0.0f);
        trackerView.flipTrackerColor(false);
        trackerView.showHistograms(false);
        trackerView.flipHistoColor(false);
        measurementView.reset();
        measurementView.showHeartRate(false);
    }

    private fun setCancelledReason(isMeasuring: Boolean, status: ConstraintResult.ConstraintStatus, eReason: ConstraintResult.ConstraintReason) {

        var reason = ""

        if (isMeasuring && (status == ConstraintResult.ConstraintStatus.Error || status == ConstraintResult.ConstraintStatus.Warn)) {
            reason += resources.getString(R.string.MEASUREMENT_CANCELED) + "\n"
        }

        when (eReason) {
            ConstraintReason.UNKNOWN, ConstraintReason.FACE_NONE, ConstraintReason.FACE_DIRECTION, ConstraintReason.FACE_OFFTARGET -> reason += resources.getString(R.string.ERR_CONSTRAINT_POSITION)
            ConstraintReason.FACE_FAR -> reason += resources.getString(R.string.ERR_CONSTRAINT_DISTANCE)
            ConstraintReason.FACE_MOVEMENT, ConstraintReason.CAMERA_MOVEMENT -> reason += resources.getString(R.string.ERR_CONSTRAINT_MOVEMENT)
            ConstraintReason.LOW_FPS -> reason += resources.getString(R.string.ERR_CONSTRAINT_FPS)
            ConstraintReason.IMAGE_BACKLIT -> reason += resources.getString(R.string.ERR_CONSTRAINT_BRIGHTNESS)
            ConstraintReason.IMAGE_DARK -> reason += resources.getString(R.string.ERR_CONSTRAINT_DARKNESS)
        }

        //AnuLogUtil.e(TAG, reason);
        val finalReason = reason
        runOnUiThread {
            measurementView.setPromptMsg(finalReason)
            measurementView.showHeartRate(false)
        }
    }

    private fun handleResultSNR(result: AnalyzerResult): Boolean {

        meansurementIdTv.text = "Measurement ID: ${result.measurementID}"
        snrTextView.text = "SNR: ${result.snr}"
        val resultIndex = result.resultIndex
        if (result.isSnrGood) {
            trackerView.flipHistoColor(true)
        } else {
            trackerView.flipHistoColor(false)
        }
        if (result.resultIndex < 2) {
            if (result.isSnrGood && result.getHeartRate() != -1) {
                setHeartRate("" + result.heartRate)
            }
        } else {
            if (result.isSnrGood && result.getHeartRate() != -1) {
                setHeartRate("" + result.heartRate)
            } else {
                setHeartRate("--")
            }
        }
        AnuLogUtil.d(TAG, "Current result index and snr: " + resultIndex + " " + result.snr)

        if (resultIndex == 2) {
            val snr15 = result.snr
            if (!result.isSnrGood && snr15 != -90f) {
                stopMeasurement(true)
                setMeasurementErrorMsg(resources.getString(R.string.ERR_MSG_SNR_SHORT))
                return false
            }
        } else if (resultIndex >= 3) {
            val snr = result.snr
            if ((!result.isSnrGood && snr != -100f) || (snr == -100f && preSnr == -100f)) {
                stopMeasurement(true)
                setMeasurementErrorMsg(resources.getString(R.string.ERR_MSG_SNR_SHORT))
                return false
            }
        }

        // last result
        if (resultIndex + 1 == result.numberOfResults) {
            if (result.isSnrGood) {
                msiTv.text = "MSI: ${result.msi}"
                bpDTv.text = "Blood Pressure Diastolic: ${result.bpDiastolic}"
                bpSTv.text = "Blood Pressure Systolic: ${result.bpSystolic}"
                if (result.resultIndex + 1 >= TOTAL_NUMBER_CHUNKS) {
                    stopMeasurement(true)
                }

            } else {
                stopMeasurement(true)
                setMeasurementErrorMsg(resources.getString(R.string.ERR_MSG_SNR_SHORT))
                return false
            }
        }
        preSnr = result.snr
        return true
    }

    private fun handleMeasurementError(error: AnuraError) {
        if (error == AnuraError.LICENSE_EXPIRED) {
            Toast.makeText(this, resources.getString(R.string.ERR_SERVER_CONNECTION), Toast.LENGTH_LONG).show()
        } else if (error == AnuraError.LOW_SNR) {
            setMeasurementErrorMsg(resources.getString(R.string.ERR_MSG_SNR_SHORT))
        } else if (error == AnuraError.DFX_VALIDATION_ERROR) {
            setMeasurementErrorMsg(resources.getString(R.string.ERR_MSG_ANALYZER_FAILED))
        } else if (error == AnuraError.DFX_GENERAL_ERROR || error == AnuraError.DFX_DISCONNECTED_ERROR) {
            setMeasurementErrorMsg(resources.getString(R.string.ERR_SERVER_CONNECTION))
        } else {
            setMeasurementErrorMsg(resources.getString(R.string.ERR_MSG_MEASUREMENT_FAILED))
        }
        stopMeasurement(true)
    }

    private fun setHeartRate(heartRate: String) {
        measurementView.setHeartRate(heartRate)
        measurementView.startHeartBeating()
    }

    private fun setMeasurementErrorMsg(message: String) {
        val errorMsg = "" + resources.getString(R.string.MEASUREMENT_CANCELED) + "\n" + message
        measurementView.setPromptMsg(errorMsg)
        measurementView.showHeartRate(false)
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
}