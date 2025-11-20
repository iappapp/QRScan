package com.github.iappapp.qrcode

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private var isProcessing = false // 防止连续多次触发跳转

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 动态创建 UI (全屏相机预览)
        // 这样你就不用创建 activity_main.xml 布局文件了
        previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(previewView)

        // 2. 初始化后台线程执行器
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 3. 检查并请求相机权限
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 注册权限请求回调
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能扫描二维码", Toast.LENGTH_LONG).show()
                finish() // 如果没有权限，退出应用
            }
        }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // 获取相机提供者
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 设置预览
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // 设置图像分析 (二维码扫描核心逻辑)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只处理最新的一帧
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { url ->
                        handleDetectedUrl(url)
                    })
                }

            // 选择后置摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 解绑之前的所有用例
                cameraProvider.unbindAll()

                // 绑定用例到生命周期
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Toast.makeText(this, "相机启动失败: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 处理检测到的 URL
    private fun handleDetectedUrl(url: String) {
        if (isProcessing) return // 如果正在处理跳转，忽略后续帧
        isProcessing = true

        runOnUiThread {
            Toast.makeText(this, "URL: $url", Toast.LENGTH_SHORT).show()

            try {
                // 创建 Intent 打开浏览器
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)

                // 可选：跳转后 finish() 关闭当前扫码页，或者保留在后台
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开链接: ${e.message}", Toast.LENGTH_SHORT).show()
                isProcessing = false // 出错后允许重新扫描
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // --- 内部类：二维码分析器 ---
    private class QrCodeAnalyzer(private val onUrlDetected: (String) -> Unit) : ImageAnalysis.Analyzer {

        // 获取 ML Kit 的扫描客户端
        private val scanner = BarcodeScanning.getClient()

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                // 将相机图像转换为 ML Kit 需要的 InputImage
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            // 获取原始值
                            val rawValue = barcode.rawValue ?: ""

                            // 判断是否是 URL
                            // 方法 1: ML Kit 自带类型判断 (更严谨)
                            val isUrlType = barcode.valueType == Barcode.TYPE_URL

                            // 方法 2: 使用 Android Patterns 正则匹配 (更宽泛，防止类型识别错误)
                            val isUrlPattern = Patterns.WEB_URL.matcher(rawValue).matches()

                            if (isUrlType || isUrlPattern) {
                                // 只要符合 URL 特征，就回调
                                onUrlDetected(rawValue)
                                break // 这一帧只处理一个码
                            }
                        }
                    }
                    .addOnFailureListener {
                        // 识别失败暂不处理
                    }
                    .addOnCompleteListener {
                        // 无论成功失败，必须关闭 imageProxy 以便分析下一帧
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}