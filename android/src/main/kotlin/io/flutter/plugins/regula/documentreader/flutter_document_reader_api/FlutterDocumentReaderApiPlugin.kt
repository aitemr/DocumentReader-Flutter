//
//  FlutterDocumentReaderApiPlugin.java
//  DocumentReader
//
//  Created by Pavel Masiuk on 21.09.2023.
//  Copyright © 2023 Regula. All rights reserved.
//
@file:Suppress("UNCHECKED_CAST")

package io.flutter.plugins.regula.documentreader.flutter_document_reader_api

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.regula.common.LocalizationCallbacks
import com.regula.documentreader.api.DocumentReader.Instance
import com.regula.documentreader.api.completions.IDocumentReaderCompletion
import com.regula.documentreader.api.completions.IDocumentReaderInitCompletion
import com.regula.documentreader.api.completions.IDocumentReaderPrepareCompletion
import com.regula.documentreader.api.completions.rfid.IRfidPKDCertificateCompletion
import com.regula.documentreader.api.completions.rfid.IRfidReaderCompletion
import com.regula.documentreader.api.completions.rfid.IRfidReaderRequest
import com.regula.documentreader.api.completions.rfid.IRfidTASignatureCompletion
import com.regula.documentreader.api.completions.rfid.certificates.IRfidPACertificates
import com.regula.documentreader.api.completions.rfid.certificates.IRfidTACertificates
import com.regula.documentreader.api.completions.rfid.certificates.IRfidTASignature
import com.regula.documentreader.api.enums.DocReaderAction
import com.regula.documentreader.api.enums.LCID
import com.regula.documentreader.api.enums.eImageQualityCheckType
import com.regula.documentreader.api.enums.eLDS_ParsingErrorCodes
import com.regula.documentreader.api.enums.eLDS_ParsingNotificationCodes
import com.regula.documentreader.api.enums.eRFID_DataFile_Type
import com.regula.documentreader.api.enums.eRFID_ErrorCodes
import com.regula.documentreader.api.enums.eVisualFieldType
import com.regula.documentreader.api.errors.DocReaderRfidException
import com.regula.documentreader.api.errors.DocumentReaderException
import com.regula.documentreader.api.internal.core.CoreScenarioUtil
import com.regula.documentreader.api.results.DocumentReaderNotification
import com.regula.documentreader.api.results.DocumentReaderResults
import com.regula.documentreader.api.results.DocumentReaderResults.fromRawResults
import com.regula.documentreader.api.results.DocumentReaderScenario
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugins.regula.documentreader.flutter_document_reader_api.Convert.bitmapToBase64
import io.flutter.plugins.regula.documentreader.flutter_document_reader_api.Convert.byteArrayFromBase64
import org.json.JSONArray
import org.json.JSONObject

class FlutterDocumentReaderApiPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    override fun onAttachedToActivity(binding: ActivityPluginBinding) = attachedToActivity(binding)
    override fun onDetachedFromActivityForConfigChanges() = Unit
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = Unit
    override fun onDetachedFromActivity() = Unit
    override fun onAttachedToEngine(binding: FlutterPluginBinding) = attachedToEngine(binding, this)
    override fun onDetachedFromEngine(binding: FlutterPluginBinding) = Unit
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) = methodCall(call, result)
}

fun attachedToEngine(binding: FlutterPluginBinding, plugin: FlutterDocumentReaderApiPlugin) {
    binaryMessenger = binding.binaryMessenger
    setupEventChannel("completion")
    setupEventChannel("database_progress")
    setupEventChannel("rfidOnProgressCompletion")
    setupEventChannel("rfidOnChipDetectedEvent")
    setupEventChannel("rfidOnRetryReadChipEvent")
    setupEventChannel("pa_certificate_completion")
    setupEventChannel("ta_certificate_completion")
    setupEventChannel("ta_signature_completion")
    setupEventChannel("bleOnServiceConnectedEvent")
    setupEventChannel("bleOnServiceDisconnectedEvent")
    setupEventChannel("bleOnDeviceReadyEvent")
    setupEventChannel("video_encoder_completion")
    setupEventChannel("onCustomButtonTappedEvent")
    MethodChannel(binaryMessenger, "flutter_document_reader_api/method").setMethodCallHandler(plugin)
}

fun attachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    activityBinding = binding
    binding.addOnNewIntentListener {
        newIntent(it)
        false
    }
}

