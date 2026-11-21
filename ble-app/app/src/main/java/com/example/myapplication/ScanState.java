package com.example.myapplication;

/** BLE 스캔 상태와 홈 화면 "자동 감지" 칩에 보여 줄 문구 */
public enum ScanState {
    /** 스캔 중. 센서 근처에 가면 도착을 자동으로 인식한다 */
    SCANNING("자동 감지 켜짐"),
    /** 스캔이 필요한 화면이 없어서 멈춰 있다 */
    IDLE("자동 감지 꺼짐"),
    /** 블루투스·위치 권한이 없다 */
    NO_PERMISSION("권한이 필요해요"),
    /** 블루투스가 꺼져 있다 */
    BLUETOOTH_OFF("블루투스를 켜 주세요"),
    /** 이 폰은 BLE를 지원하지 않는다 */
    UNSUPPORTED("자동 감지를 쓸 수 없어요"),
    /** 스캔 시작이 실패했다 (너무 자주 시작했거나 시스템 오류) */
    FAILED("자동 감지 오류");

    public final String label;

    ScanState(String label) {
        this.label = label;
    }

    /** 칩을 눌러서 사용자가 해결할 수 있는 상태인가 (권한 요청, 블루투스 켜기) */
    public boolean needsUserAction() {
        return this == NO_PERMISSION || this == BLUETOOTH_OFF;
    }
}
