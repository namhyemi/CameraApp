package com.example.cameraapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.AspectRatio
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.cameraapp.ui.theme.CameraAppTheme
import java.text.SimpleDateFormat
import java.util.Locale
import coil.compose.rememberAsyncImagePainter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // 메인 카메라 화면 호출
                    CameraPermissionWrapper(activity = this, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// 권한 팝업을 자동으로 처리해주는 내비게이터(래퍼) 컴포저블
@Composable
fun CameraPermissionWrapper(activity: ComponentActivity, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 카메라 권한이 허용되었는지 기억하는 상태 변수
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 팝업창 결과를 받아 처리하는 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "카메라 권한이 필수적입니다. 앱을 종료합니다.", Toast.LENGTH_LONG).show()
            activity.finish()
        }
    }

    // 앱이 처음 켜질 때(정확히는 이 컴포저블이 화면에 등록될 때) 권한이 없으면 팝업을 띄움
    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 권한이 있으면 카메라를 켜고, 없으면 빈 화면
    if (hasCameraPermission) {
        CameraScreen(modifier = modifier)
    }
}

// 카메라 프리뷰 전용 컴포저블
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // 현재 카메라 방향 상태 (기본값: 후면)
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    // 해상도/비율 세팅
    val resolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(
                    AspectRatio.RATIO_4_3, //
                    AspectRatioStrategy.FALLBACK_RULE_AUTO // 해당 비율이 없으면 자동으로 비슷한 비율 선택
                )
            )
            .build()
    }

    // 사진 촬영을 담당하는 ImageCapture 객체를 remember로 안전하게 보존
    val imageCapture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    // 시스템 셔터 소리를 내주는 객체 생성 및 리소스 미리 로드
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    // 깜빡임 애니메이션을 트리거할 상태 변수 (true가 되면 하얀 화면이 켜짐)
    var isBlinking by remember { mutableStateOf(false) }

    // isBlinking 변수의 변화에 따라 투명도(Float)를 0.0에서 0.6까지 자동으로 변하게 만드는 애니메이션 매핑
    val blinkAlpha by animateFloatAsState(
        targetValue = if (isBlinking) 0.6f else 0.0f,
        animationSpec = tween(durationMillis = 10), // 0.1초 동안 빠르게 변함
        label = "ShutterBlink",
        finishedListener = {
            // 하얗게 변하는 게 끝나면(finished), 다시 투명(false)하게 되돌려서 깜빡임 효과
            if (isBlinking) { isBlinking = false }
        }
    )

    // PreviewView를 미리 한 번만 생성해서 remember로 보관
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // 바인딩된 카메라의 하드웨어 제어권을 담아둘 변수
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    // 최근에 저장된 사진의 Uri 주소를 기억하는 상태 변수
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 스마트폰 저장소에서 가장 최근 사진의 Uri를 가져와 갱신 (지워졌으면 null이 됨)
                lastSavedUri = fetchLatestGalleryImageUri(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    // lensFacing 변수가 바뀔 때마다 기존 카메라를 안전하게 unbind하고 새로 세션을 연결
    LaunchedEffect(lensFacing) {
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            Log.d("CameraX", "카메라 전환 성공: $lensFacing")
        } catch (e: Exception) {
            Log.e("CameraX", "카메라 바인딩 실패", e)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black) // 여백 공간을 검은색으로 처리
    ) {
        // 카메라 프리뷰 레이어
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // 중요: 남는 공간을 모두 차지하도록 설정하여 프리뷰 위치를 위로 올림
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
            ) {
                // 실제 카메라 렌즈 화면
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(lensFacing) {
                            detectTapGestures(
                                onTap = { touchPoint ->
                                    // 1. 활성화된 카메라 하드웨어 제어 부품(cameraControl)이 있는지 확인
                                    val cameraControl = camera?.cameraControl ?: return@detectTapGestures

                                    // 2. 프리뷰 화면(PreviewView) 기준으로 터치된 좌표를 카메라 센서 좌표계로 변환
                                    val factory: MeteringPointFactory = previewView.meteringPointFactory

                                    // 3. 터치란 X, Y 좌표를 넘겨주어 카메라용 초점 포인트를 생성
                                    val point = factory.createPoint(touchPoint.x, touchPoint.y)

                                    // 4. 이 포인트를 기반으로 초점(Focus)과 노출(Metering)을 동시에 맞추라는 액션 명령서를 작성
                                    val action = FocusMeteringAction.Builder(point).build()

                                    // 5. 하드웨어 카메라에 명령서를 전달하여 실제로 렌즈 작동
                                    cameraControl.startFocusAndMetering(action)
                                    Log.e("CameraX", "터치한 위치로 초점 맞추기 실행: X=${touchPoint.x}, Y=${touchPoint.y}")
                                }
                            )
                        },
                    update = { /* LaunchedEffect에서 전환을 처리하므로 여기는 비워둠. */ }
                )

                // 촬영 순간 하얀색 오버레이 깜빡임 효과 (이제 부모 박스가 4:3이므로 정확히 프리뷰와 100% 일치하여 번쩍임)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(blinkAlpha)
                        .background(Color.DarkGray)
                )
            }
        }

        // 버튼 전용 레이아웃
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black) // 버튼 뒷배경을 깔끔한 블랙 바 스타일로 처리
                .navigationBarsPadding()
                .padding(vertical = 35.dp),
            contentAlignment = Alignment.Center
        ) {
            // 하단 버튼 레이아웃 (셔터 버튼과 전환 버튼을 가로로 정렬)
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 사진이 찍혀서 lastSavedUri가 존재하면 동그란 썸네일을 띄움
                if (lastSavedUri != null) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray)
                            .border(1.dp, Color.White, CircleShape)
                            .clickable {
                                try {
                                    // 🌟 [교정] ACTION_VIEW를 유지하되, 갤러리 앱이 주변 사진들을 연속으로 띄울 수 있도록 힌트 플래그들을 추가합니다.
                                    val galleryIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(lastSavedUri, "image/*")

                                        // 단서 1: 다른 앱(갤러리)이 이 파일의 Uri 권한을 확실하게 가질 수 있도록 부여
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                                        // 단서 2: 단일 파일 일회성 뷰어가 아닌 기기 시스템 표준 갤러리 흐름을 타도록 강제 유도
                                        addCategory(Intent.CATEGORY_DEFAULT)

                                        // 단서 3: 갤러리 앱에게 "이 사진은 내부 저장소(Storage) 파일 뭉치에서 나온 것"임을 알려 주변 파일 인덱싱 유도
                                        putExtra("android.intent.extra.FROM_STORAGE", true)
                                        putExtra("com.android.camera.ReviewActivity", true) // 일부 구형/특정 제조사 카메라 연동 뷰어 인식용 플래그
                                    }
                                    context.startActivity(galleryIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "갤러리 앱을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter(model = lastSavedUri),
                            contentDescription = "최근 촬영 사진 썸네일",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    // 아직 사진을 한 장도 안 찍었을 때는 균형 유지를 위해 기존 투명 공간을 유지합니다.
                    Spacer(modifier = Modifier.size(50.dp))
                }

                // 메인 셔터 버튼
                Box(
                    modifier = Modifier
                        .padding(horizontal = 40.dp)
                        .size(80.dp)
                        .background(Color.White, shape = CircleShape)
                        .clickable {
                            sound.play(MediaActionSound.SHUTTER_CLICK)
                            isBlinking = true
                            takePhotoAndSaveToGallery(context, imageCapture) { savedUri ->
                                lastSavedUri = savedUri
                            } // 버튼 클릭 시 사진 촬영 함수 호출
                        }
                )

                // 카메라 전/후면 전환 버튼
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(Color.DarkGray.copy(alpha = 0.7f), shape = CircleShape)
                        .clickable {
                            // 버튼을 누르면 전면/후면 토글
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh, // 안드로이드 기본 새로고침(전환) 아이콘 사용
                        contentDescription = "카메라 전환",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

// 기기 저장소(MediaStore)에서 가장 최근 사진 1장의 Uri 주소를 쿼리해오는 함수
private fun fetchLatestGalleryImageUri(context: Context): Uri? {
    val projection = arrayOf(
        MediaStore.Images.ImageColumns._ID,
        MediaStore.Images.ImageColumns.DATE_TAKEN
    )

    // 촬영 날짜 내림차순(최신순) 정렬 쿼리 정의
    val sortOrder = "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC"

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        sortOrder
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)
            val id = cursor.getLong(idColumn)
            // 찾은 ID값을 기반으로 해당 파일의 실제 접근가능한 콘텐트 Uri 조합 후 리턴
            return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        }
    }
    return null
}