fun setupEventChannel(id: String) = EventChannel(binaryMessenger, "flutter_document_reader_api/event/$id").setStreamHandler(object : EventChannel.StreamHandler {
    override fun onListen(arguments: Any?, events: EventSink) = events.let { eventSinks[id] = it }
    override fun onCancel(arguments: Any?) = Unit
})

fun sendEvent(id: String, data: Any? = "") {
    eventSinks[id]?.let { Handler(Looper.getMainLooper()).post { it.success(data.toSendable()) } }
}

fun <T> argsNullable(index: Int) = when {
    args[index] == null -> null
    args[index]!!.javaClass == HashMap::class.java -> hashMapToJSONObject(args[index] as HashMap<String, *>) as T
    args[index]!!.javaClass == ArrayList::class.java -> arrayListToJSONArray(args[index] as ArrayList<*>) as T
    else -> args[index] as T
}

lateinit var args: ArrayList<Any?>
val eventSinks = mutableMapOf<String, EventSink?>()
lateinit var binaryMessenger: BinaryMessenger
lateinit var activityBinding: ActivityPluginBinding
val lifecycle: Lifecycle
    get() = (activityBinding.lifecycle as HiddenLifecycleReference).lifecycle

fun methodCall(call: MethodCall, result: MethodChannel.Result) {
    val action = call.method
    args = call.arguments as ArrayList<Any?>
    val callback = object : Callback {
        override fun success(data: Any?) = result.success(data.toSendable())
        override fun error(message: String) = result.error("", message, null)
    }
    when (action) {
        "getDocumentReaderIsReady" -> getDocumentReaderIsReady(callback)
        "getDocumentReaderStatus" -> getDocumentReaderStatus(callback)
        "isAuthenticatorAvailableForUse" -> isAuthenticatorAvailableForUse(callback)
        "isBlePermissionsGranted" -> isBlePermissionsGranted(callback)
        "getRfidSessionStatus" -> getRfidSessionStatus(callback)
        "setRfidSessionStatus" -> setRfidSessionStatus(callback)
        "getTag" -> getTag(callback)
        "setTag" -> setTag(argsNullable(0))
        "getFunctionality" -> getFunctionality(callback)
        "setFunctionality" -> setFunctionality(args(0))
        "getProcessParams" -> getProcessParams(callback)
        "setProcessParams" -> setProcessParams(args(0))
        "getCustomization" -> getCustomization(callback)
        "setCustomization" -> setCustomization(args(0))
        "getRfidScenario" -> getRfidScenario(callback)
        "setRfidScenario" -> setRfidScenario(args(0))
        "initializeReader" -> initializeReader(callback, args(0))
        "initializeReaderWithBleDeviceConfig" -> initializeReaderWithBleDeviceConfig(callback, args(0))
        "deinitializeReader" -> deinitializeReader(callback)
        "prepareDatabase" -> prepareDatabase(callback, args(0))
        "removeDatabase" -> removeDatabase(callback)
        "runAutoUpdate" -> runAutoUpdate(callback, args(0))
        "cancelDBUpdate" -> cancelDBUpdate(callback)
        "checkDatabaseUpdate" -> checkDatabaseUpdate(callback, args(0))
        "scan" -> scan(args(0))
        "recognize" -> recognize(args(0))
        "startNewPage" -> startNewPage(callback)
        "stopScanner" -> stopScanner(callback)
        "startRFIDReader" -> startRFIDReader(args(0), args(1), args(2))
        "stopRFIDReader" -> stopRFIDReader(callback)
        "readRFID" -> readRFID(args(0), args(1), args(2))
        "providePACertificates" -> providePACertificates(callback, argsNullable(0))
        "provideTACertificates" -> provideTACertificates(callback, argsNullable(0))
        "provideTASignature" -> provideTASignature(callback, args(0))
        "setTCCParams" -> setTCCParams(callback, args(0))
        "addPKDCertificates" -> addPKDCertificates(callback, args(0))
        "clearPKDCertificates" -> clearPKDCertificates(callback)
        "startNewSession" -> startNewSession(callback)
        "startBluetoothService" -> startBluetoothService()
        "setLocalizationDictionary" -> setLocalizationDictionary(args(0))
        "getLicense" -> getLicense(callback)
        "getAvailableScenarios" -> getAvailableScenarios(callback)
        "getIsRFIDAvailableForUse" -> getIsRFIDAvailableForUse(callback)
        "getDocReaderVersion" -> getDocReaderVersion(callback)
        "getDocReaderDocumentsDatabase" -> getDocReaderDocumentsDatabase(callback)
        "textFieldValueByType" -> textFieldValueByType(callback, args(0), args(1))
        "textFieldValueByTypeLcid" -> textFieldValueByTypeLcid(callback, args(0), args(1), args(2))
        "textFieldValueByTypeSource" -> textFieldValueByTypeSource(callback, args(0), args(1), args(2))
        "textFieldValueByTypeLcidSource" -> textFieldValueByTypeLcidSource(callback, args(0), args(1), args(2), args(3))
        "textFieldValueByTypeSourceOriginal" -> textFieldValueByTypeSourceOriginal(callback, args(0), args(1), args(2), args(3))
        "textFieldValueByTypeLcidSourceOriginal" -> textFieldValueByTypeLcidSourceOriginal(callback, args(0), args(1), args(2), args(3), args(4))
        "textFieldByType" -> textFieldByType(callback, args(0), args(1))
        "textFieldByTypeLcid" -> textFieldByTypeLcid(callback, args(0), args(1), args(2))
        "graphicFieldByTypeSource" -> graphicFieldByTypeSource(callback, args(0), args(1), args(2))
        "graphicFieldByTypeSourcePageIndex" -> graphicFieldByTypeSourcePageIndex(callback, args(0), args(1), args(2), args(3))
        "graphicFieldByTypeSourcePageIndexLight" -> graphicFieldByTypeSourcePageIndexLight(callback, args(0), args(1), args(2), args(3), args(4))
        "graphicFieldImageByType" -> graphicFieldImageByType(callback, args(0), args(1))
        "graphicFieldImageByTypeSource" -> graphicFieldImageByTypeSource(callback, args(0), args(1), args(2))
        "graphicFieldImageByTypeSourcePageIndex" -> graphicFieldImageByTypeSourcePageIndex(callback, args(0), args(1), args(2), args(3))
        "graphicFieldImageByTypeSourcePageIndexLight" -> graphicFieldImageByTypeSourcePageIndexLight(callback, args(0), args(1), args(2), args(3), args(4))
        "containers" -> containers(callback, args(0), args(1))
        "encryptedContainers" -> encryptedContainers(callback, args(0))
        "getTranslation" -> getTranslation(callback, args(0), args(1))
        "finalizePackage" -> finalizePackage(callback)
    }
}

