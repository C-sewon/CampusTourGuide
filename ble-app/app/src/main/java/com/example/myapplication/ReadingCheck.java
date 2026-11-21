package com.example.myapplication;

/**
 * 연구실 서버로 올리기 전에 센서 오류값을 거른다.
 *
 * 연구실 2번 API에는 습도 칸이 없어서, 서버가 "온도와 습도가 모두 0.0" 같은 오류를 걸러낼 수 없다.
 * 그대로 올리면 연구실 운영 데이터와 우리 서버에 0℃가 정상 값처럼 들어가므로 앱에서 거른다. (PR #32 리뷰)
 *
 * - 온도와 습도가 모두 0.0: 센서 오류값 (센서 3, 4의 증상). 온도만 0.0이면 겨울 값이라 정상이다.
 * - 온도가 -20~45℃ 밖: 고장 값 (예: 73℃). 우리 서버의 오류 범위(PR #35)와 같다.
 */
public final class ReadingCheck {

    public static final float TEMP_MIN = -20f;
    public static final float TEMP_MAX = 45f;

    private ReadingCheck() {}

    /** 올리면 안 되는 값이면 그 이유, 올려도 되면 null */
    public static String rejectReason(float temperature, float humidity) {
        if (temperature == 0f && humidity == 0f) {
            return "센서 오류값(온도와 습도가 모두 0.0)";
        }
        if (temperature < TEMP_MIN || temperature > TEMP_MAX) {
            return "온도가 오류 범위(" + (int) TEMP_MIN + "~" + (int) TEMP_MAX + "℃) 밖 (" + temperature + "℃)";
        }
        return null;
    }
}
