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
import ai.nuralogix.anurasdk.network.DeepAffexDataSpec
import ai.nuralogix.anurasdk.network.DeepFXClient
import ai.nuralogix.anurasdk.network.RestClient
import ai.nuralogix.anurasdk.utils.AnuLogUtil
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ConfigActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ANURA_ConfigActivity"

        const val REST_SERVER_KEY = "rest_server_key"
        const val WS_SERVER_KEY = "ws_server_key"
        const val PARTNER_ID_KEY = "partner_id_key"
        const val LICENSE_KEY = "license_key"
        const val STUDY_ID_KEY = "study_id_key"
        const val PREF_NAME = "nura_sample_config"
        const val DEVICE_TOKEN = "device_token"
        const val CONFIG_FILE_HASH = "config_file_hash"
        const val CONFIG_FILE_NAME = "config_file.dat"
    }

    private lateinit var restServerEt: EditText
    private lateinit var wsServerEt: EditText
    private lateinit var partnerIDEt: EditText
    private lateinit var licenseEt: EditText
    private lateinit var studyIdEt: EditText
    private lateinit var connectBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        restServerEt = findViewById(R.id.rest_server_et)
        wsServerEt = findViewById(R.id.ws_server_et)
        partnerIDEt = findViewById(R.id.partner_id_et)
        licenseEt = findViewById(R.id.license_et)
        studyIdEt = findViewById(R.id.study_et)
        connectBtn = findViewById(R.id.connect_btn)


        restoreConfig()

        RestClient.getInstance().setListener(object : RestClient.Listener {
            override fun onResult(token: Int, result: String?) {
                when (token) {
                    RestClient.ACTION_REGISTER_LICENSE -> onResultRegisterLicense(result)
                    RestClient.ACTION_GET_STUDY_FILE -> onResultGetStudyFile(result)
                    RestClient.ACTION_GET_STUDY_HASH -> onResultGetStudyHash(result)
                }
            }

            override fun onError(message: String?, token: Int) {
                runOnUiThread {
                    Toast.makeText(this@ConfigActivity, "Rest API error: $message", Toast.LENGTH_LONG).show()
                }
            }

        })

        AnuLogUtil.d(TAG, "Activity onCreate, savedInstanceState is: " + savedInstanceState)
    }

    override fun onPause() {
        updateConfig()

        AnuLogUtil.d(TAG, "Configure the following for measurement: \n" +
                " REST server: ${MainActivity.SAMPLE_REST_URL} \n" +
                " WS server: ${MainActivity.SAMPLE_WS_URL} \n" +
                " Parnter ID: ${MainActivity.PARTNER_ID} \n")
        super.onPause()
    }

    override fun onDestroy() {
        AnuLogUtil.d(TAG, "Activity onDestroy.")
        saveConfig()
        super.onDestroy()
    }

    fun onConnectClick(v: View) {
        v.isEnabled = false
        updateConfig()

        DeepAffexDataSpec.REST_SERVER = MainActivity.SAMPLE_REST_URL
        DeepAffexDataSpec.WS_SERVER = MainActivity.SAMPLE_WS_URL

        val results = StringBuilder()
                .append(Build.MANUFACTURER)
                .append(" / ")
                .append(Build.MODEL)
                .append(" / ")
                .append(Build.VERSION.RELEASE)
        val device = results.toString()

        RestClient.getInstance().registerLicense(device, BuildConfig.VERSION_NAME, MainActivity.LICENSE_KEY)
    }

    private fun updateConfig() {
        MainActivity.SAMPLE_REST_URL = restServerEt.text.toString()
        MainActivity.SAMPLE_WS_URL = wsServerEt.text.toString()
        MainActivity.PARTNER_ID = partnerIDEt.text.toString()
        MainActivity.LICENSE_KEY = licenseEt.text.toString()
        MainActivity.STUDY_ID = studyIdEt.text.toString()
    }


    private fun saveConfig() {
        val pref = getSharedPreferences(PREF_NAME, 0)
        val editor = pref.edit()
        editor.putString(REST_SERVER_KEY, MainActivity.SAMPLE_REST_URL)
        editor.putString(WS_SERVER_KEY, MainActivity.SAMPLE_WS_URL)
        editor.putString(PARTNER_ID_KEY, MainActivity.PARTNER_ID)
        editor.putString(LICENSE_KEY, MainActivity.LICENSE_KEY)
        editor.putString(STUDY_ID_KEY, MainActivity.STUDY_ID)
        editor.commit()
    }

    private fun restoreConfig() {
        val pref = getSharedPreferences(PREF_NAME, 0)
        val restUrl = pref.getString(REST_SERVER_KEY, MainActivity.SAMPLE_REST_URL)
        restServerEt.setText(restUrl)
        MainActivity.SAMPLE_REST_URL = restUrl!!
        val wsUrl = pref.getString(WS_SERVER_KEY, MainActivity.SAMPLE_WS_URL)
        wsServerEt.setText(wsUrl)
        MainActivity.SAMPLE_WS_URL = wsUrl!!
        val partnerID = pref.getString(PARTNER_ID_KEY, MainActivity.PARTNER_ID)
        partnerIDEt.setText(partnerID)
        MainActivity.PARTNER_ID = partnerID!!
        val license = pref.getString(LICENSE_KEY, MainActivity.LICENSE_KEY)
        licenseEt.setText(license)
        MainActivity.LICENSE_KEY = license!!
        val studyId = pref.getString(STUDY_ID_KEY, MainActivity.STUDY_ID)
        studyIdEt.setText(studyId)
        MainActivity.STUDY_ID = studyId!!
    }

    private fun onResultRegisterLicense(result: String?) {

        try {
            val json = JSONObject(result)
            val deviceID = json.getString("DeviceID")
            val deviceToken = json.getString("Token")
            val pref = getSharedPreferences(PREF_NAME, 0)
            val editor = pref.edit()
            editor.putString(DEVICE_TOKEN, deviceToken)
            editor.commit()
            MainActivity.deviceToken = deviceToken
            DeepFXClient.getInstance().setTokenAuthorisation(deviceToken)
            runOnUiThread {
                Toast.makeText(this, "Register license success\nStart getting ConfigFile", Toast.LENGTH_LONG).show()
            }
            RestClient.getInstance().getHash(MainActivity.deviceToken, MainActivity.STUDY_ID, "1")
        } catch (e: JSONException) {
            runOnUiThread {
                Toast.makeText(this, "Register license failed... ${e.message}", Toast.LENGTH_LONG).show()
            }
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
            val pref = getSharedPreferences(PREF_NAME, 0)
            val localHash = pref.getString(CONFIG_FILE_HASH, null);
            if (!hash.equals(localHash)) {
                RestClient.getInstance().getStudyFile(MainActivity.deviceToken, MainActivity.STUDY_ID, "1")
            } else {
                runOnUiThread { connectBtn.isEnabled = true }
                AnuLogUtil.d(TAG, "Hash hasn't been changed hash=$localHash")
            }
        } else {
            runOnUiThread { connectBtn.isEnabled = true }
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
        if (!hash.isEmpty() && null != decodedBytes) {
            val pref = getSharedPreferences(PREF_NAME, 0)
            val editor = pref.edit()
            editor.putString(CONFIG_FILE_HASH, hash)
            editor.commit()
            AnuLogUtil.d(TAG, "Successfully obtained ConfigFile")
        }
        runOnUiThread { connectBtn.isEnabled = true }
    }

    private fun writeDataToFile(data: ByteArray) {
        val fileName = this.filesDir.absolutePath + File.separator + CONFIG_FILE_NAME
        try {
            val outputStreamWriter = FileOutputStream(fileName)
            outputStreamWriter.write(data)
            outputStreamWriter.close()

            AnuLogUtil.d(TAG, "File has been written to disk")
        } catch (e: Exception) {
            AnuLogUtil.e(TAG, e)
            File(fileName).delete()
        }
    }
}