fun <T> args(index: Int): T = argsNullable(index)!!
interface Callback {
    fun success(data: Any? = "")
    fun error(message: String)
}

@SuppressLint("StaticFieldLeak")
lateinit var activity: Activity
lateinit var lifecycleObserver: LifecycleEventObserver
val context
    get() = activity

var backgroundRFIDEnabled = false
var databaseDownloadProgress = 0

const val eventCompletion = "completion"
const val eventDatabaseProgress = "database_progress"

const val rfidOnProgressEvent = "rfidOnProgressCompletion"
const val rfidOnChipDetectedEvent = "rfidOnChipDetectedEvent"
const val rfidOnRetryReadChipEvent = "rfidOnRetryReadChipEvent"

const val eventPACertificateCompletion = "pa_certificate_completion"
const val eventTACertificateCompletion = "ta_certificate_completion"
const val eventTASignatureCompletion = "ta_signature_completion"

const val bleOnServiceConnectedEvent = "bleOnServiceConnectedEvent"
const val bleOnServiceDisconnectedEvent = "bleOnServiceDisconnectedEvent"
const val bleOnDeviceReadyEvent = "bleOnDeviceReadyEvent"

const val eventVideoEncoderCompletion = "video_encoder_completion"
const val onCustomButtonTappedEvent = "onCustomButtonTappedEvent"

fun getDocumentReaderIsReady(callback: Callback) = callback.success(Instance().isReady)

fun getDocumentReaderStatus(callback: Callback) = callback.success(Instance().status)

fun isAuthenticatorAvailableForUse(callback: Callback) = callback.success(Instance().isAuthenticatorAvailableForUse)

fun isBlePermissionsGranted(callback: Callback) = callback.success(isBlePermissionsGranted((activity)))

