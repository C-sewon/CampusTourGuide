package com.example.myapplication;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReadingCheckTest {

    @Test
    public void bothZeroIsSensorError() {
        // 센서 3, 4의 증상: 온도와 습도가 모두 0.0
        assertNotNull(ReadingCheck.rejectReason(0f, 0f));
    }

    @Test
    public void zeroTemperatureAloneIsValid() {
        // 겨울 0℃는 실제로 나올 수 있으므로 습도가 있으면 정상
        assertNull(ReadingCheck.rejectReason(0f, 45.2f));
    }

    @Test
    public void zeroHumidityAloneIsValid() {
        assertNull(ReadingCheck.rejectReason(21.37f, 0f));
    }

    @Test
    public void rangeBoundariesAreValid() {
        assertNull(ReadingCheck.rejectReason(-20f, 30f));
        assertNull(ReadingCheck.rejectReason(45f, 30f));
    }

    @Test
    public void outOfRangeIsRejected() {
        assertNotNull(ReadingCheck.rejectReason(-20.1f, 30f));
        assertNotNull(ReadingCheck.rejectReason(45.1f, 30f));
        assertNotNull(ReadingCheck.rejectReason(73f, 30f));   // 고장 센서 값
        assertNotNull(ReadingCheck.rejectReason(-50f, 30f));  // 고장 센서 값
    }

    @Test
    public void normalValueIsValid() {
        assertNull(ReadingCheck.rejectReason(19.5f, 48.0f));
    }
}
