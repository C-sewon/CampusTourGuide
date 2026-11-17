package com.example.myapplication;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;

import java.io.File;

/** 화면들이 같이 쓰는 지도(osmdroid, OpenStreetMap) 설정 */
public final class TourMap {

    private TourMap() {}

    /** setContentView 전에 부른다. 지도 서버에 앱을 밝히고(User-Agent), 타일 캐시는 앱 전용 폴더에 둔다 */
    public static void configure(Context context) {
        // OSM 은 com.example 같은 기본 이름을 차단(403)하므로 앱 고유 이름을 쓴다
        Configuration.getInstance().setUserAgentValue("CampusOn/0.1 (Android)");
        Configuration.getInstance().setOsmdroidBasePath(new File(context.getCacheDir(), "osmdroid"));
        Configuration.getInstance().setOsmdroidTileCache(new File(context.getCacheDir(), "osmdroid/tiles"));
    }

    /** 기본 지도 설정: OSM 타일, 두 손가락 확대, 출처 표시, 지도를 끌 때 화면 스크롤 막기 */
    @SuppressLint("ClickableViewAccessibility")
    public static void setup(Context context, MapView mapView) {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        // OpenStreetMap 사용 조건: 지도에 출처 표시
        mapView.getOverlays().add(new CopyrightOverlay(context));
        mapView.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
    }

    /**
     * 진행 방향 화살표 아이콘: 흰 테두리 동그라미 안에 흰 꺾쇠.
     * bearing 은 북쪽 기준 시계 방향 각도 (GeoPoint.bearingTo 값). 지도를 돌리지 않으므로 화면 위쪽이 북쪽이다.
     */
    public static BitmapDrawable arrowIcon(Context context, double bearing, int color) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(24 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;

        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(r, r, r, paint);
        paint.setColor(color);
        canvas.drawCircle(r, r, r - 2 * density, paint);

        // 위쪽을 가리키는 꺾쇠를 그린 뒤 진행 방향만큼 돌린다
        canvas.rotate((float) bearing, r, r);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path chevron = new android.graphics.Path();
        float w = 4.5f * density;
        chevron.moveTo(r - w, r + w * 0.5f);
        chevron.lineTo(r, r - w * 0.6f);
        chevron.lineTo(r + w, r + w * 0.5f);
        canvas.drawPath(chevron, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /** 내 위치 아이콘: 연한 파란 원 위에 흰 테두리 파란 점 (프로토타입의 현재 위치 점) */
    public static BitmapDrawable myLocationIcon(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(34 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;
        paint.setColor(0x2E2F6BD9);
        canvas.drawCircle(r, r, r, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(r, r, 9 * density, paint);
        paint.setColor(0xFF2F6BD9);
        canvas.drawCircle(r, r, 6.5f * density, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    /** 흰 테두리가 있는 동그라미 안에 글자(센서 번호 등)를 쓴 아이콘 */
    public static BitmapDrawable circleIcon(Context context, String label, int color) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round((label.length() > 2 ? 32 : 28) * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;

        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(r, r, r, paint);
        paint.setColor(color);
        canvas.drawCircle(r, r, r - 2.5f * density, paint);

        paint.setColor(0xFFFFFFFF);
        paint.setTextSize((label.length() > 2 ? 10 : 12) * density);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        float textY = r - (paint.descent() + paint.ascent()) / 2;
        canvas.drawText(label, r, textY, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