fun getRfidSessionStatus(callback: Callback) = callback.error("getRfidSessionStatus() is an ios-only method")

fun setRfidSessionStatus(callback: Callback) = callback.error("setRfidSessionStatus() is an ios-only method")

fun getTag(callback: Callback) = callback.success(Instance().tag)

fun setTag(tag: String?) = tag.let { Instance().tag = it }

fun getFunctionality(callback: Callback) = callback.success(getFunctionality(Instance().functionality()))

fun setFunctionality(functionality: JSONObject) = setFunctionality(Instance().functionality(), functionality)

fun getProcessParams(callback: Callback) = callback.success(getProcessParams(Instance().processParams()))

fun setProcessParams(processParams: JSONObject) = setProcessParams(Instance().processParams(), processParams)

fun getCustomization(callback: Callback) = callback.success(getCustomization(Instance().customization()))

fun setCustomization(customization: JSONObject) = setCustomization(Instance().customization(), customization, context)

fun getRfidScenario(callback: Callback) = callback.success(getRfidScenario(Instance().rfidScenario()))

fun setRfidScenario(rfidScenario: JSONObject) = setRfidScenario(Instance().rfidScenario(), rfidScenario)

fun initializeReader(callback: Callback, config: JSONObject) = Instance().initializeReader(context, docReaderConfigFromJSON(config), getInitCompletion(callback))

fun initializeReaderWithBleDeviceConfig(callback: Callback, config: JSONObject) = Instance().initializeReader(context, bleDeviceConfigFromJSON(config), getInitCompletion(callback))

fun deinitializeReader(callback: Callback) {
    Instance().deinitializeReader()
    callback.success()
}

fun prepareDatabase(callback: Callback, databaseID: String) = Instance().prepareDatabase(context, databaseID, getPrepareCompletion(callback))

fun removeDatabase(callback: Callback) = callback.success(Instance().removeDatabase(context))

fun runAutoUpdate(callback: Callback, databaseID: String) = Instance().runAutoUpdate(context, databaseID, getPrepareCompletion(callback))

fun cancelDBUpdate(callback: Callback) = callback.success(Instance().cancelDBUpdate(context))

fun checkDatabaseUpdate(callback: Callback, databaseID: String) = Instance().checkDatabaseUpdate(context, databaseID) { callback.success(generateDocReaderDocumentsDatabase(it)) }

fun scan(config: JSONObject) {
    stopBackgroundRFID()
    Instance().showScanner(context, scannerConfigFromJSON(config), completion)
}

fun recognize(config: JSONObject) {
    stopBackgroundRFID()
    Instance().recognize(recognizeConfigFromJSON(config), completion)
}

fun startNewPage(callback: Callback) {
    Instance().startNewPage()
    callback.success()
}

fun stopScanner(callback: Callback) {
    Instance().stopScanner(context)
    callback.success()
}

fun startRFIDReader(onRequestPACertificates: Boolean, onRequestTACertificates: Boolean, onRequestTASignature: Boolean) {
    stopBackgroundRFID()
    requestType = RfidReaderRequestType(
        onRequestPACertificates,
        onRequestTACertificates,
        onRequestTASignature
    )
    Instance().startRFIDReader(context, rfidReaderCompletion, requestType.getRfidReaderRequest())
}

fun readRFID(onRequestPACertificates: Boolean, onRequestTACertificates: Boolean, onRequestTASignature: Boolean) {
    requestType = RfidReaderRequestType(
        onRequestPACertificates,
        onRequestTACertificates,
        onRequestTASignature
    )
    startForegroundDispatch()
}

fun stopRFIDReader(callback: Callback) {
    Instance().stopRFIDReader(context)
    stopBackgroundRFID()
    callback.success()
}

fun providePACertificates(callback: Callback, certificates: JSONArray?) {
    paCertificateCompletion!!.onCertificatesReceived(arrayFromJSON(certificates, ::pkdCertificateFromJSON, arrayOfNulls(certificates?.length() ?: 0)))
    callback.success()
}

fun provideTACertificates(callback: Callback, certificates: JSONArray?) {
    taCertificateCompletion!!.onCertificatesReceived(arrayFromJSON(certificates, ::pkdCertificateFromJSON, arrayOfNulls(certificates?.length() ?: 0)))
    callback.success()
}

