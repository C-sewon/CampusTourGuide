package com.example.myapplication;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 캠퍼스에 설치된 센서 5대 목록.
 * 번호, BLE 이름, MAC은 CampusTourGuide README의 센서 표와 같다.
 * "센서 N"은 BLE 이름이 opensrcN인 센서를 뜻한다.
 */
public final class Sensors {

    public static final class Sensor {
        public final int id;
        public final String bleName;
        public final String mac;
        public final String location;

        Sensor(int id, String bleName, String mac, String location) {
            this.id = id;
            this.bleName = bleName;
            this.mac = mac;
            this.location = location;
        }

        @Override
        public String toString() {
            return "센서 " + id + " (" + bleName + ")";
        }
    }

    public static final List<Sensor> ALL = Collections.unmodifiableList(Arrays.asList(
            new Sensor(1, "opensrc1", "88:A2:9E:9B:5F:09", "도서관 뒤편 (진로창업본부 입구)"),
            new Sensor(2, "opensrc2", "88:A2:9E:9B:5E:6A", "사회과학대 입구"),
            new Sensor(3, "opensrc3", "B8:27:EB:D3:40:06", "사회과학대 옆 이마트24 건너편"),
            new Sensor(4, "opensrc4", "D8:3A:DD:79:8F:80", "테니스장 앞"),
            new Sensor(5, "opensrc5", "D8:3A:DD:C1:89:5B", "테니스장 건너편")
    ));

    private Sensors() {}

    /**
     * 스캔한 기기가 우리 센서인지 찾는다. MAC이 맞으면 그 센서, 아니면 이름으로 찾는다.
     * 둘 다 맞지 않으면 null.
     */
    public static Sensor find(String bleName, String mac) {
        if (mac != null) {
            for (Sensor s : ALL) {
                if (s.mac.equalsIgnoreCase(mac)) return s;
            }
        }
        if (bleName != null) {
            for (Sensor s : ALL) {
                if (s.bleName.equals(bleName)) return s;
            }
        }
        return null;
    }

    public static Sensor byId(int id) {
        for (Sensor s : ALL) {
            if (s.id == id) return s;
        }
        return null;
    }
}
