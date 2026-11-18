package com.example.myapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TourRouteTest {

    @Test
    public void url_lonLatOrder() {
        assertEquals("https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
                        + "127.457839,36.628895;127.454222,36.630931?overview=full&geometries=geojson",
                TourRoute.url(36.628895, 127.457839, 36.630931, 127.454222));
    }

    @Test
    public void parse_route() {
        String json = "{\"code\":\"Ok\",\"routes\":[{\"distance\":400.2,\"duration\":318.5,"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":"
                + "[[127.4578,36.6289],[127.4560,36.6300],[127.4542,36.6309]]}}]}";
        TourRoute.Route route = TourRoute.parse(json);
        assertEquals(3, route.points.size());
        assertEquals(36.6289, route.points.get(0)[0], 1e-9);   // 위도가 먼저
        assertEquals(127.4578, route.points.get(0)[1], 1e-9);
        assertEquals(400.2, route.distanceM, 1e-9);
        assertEquals(318.5, route.durationSec, 1e-9);
    }

    @Test
    public void parse_failures() {
        assertNull(TourRoute.parse("{\"code\":\"NoRoute\",\"routes\":[]}"));
        assertNull(TourRoute.parse("{\"code\":\"Ok\",\"routes\":[]}"));
        assertNull(TourRoute.parse("not json"));
        assertNull(TourRoute.parse("{\"code\":\"Ok\",\"routes\":[{\"distance\":1,\"duration\":1,"
                + "\"geometry\":{\"coordinates\":[[127.4,36.6]]}}]}"));
    }
}