fun provideTASignature(callback: Callback, signature: String?) {
    taSignatureCompletion!!.onSignatureReceived(byteArrayFromBase64(signature))
    callback.success()
}

fun setTCCParams(callback: Callback, params: JSONObject) {
    Instance().setTccParams(tccParamsFromJSON(params)) { success, error ->
        callback.success(generateSuccessCompletion(success, error))
    }
}

fun addPKDCertificates(callback: Callback, certificates: JSONArray) {
    Instance().addPKDCertificates(listFromJSON(certificates, ::pkdCertificateFromJSON)!!)
    callback.success()
}

fun clearPKDCertificates(callback: Callback) {
    Instance().clearPKDCertificates()
    callback.success()
}

fun startNewSession(callback: Callback) {
    Instance().startNewSession()
    callback.success()
}

fun startBluetoothService() = startBluetoothService(
    activity,
    { sendEvent(bleOnServiceConnectedEvent, it) },
    { sendEvent(bleOnServiceDisconnectedEvent) },
    { sendEvent(bleOnDeviceReadyEvent) }
)

@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
fun setLocalizationDictionary(dictionary: JSONObject) {
    localizationCallbacks = LocalizationCallbacks { dictionary.optString(it, null) }
    Instance().setLocalizationCallback(localizationCallbacks!!)
}

fun getLicense(callback: Callback) = callback.success(generateLicense(Instance().license()))

fun getAvailableScenarios(callback: Callback) {
    val scenarios: MutableList<DocumentReaderScenario> = ArrayList()
    for (scenario: DocumentReaderScenario in Instance().availableScenarios)
        scenarios.add(CoreScenarioUtil.getScenario(scenario.name))
    callback.success(generateList(scenarios, ::generateDocumentReaderScenario))
}

fun getIsRFIDAvailableForUse(callback: Callback) = callback.success(Instance().isRFIDAvailableForUse)

fun getDocReaderVersion(callback: Callback) = callback.success(generateDocReaderVersion(Instance().version))

fun getDocReaderDocumentsDatabase(callback: Callback) = callback.success(Instance().version?.let { generateDocReaderDocumentsDatabase(it.database) })

fun textFieldValueByType(callback: Callback, raw: String, fieldType: Int) = callback.success(fromRawResults(raw).getTextFieldValueByType(fieldType))

fun textFieldValueByTypeLcid(callback: Callback, raw: String, fieldType: Int, lcid: Int) = callback.success(fromRawResults(raw).getTextFieldValueByType(fieldType, lcid))

fun textFieldValueByTypeSource(callback: Callback, raw: String, fieldType: Int, source: Int) = callback.success(fromRawResults(raw).getTextFieldValueByTypeAndSource(fieldType, source))

fun textFieldValueByTypeLcidSource(callback: Callback, raw: String, fieldType: Int, lcid: Int, source: Int) = callback.success(fromRawResults(raw).getTextFieldValueByType(fieldType, lcid, source))

fun textFieldValueByTypeSourceOriginal(callback: Callback, raw: String, fieldType: Int, source: Int, original: Boolean) = callback.success(fromRawResults(raw).getTextFieldValueByTypeAndSource(fieldType, source, original))

fun textFieldValueByTypeLcidSourceOriginal(callback: Callback, raw: String, fieldType: Int, lcid: Int, source: Int, original: Boolean) = callback.success(fromRawResults(raw).getTextFieldValueByType(fieldType, lcid, source, original))

fun textFieldByType(callback: Callback, raw: String, fieldType: Int) = callback.success(generateDocumentReaderTextField(fromRawResults(raw).getTextFieldByType(fieldType), context))

fun textFieldByTypeLcid(callback: Callback, raw: String, fieldType: Int, lcid: Int) = callback.success(generateDocumentReaderTextField(fromRawResults(raw).getTextFieldByType(fieldType, lcid), context))

fun graphicFieldByTypeSource(callback: Callback, raw: String, fieldType: Int, source: Int) = callback.success(generateDocumentReaderGraphicField(fromRawResults(raw).getGraphicFieldByType(fieldType, source), context))

fun graphicFieldByTypeSourcePageIndex(callback: Callback, raw: String, fieldType: Int, source: Int, pageIndex: Int) = callback.success(generateDocumentReaderGraphicField(fromRawResults(raw).getGraphicFieldByType(fieldType, source, pageIndex), context))

