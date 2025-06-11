package com.apt.qrcode

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import androidx.core.net.toUri
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var qrPreviewImage: ImageView
    private val requestCodePermissions = 1001
    private var isScanned = false 

    private lateinit var loadingDialog: AlertDialog

    private lateinit var loadingOverlay: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        qrPreviewImage = findViewById(R.id.qrPreviewImage)
        loadingOverlay = findViewById(R.id.loadingOverlay)  
        val btnScan = findViewById<FrameLayout>(R.id.btnScan)
        btnScan.setOnClickListener {
            if (allPermissionsGranted()) {
                qrPreviewImage.visibility = View.GONE
                previewView.visibility = View.VISIBLE
                startCamera()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    requestCodePermissions
                )
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        processImageProxy(barcodeScanner, imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendUrlToServer(url: String) {
        loadingOverlay.visibility = View.VISIBLE  

        val thread = Thread {
            try {
                val json = """{"url":"$url"}"""
                val connection = java.net.URL("http://0.0.0.0:5000/receive_url") // 내부 IP 주소로 바꿔야함
                    .openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                connection.outputStream.use { os ->
                    os.write(json.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                runOnUiThread {
                    loadingOverlay.visibility = View.GONE  

                    try {
                        val jsonResult = org.json.JSONObject(response)
                        val prediction = jsonResult.getString("result")

                        if (prediction.contains("정상", ignoreCase = true)) {
                            openWebPage(url)
                        } else {
                            showPhishingWarning(url)
                        }

                    } catch (e: Exception) {
                        Toast.makeText(this, "응답 파싱 오류", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE  
                    Toast.makeText(this, "서버 연결 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
        thread.start()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(scanner: BarcodeScanner, imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (!isScanned && barcode.valueType == Barcode.TYPE_URL) {
                        isScanned = true
                        val url = barcode.rawValue
                        Toast.makeText(this, "스캔됨: $url", Toast.LENGTH_SHORT).show()
                        sendUrlToServer(url ?: "")
                        //openWebPage(url)

                    }
                }
            }
            .addOnFailureListener { it.printStackTrace() }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun openWebPage(url: String?) {
        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, it.toUri())
            startActivity(intent)
        }
    }



    private fun showPhishingWarning(url: String?) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ 피싱 의심 사이트")
            .setMessage("이 사이트는 피싱 위험이 있습니다.\n정말 접속하시겠습니까?")
            .setPositiveButton("계속하기") { _, _ -> openWebPage(url) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == requestCodePermissions) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                qrPreviewImage.visibility = View.GONE
                previewView.visibility = View.VISIBLE
                startCamera()
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