// 사진을 찍어서 공용 갤러리(Pictures) 폴더에 등록하는 함수
private fun takePhotoAndSaveToGallery(context: Context, imageCapture: ImageCapture, onImageSavedAction: (Uri) -> Unit) {
    // 파일 이름에 사용할 날짜 포맷 생성 (예: 20260613_161230)
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        .format(System.currentTimeMillis())

    // 안드로이드 공용 미디어 데이터베이스(MediaStore)에 세팅할 파일 정보 꾸러미 생성
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        // 안드로이드 10(Q) 이상 버전인 경우 공용 Pictures 디렉토리에 전용 폴더 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyCameraApp")
        }
    }

    // 사진 저장을 위한 설정 옵션 객체 만들기
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, // MediaStore를 통해 저장 옵션 설정
        contentValues
    ).build()

    // 이미지 캡처 실행 및 콜백 리스너 등록
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // 성공 시: 토스트 알림을 띄우고 저장된 경로를 로그에 출력
                Toast.makeText(context, "갤러리에 사진이 저장되었습니다!", Toast.LENGTH_SHORT).show()

                // 저장 완료 후 발급된 파일의 Uri 주소를 안전하게 추출하여 넘겨줌
                outputFileResults.savedUri?.let { uri ->
                    onImageSavedAction(uri)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                // 실패 시: 에러 로그
                Log.e("CameraX", "갤러리 저장 실패: ${exception.message}", exception)
            }
        }
    )
}