fun graphicFieldByTypeSourcePageIndexLight(callback: Callback, raw: String, fieldType: Int, source: Int, pageIndex: Int, light: Int) = callback.success(generateDocumentReaderGraphicField(fromRawResults(raw).getGraphicFieldByType(fieldType, source, pageIndex, light), context))

fun graphicFieldImageByType(callback: Callback, raw: String, fieldType: Int) = callback.success(bitmapToBase64(fromRawResults(raw).getGraphicFieldImageByType(fieldType)))

fun graphicFieldImageByTypeSource(callback: Callback, raw: String, fieldType: Int, source: Int) = callback.success(bitmapToBase64(fromRawResults(raw).getGraphicFieldImageByType(fieldType, source)))

fun graphicFieldImageByTypeSourcePageIndex(callback: Callback, raw: String, fieldType: Int, source: Int, pageIndex: Int) = callback.success(bitmapToBase64(fromRawResults(raw).getGraphicFieldImageByType(fieldType, source, pageIndex)))

fun graphicFieldImageByTypeSourcePageIndexLight(callback: Callback, raw: String, fieldType: Int, source: Int, pageIndex: Int, light: Int) = callback.success(bitmapToBase64(fromRawResults(raw).getGraphicFieldImageByType(fieldType, source, pageIndex, light)))

fun containers(callback: Callback, raw: String, resultType: JSONArray) = callback.success(fromRawResults(raw).getContainers(resultType.toIntArray()!!))

fun encryptedContainers(callback: Callback, raw: String) = callback.success(fromRawResults(raw).encryptedContainers)

fun finalizePackage(callback: Callback) = Instance().finalizePackage { action, info, error -> callback.success(generateFinalizePackageCompletion(action, info, error)) }

fun getTranslation(callback: Callback, className: String, value: Int) = when (className) {
    "RFIDErrorCodes" -> callback.success(eRFID_ErrorCodes.getTranslation(context, value))
    "LDSParsingErrorCodes" -> callback.success(eLDS_ParsingErrorCodes.getTranslation(context, value))
    "LDSParsingNotificationCodes" -> callback.success(eLDS_ParsingNotificationCodes.getTranslation(context, value))
    "ImageQualityCheckType" -> callback.success(eImageQualityCheckType.getTranslation(context, value))
    "RFIDDataFileType" -> callback.success(eRFID_DataFile_Type.getTranslation(context, value))
    "VisualFieldType" -> callback.success(eVisualFieldType.getTranslation(context, value))
    "LCID" -> callback.success(LCID.getTranslation(context, value))
    else -> null
}

val completed = { action: Int, results: DocumentReaderResults?, error: DocumentReaderException? ->
    sendEvent(eventCompletion, generateCompletion(action, results, error, context))
    if ((action == DocReaderAction.ERROR) || (action == DocReaderAction.CANCEL) || ((action == DocReaderAction.COMPLETE) && (results?.rfidResult == 1)))
        stopBackgroundRFID()
}

val completion = IDocumentReaderCompletion(completed)

val rfidReaderCompletion = object : IRfidReaderCompletion() {
    override fun onCompleted(action: Int, results: DocumentReaderResults?, error: DocumentReaderException?): Unit = completed(action, results, error)
    override fun onChipDetected(): Unit = sendEvent(rfidOnChipDetectedEvent)
    override fun onRetryReadChip(error: DocReaderRfidException) = sendEvent(rfidOnRetryReadChipEvent, generateRegulaException(error))
    override fun onProgress(notification: DocumentReaderNotification) = sendEvent(rfidOnProgressEvent, generateDocumentReaderNotification(notification))
}

fun getPrepareCompletion(callback: Callback) = object : IDocumentReaderPrepareCompletion {
    override fun onPrepareProgressChanged(progress: Int) {
        if (progress != databaseDownloadProgress) {
            sendEvent(eventDatabaseProgress, progress)
            databaseDownloadProgress = progress
        }
    }

    override fun onPrepareCompleted(s: Boolean, e: DocumentReaderException?) = callback.success(generateSuccessCompletion(s, e))
}

fun getInitCompletion(callback: Callback) = IDocumentReaderInitCompletion { success, error ->
    if (success) {
        Instance().setVideoEncoderCompletion { _, file -> sendEvent(eventVideoEncoderCompletion, file.path) }
        Instance().setOnClickListener { sendEvent(onCustomButtonTappedEvent, it.tag) }
    }
    callback.success(generateSuccessCompletion(success, error))
}

