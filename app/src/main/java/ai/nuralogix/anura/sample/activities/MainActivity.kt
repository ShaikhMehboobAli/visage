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
import ai.nuralogix.anura.sample.activities.MeasurementActivity.Companion.FACE_ENGINE_KEY
import ai.nuralogix.anura.sample.settings.CameraConfigurationFragment
import ai.nuralogix.anura.sample.utils.BundleUtils
import ai.nuralogix.anurasdk.camera.CameraCapability
import ai.nuralogix.anurasdk.camera.CameraInfo
import ai.nuralogix.anurasdk.error.AnuraError
import ai.nuralogix.anurasdk.network.DeepAffexDataSpec
import ai.nuralogix.anurasdk.network.DeepFXClient
import ai.nuralogix.anurasdk.network.RestClient
import ai.nuralogix.anurasdk.utils.AnuLogUtil
import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ANURA_MainActivity"
        const val MY_PERMISSIONS_REQUEST = 1
        val FACE_ENGINES = arrayOf("Visage")

        var SAMPLE_REST_URL = BuildConfig.SAMPLE_REST_URL
        var SAMPLE_WS_URL = BuildConfig.SAMPLE_WS_URL
        var PARTNER_ID = BuildConfig.PARTNER_ID
        var LICENSE_KEY = BuildConfig.LICENSE_KEY
        var STUDY_ID = BuildConfig.STUDY_ID

        var deviceToken: String? = null
    }

    private var faceIndex = 0
    private var cameraId: String? = null
    private var cameraHD: Boolean = false
    private var goMeasurementBtn: Button? = null
    private var goAnuraMeasurementBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        restoreConfig()
        goMeasurementBtn = findViewById<Button>(R.id.go_measuremnt_btn)
        goMeasurementBtn?.setOnClickListener {
            val anuraSupportedCameras: List<CameraInfo> = CameraCapability.createCameraCapabilityInstance(baseContext).getAnuraSupportedCameras(CameraInfo.CAMERA_CHECK_MIN_PIXEL_FLAG)
            if (anuraSupportedCameras.isEmpty()) {
                Toast.makeText(baseContext, "Camera does not support", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val intent = Intent(this@MainActivity, MeasurementActivity::class.java)
            intent.putExtra(FACE_ENGINE_KEY, faceIndex)
            intent.putExtra(CameraConfigurationFragment.CAMERA_ID_KEY, cameraId)
            startActivity(intent)
        }

        goAnuraMeasurementBtn = findViewById<Button>(R.id.go_anura_measuremnt_btn)
        goAnuraMeasurementBtn!!.visibility = if (BuildConfig.hasFullFeature) View.VISIBLE else View.GONE
        goAnuraMeasurementBtn?.setOnClickListener {
            val anuraSupportedCameras: List<CameraInfo> = CameraCapability.createCameraCapabilityInstance(baseContext).getAnuraSupportedCameras(CameraInfo.CAMERA_CHECK_MIN_PIXEL_FLAG)
            if (anuraSupportedCameras.isEmpty()) {
                Toast.makeText(baseContext, "Camera does not support", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            faceIndex = 2

            val intent = Intent(this@MainActivity, Class.forName("ai.nuralogix.anura.sample.activities.AnuraMeasurementActivity"))
            intent.putExtra(FACE_ENGINE_KEY, faceIndex)
            intent.putExtra(CameraConfigurationFragment.CAMERA_ID_KEY, cameraId)
            intent.putExtra(CameraConfigurationFragment.CAMERA_RESOLUTION, cameraHD)
            startActivity(intent)
        }

        val goConfigBtn = findViewById<Button>(R.id.go_config_btn)
        goConfigBtn.setOnClickListener {
            val intent = Intent(this, ConfigActivity::class.java)
            startActivity(intent)
        }

        AnuLogUtil.setShowLog(false)
        copyFileOrDir("visage")
        copyFileOrDir("mobile20210122.dat")


        requestPermission()
    }

    override fun onResume() {
        super.onResume()

        val goMeasurementBtn = findViewById<Button>(R.id.go_measuremnt_btn)
        // The following code snippet illustrates verify license/token validity before proceeding measurement
        deviceToken?.let {
            RestClient.getInstance().getLicenseStatus(deviceToken)

            RestClient.getInstance().setListener(object : RestClient.Listener {
                override fun onResult(token: Int, result: String?) {
                    when (token) {
                        RestClient.ACTION_REGISTER_LICENSE -> onResultRegisterLicense(result)
                        RestClient.ACTION_EXPIRE_LICENSE -> onResultLicenseStatus(result)
                        RestClient.ACTION_GET_STUDY_FILE -> onResultGetStudyFile(result)
                        RestClient.ACTION_GET_STUDY_HASH -> onResultGetStudyHash(result)
                    }
                }

                override fun onError(message: String?, token: Int) {
                    if (token == RestClient.ACTION_REGISTER_LICENSE) {
                        Toast.makeText(this@MainActivity, "Could not register license: $message", Toast.LENGTH_LONG).show()
                    } else if (token == RestClient.ACTION_EXPIRE_LICENSE) {
                        runOnUiThread {
                            val results = StringBuilder()
                                    .append(Build.MANUFACTURER)
                                    .append(" / ")
                                    .append(Build.MODEL)
                                    .append(" / ")
                                    .append(Build.VERSION.RELEASE)
                            val device = results.toString()
                            RestClient.getInstance().registerLicense(device, BuildConfig.VERSION_NAME, BuildConfig.LICENSE_KEY)
                        }
                    }
                }

            })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menus, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_device_info -> {
                val intent = Intent(this@MainActivity, DeviceInfoActivity::class.java)
                startActivity(intent)
                return true
            }

            R.id.action_camera_choice -> {
                val intent = SettingsActivity.createIntent(this, CameraConfigurationFragment::class.java.name)
                intent.putExtra(BundleUtils.CAMERA_BUNDLE_KEY, Bundle())
                startActivity(intent)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent?) {
        val configBundle = intent?.getBundleExtra(BundleUtils.CAMERA_BUNDLE_KEY)
        configBundle?.let {
            cameraId = configBundle.getString(CameraConfigurationFragment.CAMERA_ID_KEY)
            cameraHD = configBundle.getBoolean(CameraConfigurationFragment.CAMERA_RESOLUTION)
            AnuLogUtil.d(MainActivity.TAG, "on resume with bundle $configBundle, $cameraId, high resolution: $cameraHD")
        }
        super.onNewIntent(intent)
    }

    fun copyFileOrDir(path: String) {
        val assetManager = this.assets
        val assets: Array<String>?
        try {
            assets = assetManager.list(path)
            if (assets!!.isEmpty()) {
                copyFile(path)
            } else {
                val fullPath = filesDir.absolutePath + File.separator + path
                val dir = File(fullPath)
                if (!dir.exists())
                    dir.mkdir()
                for (asset in assets) {
                    copyFileOrDir("$path/$asset")
                }
            }
        } catch (ex: IOException) {
            AnuLogUtil.e(TAG, "copyFileOrDir exception: ${ex.message}")
        } catch (e: Exception) {
            AnuLogUtil.e(TAG, "copyFileOrDir exception: ${e.message}")
        }

    }

    private fun restoreConfig() {
        val pref = getSharedPreferences(ConfigActivity.PREF_NAME, MODE_PRIVATE)
        val restUrl = pref.getString(ConfigActivity.REST_SERVER_KEY, SAMPLE_REST_URL)
        SAMPLE_REST_URL = restUrl!!
        val wsUrl = pref.getString(ConfigActivity.WS_SERVER_KEY, SAMPLE_WS_URL)
        SAMPLE_WS_URL = wsUrl!!
        val partnerID = pref.getString(ConfigActivity.PARTNER_ID_KEY, PARTNER_ID)
        PARTNER_ID = partnerID!!
        val license = pref.getString(ConfigActivity.LICENSE_KEY, LICENSE_KEY)
        LICENSE_KEY = license!!
        val studyId = pref.getString(ConfigActivity.STUDY_ID_KEY, STUDY_ID)
        STUDY_ID = studyId!!
        deviceToken = pref.getString(ConfigActivity.DEVICE_TOKEN, null)
        DeepAffexDataSpec.REST_SERVER = SAMPLE_REST_URL
        DeepAffexDataSpec.WS_SERVER = SAMPLE_WS_URL
    }

    private fun copyFile(filename: String) {
        val assetManager = this.assets

        val `in`: InputStream
        val out: OutputStream
        try {
            `in` = assetManager.open(filename)
            val newFileName = filesDir.absolutePath + File.separator + filename

            out = FileOutputStream(newFileName)
            val buffer = ByteArray(1024)
            var read = `in`.read(buffer)
            while (read != -1) {
                out.write(buffer, 0, read)
                read = `in`.read(buffer)
            }
            `in`.close()
            out.flush()
            out.close()

        } catch (e: Exception) {
            AnuLogUtil.e(TAG, "copyFile exception: ${e.message}")
        }

    }

    private fun showFaceEngineChoiceDialog() {
        val builder: AlertDialog.Builder? = let {
            AlertDialog.Builder(it)
        }
        builder?.apply {
            setTitle("Choose Face Engine")
            setItems(FACE_ENGINES, DialogInterface.OnClickListener { dialog, which ->
                faceIndex = which
                if (faceIndex == 2) {
                    showAlertDialog()
                } else {
                    val intent = Intent(this@MainActivity, MeasurementActivity::class.java)
                    intent.putExtra(FACE_ENGINE_KEY, faceIndex)
                    intent.putExtra(CameraConfigurationFragment.CAMERA_ID_KEY, cameraId)
                    intent.putExtra(CameraConfigurationFragment.CAMERA_RESOLUTION, cameraHD)
                    startActivity(intent)
                }
            })
            setNegativeButton("Cancel") { _, _ -> }
        }

        val dialog: AlertDialog? = builder?.create()
        dialog?.show()
    }

    private fun showAlertDialog() {
        val builder: AlertDialog.Builder? = let {
            AlertDialog.Builder(it)
        }
        builder?.apply {
            setPositiveButton("OK") { _, _ ->
                val intent = Intent(this@MainActivity, MeasurementActivity::class.java)
                intent.putExtra(FACE_ENGINE_KEY, faceIndex)
                intent.putExtra(CameraConfigurationFragment.CAMERA_ID_KEY, cameraId)
                intent.putExtra(CameraConfigurationFragment.CAMERA_RESOLUTION, cameraHD)
                startActivity(intent)
            }
            setNegativeButton("Cancel") { _, _ -> }
            setMessage("Please note that the sample is using Visage for face tracking with temporary license. \n" +
                    "Please contact Visage for permanent license to be able to use visage in your product.")
        }

        val dialog: AlertDialog? = builder?.create()
        dialog?.show()
    }

    private fun requestPermission(): Boolean {
        val permissionList = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionList.add(Manifest.permission.CAMERA)
        }
        val permissionStrings = arrayOfNulls<String>(permissionList.size)
        permissionList.toArray(permissionStrings)
        return if (permissionList.size > 0) {
            ActivityCompat.requestPermissions(this, permissionStrings, MY_PERMISSIONS_REQUEST)
            false
        } else {
            AnuLogUtil.d(TAG, "have all the required permissions")
            true
        }
    }

    private fun onResultRegisterLicense(result: String?) {

        try {
            val json = JSONObject(result)
            val deviceID = json.getString("DeviceID")
            val deviceToken = json.getString("Token")
            val pref = getSharedPreferences(ConfigActivity.PREF_NAME, 0)
            val editor = pref.edit()
            editor.putString(ConfigActivity.DEVICE_TOKEN, deviceToken)
            editor.commit()
            MainActivity.deviceToken = deviceToken
            goMeasurementBtn?.isEnabled = true
            goAnuraMeasurementBtn?.isEnabled = true
            RestClient.getInstance().getHash(MainActivity.deviceToken, STUDY_ID, "1")

        } catch (e: JSONException) {
            runOnUiThread {
                Toast.makeText(this, "Register license failed... ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onResultLicenseStatus(result: String?) {
        AnuLogUtil.d(TAG, "License status: $result");
        try {
            val json = JSONObject(result)
            val activeLicense = json.getBoolean("ActiveLicense")
            if (!activeLicense) {
                Toast.makeText(this@MainActivity, "License expired", Toast.LENGTH_LONG).show()
                val results = StringBuilder()
                        .append(Build.MANUFACTURER)
                        .append(" / ")
                        .append(Build.MODEL)
                        .append(" / ")
                        .append(Build.VERSION.RELEASE)
                val device = results.toString()
                RestClient.getInstance().registerLicense(device, BuildConfig.VERSION_NAME, LICENSE_KEY)
            } else {
                goMeasurementBtn?.isEnabled = true
                goAnuraMeasurementBtn?.isEnabled = true
                deviceToken?.let {
                    RestClient.getInstance().getHash(deviceToken, STUDY_ID, "1")
                }
            }
        } catch (e: JSONException) {
        }
    }

    private fun onResultGetStudyHash(result: String?) {
        var hash = ""
        try {
            val data = JSONObject(result)
            hash = data.getString("MD5Hash")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        if (!hash.isEmpty()) {
            val pref = getSharedPreferences(ConfigActivity.PREF_NAME, 0)
            val localHash = pref.getString(ConfigActivity.CONFIG_FILE_HASH, null);
            if (hash != localHash && deviceToken != null) {
                RestClient.getInstance().getStudyFile(deviceToken, STUDY_ID, "1")
            } else {
                AnuLogUtil.d(ConfigActivity.TAG, "Hash hasn't been changed hash=$localHash")
            }
        }
    }

    private fun onResultGetStudyFile(result: String?) {
        var decodedBytes: ByteArray? = null
        var hash = ""
        try {
            val json = JSONObject(result)
            val configFile = json["ConfigFile"] as String
            hash = json.getString("MD5Hash")
            decodedBytes = Base64.decode(configFile, Base64.DEFAULT)
        } catch (e: JSONException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
        decodedBytes?.let { writeDataToFile(it) }
        if (hash.isNotEmpty() && null != decodedBytes) {
            val pref = getSharedPreferences(ConfigActivity.PREF_NAME, MODE_PRIVATE)
            val editor = pref.edit()
            editor.putString(ConfigActivity.CONFIG_FILE_HASH, hash)
            editor.commit()
            AnuLogUtil.d(ConfigActivity.TAG, "Successfully obtained ConfigFile")
        }
    }

    private fun writeDataToFile(data: ByteArray) {
        val fileName = this.filesDir.absolutePath + File.separator + ConfigActivity.CONFIG_FILE_NAME
        try {
            val outputStreamWriter = FileOutputStream(fileName)
            outputStreamWriter.write(data)
            outputStreamWriter.close()

            AnuLogUtil.d(ConfigActivity.TAG, "File has been written to disk")
        } catch (e: Exception) {
            AnuLogUtil.e(ConfigActivity.TAG, e)
            File(fileName).delete()
        }
    }
}