var paCertificateCompletion: IRfidPKDCertificateCompletion? = null
var taCertificateCompletion: IRfidPKDCertificateCompletion? = null
var taSignatureCompletion: IRfidTASignatureCompletion? = null

class RfidReaderRequestType(
    val doPACertificates: Boolean,
    val doTACertificates: Boolean,
    val doTASignature: Boolean
) {
    private val onRequestPACertificates = IRfidPACertificates { serialNumber, issuer, completion ->
        paCertificateCompletion = completion
        sendEvent(eventPACertificateCompletion, generatePACertificateCompletion(serialNumber, issuer))
    }
    private val onRequestTACertificates = IRfidTACertificates { keyCAR, completion ->
        taCertificateCompletion = completion
        sendEvent(eventTACertificateCompletion, keyCAR)
    }
    private val onRequestTASignature = IRfidTASignature { challenge, completion ->
        taSignatureCompletion = completion
        sendEvent(eventTASignatureCompletion, generateTAChallenge(challenge))
    }

    fun getRfidReaderRequest(): IRfidReaderRequest? = when {
        !doPACertificates && !doTACertificates && doTASignature -> IRfidReaderRequest(onRequestTASignature)
        !doPACertificates && doTACertificates && !doTASignature -> IRfidReaderRequest(onRequestTACertificates)
        !doPACertificates && doTACertificates && doTASignature -> IRfidReaderRequest(onRequestTACertificates, onRequestTASignature)
        doPACertificates && !doTACertificates && !doTASignature -> IRfidReaderRequest(onRequestPACertificates)
        doPACertificates && !doTACertificates && doTASignature -> IRfidReaderRequest(onRequestPACertificates, onRequestTASignature)
        doPACertificates && doTACertificates && !doTASignature -> IRfidReaderRequest(onRequestPACertificates, onRequestTACertificates)
        doPACertificates && doTACertificates && doTASignature -> IRfidReaderRequest(onRequestPACertificates, onRequestTACertificates, onRequestTASignature)
        else -> null
    }
}

var requestType = RfidReaderRequestType(
    doPACertificates = false,
    doTACertificates = false,
    doTASignature = false
)

@Suppress("DEPRECATION")
fun newIntent(intent: Intent) = if (intent.action == NfcAdapter.ACTION_TECH_DISCOVERED)
    Instance().readRFID(
        IsoDep.get(intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)),
        rfidReaderCompletion,
        requestType.getRfidReaderRequest()
    ) else Unit

fun startForegroundDispatch() {
    backgroundRFIDEnabled = true
    val filters: Array<IntentFilter?> = arrayOfNulls(1)
    filters[0] = IntentFilter()
    filters[0]!!.addAction(NfcAdapter.ACTION_TECH_DISCOVERED)
    filters[0]!!.addCategory(Intent.CATEGORY_DEFAULT)
    val techList = arrayOf(arrayOf("android.nfc.tech.IsoDep"))
    val intent = Intent(context, context.javaClass)
    val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, flag)

    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        enableForegroundDispatch(pendingIntent, filters, techList)
    lifecycleObserver = LifecycleEventObserver { _, event ->
        if (backgroundRFIDEnabled) when (event) {
            Lifecycle.Event.ON_RESUME -> enableForegroundDispatch(pendingIntent, filters, techList)
            Lifecycle.Event.ON_PAUSE -> disableForegroundDispatch()
            else -> Unit
        }
    }
    context.runOnUiThread { lifecycle.addObserver(lifecycleObserver) }
}

fun enableForegroundDispatch(
    pendingIntent: PendingIntent,
    filters: Array<IntentFilter?>,
    techList: Array<Array<String>>
) = NfcAdapter.getDefaultAdapter(context).enableForegroundDispatch(activity, pendingIntent, filters, techList)

fun disableForegroundDispatch() = NfcAdapter.getDefaultAdapter(activity).disableForegroundDispatch(activity)

fun stopBackgroundRFID() {
    if (!backgroundRFIDEnabled) return
    backgroundRFIDEnabled = false
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        disableForegroundDispatch()
    context.runOnUiThread { lifecycle.removeObserver(lifecycleObserver) }
}

// Weak references
var localizationCallbacks: LocalizationCallbacks? = null